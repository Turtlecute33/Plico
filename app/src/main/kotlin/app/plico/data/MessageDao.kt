package app.plico.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Message rows, their folder memberships, bodies, attachments and search index (DESIGN.md §5).
 *
 * The sync writes ([upsert], [syncFlags]) carry server state and never change the flags or labels of a
 * row that a pending op references (`protocol PS2`). The `set…`, [addToFolder] and [removeFromFolder]
 * writes are local user actions and always apply.
 */
class MessageDao(private val db: Db) {

    // ---- Lists and detail

    /**
     * The newest [limit] rows of [view], newest first. Hidden rows and server copies of local drafts
     * (`protocol PS7`) are left out. Starred leaves out Trash and Spam; Search uses the local index and adds
     * [serverHits] (the first [MAX_SERVER_HITS] ids of a server search, DESIGN §7.7).
     */
    fun observeList(view: MailView, limit: Int, serverHits: List<Long> = emptyList()): Flow<List<MessageSummary>> {
        val (where, args) = when (view) {
            MailView.Unified -> "m.id IN (SELECT mf.message_id FROM message_folder mf " +
                "JOIN folder f ON f.id = mf.folder_id WHERE f.role = 'inbox')" to emptyList()
            is MailView.InFolder ->
                "m.id IN (SELECT message_id FROM message_folder WHERE folder_id = ?)" to listOf(view.folderId)
            is MailView.Starred -> "m.flagged = 1 AND m.folder_id NOT IN " +
                "(SELECT id FROM folder WHERE role IN ('trash', 'junk'))" + accountFilter(view.accountId) to
                listOfNotNull(view.accountId)
            is MailView.Search -> {
                val match = ftsQuery(view.query) ?: return flowOf(emptyList())
                // Numbers only, so inlining them cannot inject SQL.
                val hits = serverHits.take(MAX_SERVER_HITS).sqlList()
                "(m.id IN (SELECT rowid FROM message_fts WHERE message_fts MATCH ?) OR m.id IN ($hits))" +
                    accountFilter(view.accountId) to listOfNotNull(match, view.accountId)
            }
        }
        val tables = when (view) {
            MailView.Unified -> arrayOf(Tables.MESSAGE, Tables.MESSAGE_FOLDER, Tables.FOLDER, Tables.DRAFT)
            is MailView.InFolder -> arrayOf(Tables.MESSAGE, Tables.MESSAGE_FOLDER, Tables.DRAFT)
            is MailView.Starred -> arrayOf(Tables.MESSAGE, Tables.FOLDER, Tables.DRAFT)
            is MailView.Search -> arrayOf(Tables.MESSAGE, Tables.MESSAGE_FTS, Tables.DRAFT)
        }
        val sql = "SELECT $SUMMARY_COLS FROM message m WHERE $where AND $VISIBLE ORDER BY m.date DESC LIMIT $limit"
        val bound = args.toTypedArray<Any>()
        return db.observe(*tables) { list(sql, *bound, row = ::summaryOf) }
    }

    fun get(id: Long): Message? = db.sql.message(id)

    /** The message with its body, attachments and folders; null once the row is gone. */
    fun observeDetail(id: Long): Flow<MessageDetail?> =
        db.observe(Tables.MESSAGE, Tables.BODY, Tables.ATTACHMENT, Tables.MESSAGE_FOLDER, Tables.FOLDER) {
            message(id)?.let { m ->
                MessageDetail(
                    message = m,
                    body = body(id),
                    attachments = attachmentRows(id),
                    folders = list(
                        "SELECT $FOLDER_COLS FROM folder WHERE id IN " +
                            "(SELECT folder_id FROM message_folder WHERE message_id = ?) ORDER BY kind, path",
                        id, row = ::folderOf,
                    ),
                )
            }
        }

    fun body(messageId: Long): Body? = db.sql.body(messageId)

    fun attachments(messageId: Long): List<Attachment> = db.sql.attachmentRows(messageId)

    fun attachment(id: Long): Attachment? =
        db.sql.one("SELECT $ATTACHMENT_COLS FROM attachment WHERE id = ?", id, row = ::attachmentOf)

    // ---- Lookups for sync and ops

    fun byUid(folderId: Long, uid: Long): Message? =
        db.sql.one("SELECT $MESSAGE_COLS FROM message WHERE folder_id = ? AND uid = ?", folderId, uid, row = ::messageOf)

    /** uid -> id for the rows of [folderId] among [uids]. */
    fun idsByUid(folderId: Long, uids: Collection<Long>): Map<Long, Long> = uids.batches().flatMap { batch ->
        db.sql.list("SELECT uid, id FROM message WHERE folder_id = ? AND uid IN (${batch.sqlList()})", folderId) { c ->
            c.getLong(0) to c.getLong(1)
        }
    }.toMap()

    /** gm_msgid -> id for the account's rows among [gmMsgIds]. */
    fun idsByGmMsgId(accountId: Long, gmMsgIds: Collection<Long>): Map<Long, Long> =
        gmMsgIds.batches().flatMap { batch ->
            db.sql.list(
                "SELECT gm_msgid, id FROM message WHERE account_id = ? AND gm_msgid IN (${batch.sqlList()})",
                accountId,
            ) { c -> c.getLong(0) to c.getLong(1) }
        }.toMap()

    /** Rows of the account whose Message-ID header is [messageId] (with angle brackets). */
    fun byMessageId(accountId: Long, messageId: String): List<Message> = db.sql.list(
        "SELECT $MESSAGE_COLS FROM message WHERE account_id = ? AND message_id = ? ORDER BY id",
        accountId, messageId, row = ::messageOf,
    )

    /** Server locations of the rows that still exist among [ids], by id. */
    fun refs(ids: Collection<Long>): List<MessageRef> = ids.batches().flatMap { batch ->
        db.sql.list("SELECT id, folder_id, uid FROM message WHERE id IN (${batch.sqlList()})", row = ::refOf)
    }.sortedBy { it.id }

    /** Local UIDs of [folderId] in [from]..[to], ascending; hidden rows included. */
    fun uids(folderId: Long, from: Long = 1, to: Long = Long.MAX_VALUE): List<Long> = db.sql.list(
        "SELECT uid FROM message WHERE folder_id = ? AND uid BETWEEN ? AND ? ORDER BY uid",
        folderId, from, to,
    ) { it.getLong(0) }

    /** The [limit] highest local UIDs of [folderId], ascending. */
    fun newestUids(folderId: Long, limit: Int): List<Long> = db.sql.list(
        "SELECT uid FROM message WHERE folder_id = ? ORDER BY uid DESC LIMIT $limit",
        folderId,
    ) { it.getLong(0) }.asReversed()

    /** Number of local rows of [folderId] with a UID in [from]..[to]; hidden rows included. */
    fun count(folderId: Long, from: Long = 1, to: Long = Long.MAX_VALUE): Int = db.sql.long(
        "SELECT count(*) FROM message WHERE folder_id = ? AND uid BETWEEN ? AND ?",
        folderId, from, to,
    )!!.toInt()

    /** The newest [limit] visible rows of [folderId] without a body, dated >= [since] (0 = all). */
    fun needingBodies(folderId: Long, since: Long, limit: Int): List<MessageRef> = db.sql.list(
        "SELECT id, folder_id, uid FROM message WHERE folder_id = ? AND body = 0 AND hidden = 0 AND date >= ? " +
            "ORDER BY date DESC LIMIT $limit",
        folderId, since, row = ::refOf,
    )

    /** The ids among [ids] that are members of [folderId] (source mailbox or label view). */
    fun memberIds(folderId: Long, ids: Collection<Long>): Set<Long> = ids.batches().flatMapTo(HashSet()) { batch ->
        db.sql.list(
            "SELECT message_id FROM message_folder WHERE folder_id = ? AND message_id IN (${batch.sqlList()})",
            folderId,
        ) { it.getLong(0) }
    }

    /** The Gmail label views of each of [ids]; an id without labels, or without a row, maps to an empty set. */
    fun labelIds(ids: Collection<Long>): Map<Long, Set<Long>> {
        val labels = ids.associateWithTo(HashMap()) { HashSet<Long>() }
        for (batch in ids.batches()) {
            db.sql.list(
                "SELECT mf.message_id, mf.folder_id FROM message_folder mf JOIN folder f ON f.id = mf.folder_id " +
                    "WHERE f.kind = ${FolderKind.LABEL} AND mf.message_id IN (${batch.sqlList()})",
            ) { it.getLong(0) to it.getLong(1) }.forEach { (message, label) -> labels[message]?.add(label) }
        }
        return labels
    }

    /** True when a row other than [exceptId] from [email] (any case) is dated before [before]. */
    fun hasEarlierFrom(email: String, before: Long, exceptId: Long): Boolean = db.sql.long(
        "SELECT 1 FROM message WHERE from_email = ? COLLATE NOCASE AND date < ? AND id != ? LIMIT 1",
        email, before, exceptId,
    ) != null

    // ---- Sync writes

    /**
     * Inserts header rows, or updates gm_msgid, modseq and flags of the rows that exist with the same
     * (folder, uid) (`INSERT … ON CONFLICT DO UPDATE`, never REPLACE, so bodies survive; `protocol PS10`).
     * Header fields, notified and hidden of existing rows are kept. Every row gets its source-folder
     * membership; [NewMessage.labels] replace its label memberships; new rows get a search-index row.
     * Returns the ids in the order of [rows].
     */
    fun upsert(rows: List<NewMessage>): List<Long> = db.tx(*Tables.MESSAGE_OWNED) {
        val protected = opMessageIds()
        rows.map { m ->
            val existing = long("SELECT id FROM message WHERE folder_id = ? AND uid = ?", m.folderId, m.uid)
            val keepLocal = existing != null && existing in protected
            val inserted = insertRow(
                UPSERT,
                m.accountId, m.folderId, m.uid, m.gmMsgId, m.messageId, m.inReplyTo, m.refs, m.subject,
                m.fromName, m.fromEmail, m.toList, m.ccList, m.bccList, m.replyTo, m.date, m.size, m.seen,
                m.flagged, m.answered, m.draft, m.modSeq, m.attachments, m.auth, m.unsubscribe, m.unsubscribePost,
                m.notified, keepLocal,
            )
            // On the update path executeInsert() returns a stale rowid, so the known id wins.
            val id = existing ?: inserted
            if (existing == null) writeFts(id, "")
            write("INSERT OR IGNORE INTO message_folder(folder_id, message_id) VALUES(?, ?)", m.folderId, id)
            if (m.labels != null && !keepLocal) writeLabels(id, m.folderId, m.labels)
            id
        }
    }

    /**
     * Writes server flags (and Gmail labels when given) to existing rows of [folderId]. Unknown UIDs are
     * ignored; rows that a pending op references are skipped. Unchanged rows are not written.
     */
    fun syncFlags(folderId: Long, flags: List<ServerFlags>): Unit = db.tx(Tables.MESSAGE, Tables.MESSAGE_FOLDER) {
        val protected = opMessageIds()
        for (f in flags) {
            val (id, current) = one(
                "SELECT id, seen, flagged, answered, draft, modseq FROM message WHERE folder_id = ? AND uid = ?",
                folderId, f.uid,
            ) { c ->
                c.getLong(0) to ServerFlags(f.uid, c.bool(1), c.bool(2), c.bool(3), c.bool(4), c.getLong(5))
            } ?: continue
            if (id in protected) continue
            val wanted = f.copy(modSeq = f.modSeq ?: current.modSeq, labels = null)
            if (wanted != current) {
                write(
                    "UPDATE message SET seen = ?, flagged = ?, answered = ?, draft = ?, modseq = ? WHERE id = ?",
                    f.seen, f.flagged, f.answered, f.draft, wanted.modSeq, id,
                )
            }
            f.labels?.let { writeLabels(id, folderId, it) }
        }
    }

    /**
     * Moves a row in place after a MOVE with COPYUID (`protocol PS26`): new source folder and UID, body and
     * attachments kept, visible again. Label memberships are dropped (a Gmail message in Trash or Spam has
     * none). A row that already holds the target UID is replaced by this one.
     */
    fun moveRow(id: Long, folderId: Long, uid: Long): Unit = db.tx(*Tables.MESSAGE_OWNED) {
        write("DELETE FROM message WHERE folder_id = ? AND uid = ? AND id != ?", folderId, uid, id)
        write("UPDATE message SET folder_id = ?, uid = ?, modseq = 0, hidden = 0 WHERE id = ?", folderId, uid, id)
        write("DELETE FROM message_folder WHERE message_id = ?", id)
        write("INSERT INTO message_folder(folder_id, message_id) VALUES(?, ?)", folderId, id)
    }

    /** Deletes rows (expunged, or a MOVE without COPYUID). Returns the number deleted. */
    fun delete(ids: Collection<Long>): Int = db.tx(*Tables.MESSAGE_OWNED) {
        ids.batches().sumOf { write("DELETE FROM message WHERE id IN (${it.sqlList()})") }
    }

    /** Deletes the rows of [folderId] with these UIDs. Returns the number deleted. */
    fun deleteUids(folderId: Long, uids: Collection<Long>): Int = db.tx(*Tables.MESSAGE_OWNED) {
        uids.batches().sumOf { write("DELETE FROM message WHERE folder_id = ? AND uid IN (${it.sqlList()})", folderId) }
    }

    /**
     * Stores a downloaded body in one transaction: [html] and [text] for display, the attachment rows
     * (replacing earlier ones), the snippet and attachment count, and [searchText] in the search index.
     * Does nothing when the row is gone.
     */
    fun saveBody(
        messageId: Long,
        html: String?,
        text: String?,
        searchText: String,
        snippet: String,
        attachments: List<NewAttachment>,
    ): Unit = db.tx(Tables.MESSAGE, Tables.BODY, Tables.ATTACHMENT, Tables.MESSAGE_FTS) {
        if (long("SELECT id FROM message WHERE id = ?", messageId) == null) return@tx
        write(
            "INSERT INTO body(message_id, html, text) VALUES(?, ?, ?) " +
                "ON CONFLICT(message_id) DO UPDATE SET html = excluded.html, text = excluded.text",
            messageId, html, text,
        )
        write("DELETE FROM attachment WHERE message_id = ?", messageId)
        for (a in attachments) {
            insertRow(
                "INSERT INTO attachment(message_id, section, name, mime, size, encoding, cid, inline) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                messageId, a.section, a.name, a.mime, a.size, a.encoding, a.cid, a.inline,
            )
        }
        write(
            "UPDATE message SET body = 1, snippet = ?, attachments = ? WHERE id = ?",
            snippet, attachments.count { !it.inline }, messageId,
        )
        writeFts(messageId, searchText)
    }

    // ---- Local edits (user actions; ops carry them to the server)

    fun setSeen(ids: Collection<Long>, seen: Boolean): Unit = setColumn(ids, "seen", seen)
    fun setFlagged(ids: Collection<Long>, flagged: Boolean): Unit = setColumn(ids, "flagged", flagged)
    fun setAnswered(ids: Collection<Long>, answered: Boolean): Unit = setColumn(ids, "answered", answered)

    /** Hidden rows are left out of every list and count (pending move or delete). */
    fun setHidden(ids: Collection<Long>, hidden: Boolean): Unit = setColumn(ids, "hidden", hidden)

    /** Adds the rows to a Gmail label view. */
    fun addToFolder(ids: Collection<Long>, folderId: Long): Unit = db.tx(Tables.MESSAGE_FOLDER) {
        for (id in ids) write("INSERT OR IGNORE INTO message_folder(folder_id, message_id) VALUES(?, ?)", folderId, id)
    }

    /** Removes the rows from a Gmail label view. A row's source-mailbox membership is never removed. */
    fun removeFromFolder(ids: Collection<Long>, folderId: Long): Unit = db.tx(Tables.MESSAGE_FOLDER) {
        for (batch in ids.batches()) {
            write(
                "DELETE FROM message_folder WHERE folder_id = ? AND message_id IN (${batch.sqlList()}) " +
                    "AND message_id NOT IN (SELECT id FROM message WHERE folder_id = ?)",
                folderId, folderId,
            )
        }
    }

    // ---- Notifications (DESIGN.md §9)

    /**
     * Rows of the account that notify unless a later check drops them (the Gmail category filter): not yet
     * notified, unseen, visible, dated >= [since], in a notify scope (an inbox, or a folder or label view
     * with notify = 1). Newest first.
     */
    fun notifyCandidates(accountId: Long, since: Long): List<MessageRef> = db.sql.list(
        "SELECT m.id, m.folder_id, m.uid FROM message m WHERE $NOTIFY_RULE ORDER BY m.date DESC",
        accountId, since, row = ::refOf,
    )

    /**
     * Returns the rows that notify now ([notifyCandidates] as list rows) and marks every row of the account
     * with notified = 0 as notified, in one transaction, so no row notifies twice or late.
     */
    fun claimNotifications(accountId: Long, since: Long): List<MessageSummary> {
        db.sql.long("SELECT 1 FROM message WHERE account_id = ? AND notified = 0 LIMIT 1", accountId)
            ?: return emptyList()
        return db.tx(Tables.MESSAGE) {
            val rows = list(
                "SELECT $SUMMARY_COLS FROM message m WHERE $NOTIFY_RULE ORDER BY m.date DESC",
                accountId, since, row = ::summaryOf,
            )
            write("UPDATE message SET notified = 1 WHERE account_id = ? AND notified = 0", accountId)
            rows
        }
    }

    /** Marks rows as notified, e.g. new mail that fails the Gmail category filter. */
    fun markNotified(ids: Collection<Long>): Unit = setColumn(ids, "notified", true)

    /** The ids among [ids] that may still show a notification: unseen, visible and in a notify scope. */
    fun stillNotifiable(ids: Collection<Long>): Set<Long> = ids.batches().flatMapTo(HashSet()) { batch ->
        db.sql.list(
            "SELECT m.id FROM message m WHERE m.id IN (${batch.sqlList()}) AND m.seen = 0 AND $VISIBLE AND $NOTIFY_SCOPE",
        ) { it.getLong(0) }
    }

    // ---- Helpers

    private fun setColumn(ids: Collection<Long>, column: String, value: Boolean) = db.tx(Tables.MESSAGE) {
        for (batch in ids.batches()) write("UPDATE message SET $column = ? WHERE id IN (${batch.sqlList()})", value)
    }

    /** Sets the label memberships of a row to [labels]; the source membership stays. */
    private fun SQLiteDatabase.writeLabels(id: Long, sourceId: Long, labels: Set<Long>) {
        val current = list(
            "SELECT folder_id FROM message_folder WHERE message_id = ? AND folder_id != ?",
            id, sourceId,
        ) { it.getLong(0) }.toSet()
        if (current == labels - sourceId) return
        write("DELETE FROM message_folder WHERE message_id = ? AND folder_id != ?", id, sourceId)
        for (label in labels) {
            write("INSERT OR IGNORE INTO message_folder(folder_id, message_id) VALUES(?, ?)", label, id)
        }
    }

    /** Writes the search-index row of a message from its headers and [body] (`privacy PS8`: OR REPLACE). */
    private fun SQLiteDatabase.writeFts(id: Long, body: String) = write(
        "INSERT OR REPLACE INTO message_fts(rowid, subject, people, body) SELECT id, subject, " +
            "from_name || ' ' || from_email || ' ' || to_list || ' ' || cc_list || ' ' || bcc_list, ? " +
            "FROM message WHERE id = ?",
        body, id,
    )

    private fun SQLiteDatabase.message(id: Long) =
        one("SELECT $MESSAGE_COLS FROM message WHERE id = ?", id, row = ::messageOf)

    private fun SQLiteDatabase.body(id: Long) =
        one("SELECT message_id, html, text FROM body WHERE message_id = ?", id) { c ->
            Body(c.getLong(0), c.strOrNull(1), c.strOrNull(2))
        }

    private fun SQLiteDatabase.attachmentRows(messageId: Long) = list(
        "SELECT $ATTACHMENT_COLS FROM attachment WHERE message_id = ? ORDER BY id",
        messageId, row = ::attachmentOf,
    )

    companion object {
        /**
         * FTS4 MATCH expression for a user query: each whitespace-separated word becomes a quoted phrase of
         * its letter and digit runs, with a prefix star on the last one. Quotes and operators cannot pass.
         * Null when nothing searchable is left.
         */
        fun ftsQuery(query: String): String? = query.split(WHITESPACE).mapNotNull { word ->
            val tokens = word.split(NON_TOKEN).filter(String::isNotEmpty)
            if (tokens.isEmpty()) null else tokens.joinToString(" ", "\"", "*\"")
        }.joinToString(" ").ifEmpty { null }

        private val WHITESPACE = Regex("\\s+")
        private val NON_TOKEN = Regex("[^\\p{L}\\p{N}\\p{M}]+")

        private fun accountFilter(accountId: Long?) = if (accountId == null) "" else " AND m.account_id = ?"

        // The last bound value (keep) tells the update path to leave the local flags alone (protocol PS2).
        private const val UPSERT = """
            INSERT INTO message(account_id, folder_id, uid, gm_msgid, message_id, in_reply_to, refs, subject,
              from_name, from_email, to_list, cc_list, bcc_list, reply_to, date, size, seen, flagged, answered,
              draft, modseq, attachments, auth, unsubscribe, unsubscribe_post, notified)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(folder_id, uid) DO UPDATE SET
              gm_msgid = coalesce(excluded.gm_msgid, gm_msgid), modseq = excluded.modseq,
              seen = CASE WHEN :keep THEN seen ELSE excluded.seen END,
              flagged = CASE WHEN :keep THEN flagged ELSE excluded.flagged END,
              answered = CASE WHEN :keep THEN answered ELSE excluded.answered END,
              draft = CASE WHEN :keep THEN draft ELSE excluded.draft END
            """

        /** Not hidden, and not a server copy of a local draft (`protocol PS7`). */
        private const val VISIBLE = "m.hidden = 0 AND NOT EXISTS " +
            "(SELECT 1 FROM draft d WHERE d.account_id = m.account_id AND d.message_id = m.message_id)"

        /** An inbox (Riseup INBOX, Gmail \Inbox) or a folder or label view with notify = 1. */
        private const val NOTIFY_SCOPE = "EXISTS (SELECT 1 FROM message_folder nf JOIN folder nfo ON nfo.id = nf.folder_id " +
            "WHERE nf.message_id = m.id AND (nfo.role = 'inbox' OR nfo.notify = 1))"

        /** DESIGN.md §9 rule; binds the account id and the 7-day cutoff. */
        private const val NOTIFY_RULE = "m.account_id = ? AND m.notified = 0 AND m.seen = 0 AND m.date >= ? " +
            "AND $VISIBLE AND $NOTIFY_SCOPE"

        /** Server hits beyond this are not listed: the statement stays small. */
        const val MAX_SERVER_HITS = 1_000

        private const val SUMMARY_COLS = "m.id, m.account_id, m.folder_id, m.subject, m.from_name, m.from_email, " +
            "m.date, m.seen, m.flagged, m.draft, m.attachments, m.snippet, m.to_list"

        private const val MESSAGE_COLS = "id, account_id, folder_id, uid, gm_msgid, message_id, in_reply_to, refs, " +
            "subject, from_name, from_email, to_list, cc_list, bcc_list, reply_to, date, size, seen, flagged, " +
            "answered, draft, modseq, attachments, snippet, auth, unsubscribe, unsubscribe_post, body, notified, hidden"

        private const val ATTACHMENT_COLS = "id, message_id, section, name, mime, size, encoding, cid, inline"

        private fun refOf(c: Cursor) = MessageRef(c.getLong(0), c.getLong(1), c.getLong(2))

        private fun summaryOf(c: Cursor) = MessageSummary(
            id = c.getLong(0),
            accountId = c.getLong(1),
            folderId = c.getLong(2),
            subject = c.str(3),
            fromName = c.str(4),
            fromEmail = c.str(5),
            date = c.getLong(6),
            seen = c.bool(7),
            flagged = c.bool(8),
            draft = c.bool(9),
            attachments = c.getInt(10) > 0,
            snippet = c.str(11),
            toList = c.str(12),
        )

        private fun messageOf(c: Cursor) = Message(
            id = c.getLong(0),
            accountId = c.getLong(1),
            folderId = c.getLong(2),
            uid = c.getLong(3),
            gmMsgId = c.longOrNull(4),
            messageId = c.strOrNull(5),
            inReplyTo = c.strOrNull(6),
            refs = c.strOrNull(7),
            subject = c.str(8),
            fromName = c.str(9),
            fromEmail = c.str(10),
            toList = c.str(11),
            ccList = c.str(12),
            bccList = c.str(13),
            replyTo = c.str(14),
            date = c.getLong(15),
            size = c.getLong(16),
            seen = c.bool(17),
            flagged = c.bool(18),
            answered = c.bool(19),
            draft = c.bool(20),
            modSeq = c.getLong(21),
            attachments = c.getInt(22),
            snippet = c.str(23),
            auth = c.getInt(24),
            unsubscribe = c.strOrNull(25),
            unsubscribePost = c.bool(26),
            hasBody = c.bool(27),
            notified = c.bool(28),
            hidden = c.bool(29),
        )

        private fun attachmentOf(c: Cursor) = Attachment(
            id = c.getLong(0),
            messageId = c.getLong(1),
            section = c.str(2),
            name = c.str(3),
            mime = c.str(4),
            size = c.getLong(5),
            encoding = c.str(6),
            cid = c.strOrNull(7),
            inline = c.bool(8),
        )
    }
}
