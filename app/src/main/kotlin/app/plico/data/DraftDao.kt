package app.plico.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.plico.mail.mime.MessageBuilder
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local drafts; the table is also the outbox (DESIGN.md §7.5, §7.6). State changes are compare-and-set,
 * so an undo and the sender cannot both win.
 */
class DraftDao(private val db: Db) {
    fun get(id: Long): Draft? = db.sql.draft(id)
    fun observe(id: Long): Flow<Draft?> = db.observe(Tables.DRAFT) { draft(id) }

    /** Drafts of one account, last edited first. */
    fun observeByAccount(accountId: Long): Flow<List<Draft>> = db.observe(Tables.DRAFT) {
        list("SELECT $COLS FROM draft WHERE account_id = ? ORDER BY updated DESC, id DESC", accountId, row = ::draftOf)
    }

    /** Drafts of every account, last edited first. */
    fun observeAll(): Flow<List<Draft>> = db.observe(Tables.DRAFT) {
        list("SELECT $COLS FROM draft ORDER BY updated DESC, id DESC", row = ::draftOf)
    }

    fun byMessageId(accountId: Long, messageId: String): Draft? = db.sql.one(
        "SELECT $COLS FROM draft WHERE account_id = ? AND message_id = ?",
        accountId, messageId, row = ::draftOf,
    )

    /**
     * Creates a draft in state editing and returns its id. It gets a new Message-ID
     * (`<UUID@from-domain>`) that every revision and the sent message keep, unless [NewDraft.messageId]
     * is set (a server draft opened for editing).
     */
    fun create(draft: NewDraft): Long = db.tx(Tables.DRAFT) {
        insertRow(
            """
            INSERT INTO draft(account_id, message_id, from_email, to_list, cc_list, bcc_list, subject, body,
              in_reply_to, refs, source_message, updated)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            draft.accountId,
            draft.messageId ?: MessageBuilder.newMessageId(draft.fromEmail.substringAfterLast('@')),
            draft.fromEmail, draft.toList, draft.ccList, draft.bccList, draft.subject, draft.body,
            draft.inReplyTo, draft.refs, draft.sourceMessage, System.currentTimeMillis(),
        )
    }

    /**
     * Saves the editable content of [draft]: account, From, recipients, subject, body, reply references
     * and attachments. Returns false (nothing saved) when the draft is gone, sending or sent.
     */
    fun update(draft: Draft): Boolean = db.tx(Tables.DRAFT) {
        write(
            """
            UPDATE draft SET account_id = ?, from_email = ?, to_list = ?, cc_list = ?, bcc_list = ?, subject = ?,
              body = ?, in_reply_to = ?, refs = ?, source_message = ?, attachments = ?, updated = ?
            WHERE id = ? AND state NOT IN (${DraftState.SENDING}, ${DraftState.SENT})
            """,
            draft.accountId, draft.fromEmail, draft.toList, draft.ccList, draft.bccList, draft.subject,
            draft.body, draft.inReplyTo, draft.refs, draft.sourceMessage, encodeAttachments(draft.attachments),
            System.currentTimeMillis(), draft.id,
        ) > 0
    }

    /** Records whether a revision is APPENDed to the server's drafts mailbox. */
    fun setOnServer(id: Long, onServer: Boolean): Unit = db.tx(Tables.DRAFT) {
        write("UPDATE draft SET on_server = ? WHERE id = ?", onServer, id)
    }

    /** Send: editing or failed -> queued, sent at [sendAt] (now + undo seconds). */
    fun queue(id: Long, sendAt: Long): Boolean = transition(
        id, listOf(DraftState.EDITING, DraftState.FAILED), DraftState.QUEUED,
        "send_at = ?, attempts = 0, error = NULL", sendAt,
    )

    /** Undo send: queued -> editing. False when sending has started. */
    fun unqueue(id: Long): Boolean = transition(id, listOf(DraftState.QUEUED), DraftState.EDITING)

    /** Claims a due send: queued -> sending. False when the user undid it. */
    fun markSending(id: Long): Boolean = transition(id, listOf(DraftState.QUEUED), DraftState.SENDING)

    /** Transient error: sending -> queued, tried again at [sendAt]. */
    fun retryLater(id: Long, attempts: Int, sendAt: Long, error: String?): Boolean = transition(
        id, listOf(DraftState.SENDING), DraftState.QUEUED,
        "attempts = ?, send_at = ?, error = ?", attempts, sendAt, error,
    )

    /** Queued or sending -> failed, with the reason the Drafts view shows. */
    fun markFailed(id: Long, error: String): Boolean = transition(
        id, listOf(DraftState.QUEUED, DraftState.SENDING), DraftState.FAILED, "error = ?", error,
    )

    /** Sending -> sent: the server accepted it; follow-up ops are pending. */
    fun markSent(id: Long): Boolean = transition(id, listOf(DraftState.SENDING), DraftState.SENT, "error = NULL")

    /**
     * Start-up rule (DESIGN.md §7.5): a draft still in state sending was interrupted and may have been sent,
     * so it becomes failed with [error] (R.string.data_error_may_have_been_sent). Call once per process
     * before the first send. Returns the number of drafts changed.
     */
    fun failInterrupted(error: String): Int = db.tx(Tables.DRAFT) {
        write("UPDATE draft SET state = ?, error = ? WHERE state = ?", DraftState.FAILED, error, DraftState.SENDING)
    }

    /** Queued drafts of the account with send_at <= [now], oldest first. */
    fun dueSends(accountId: Long, now: Long): List<Draft> = db.sql.list(
        "SELECT $COLS FROM draft WHERE account_id = ? AND state = ? AND send_at <= ? ORDER BY send_at, id",
        accountId, DraftState.QUEUED, now, row = ::draftOf,
    )

    /** The earliest send_at of the account's queued drafts; null when none is queued. */
    fun nextSendAt(accountId: Long): Long? = db.sql.long(
        "SELECT min(send_at) FROM draft WHERE account_id = ? AND state = ?",
        accountId, DraftState.QUEUED,
    )

    /** Deletes the row. Its outbox dir is the caller's job ([Files.deleteOutbox]). */
    fun delete(id: Long): Unit = db.tx(Tables.DRAFT) {
        write("DELETE FROM draft WHERE id = ?", id)
    }

    /** Sets state [to] (and the [set] assignments) only when the state is one of [from]. */
    private fun transition(id: Long, from: List<Int>, to: Int, set: String = "", vararg args: Any?): Boolean =
        db.tx(Tables.DRAFT) {
            val extra = if (set.isEmpty()) "" else ", $set"
            write("UPDATE draft SET state = ?$extra WHERE id = ? AND state IN (${from.joinToString(",")})", to, *args, id) > 0
        }

    private fun SQLiteDatabase.draft(id: Long) = one("SELECT $COLS FROM draft WHERE id = ?", id, row = ::draftOf)

    internal companion object {
        private const val COLS = "id, account_id, message_id, from_email, to_list, cc_list, bcc_list, subject, body, " +
            "in_reply_to, refs, source_message, attachments, on_server, state, send_at, attempts, error, updated"

        /** `draft.attachments` column format: JSON `[{file,name,mime,size}]`. */
        fun encodeAttachments(list: List<DraftAttachment>): String = JSONArray(
            list.map { JSONObject().put("file", it.file).put("name", it.name).put("mime", it.mime).put("size", it.size) },
        ).toString()

        fun decodeAttachments(text: String): List<DraftAttachment> {
            val array = JSONArray(text)
            return List(array.length()) { i ->
                val o = array.getJSONObject(i)
                DraftAttachment(o.getString("file"), o.getString("name"), o.getString("mime"), o.getLong("size"))
            }
        }

        private fun draftOf(c: Cursor) = Draft(
            id = c.getLong(0),
            accountId = c.getLong(1),
            messageId = c.str(2),
            fromEmail = c.str(3),
            toList = c.str(4),
            ccList = c.str(5),
            bccList = c.str(6),
            subject = c.str(7),
            body = c.str(8),
            inReplyTo = c.strOrNull(9),
            refs = c.strOrNull(10),
            sourceMessage = c.longOrNull(11),
            attachments = decodeAttachments(c.str(12)),
            onServer = c.bool(13),
            state = c.getInt(14),
            sendAt = c.getLong(15),
            attempts = c.getInt(16),
            error = c.strOrNull(17),
            updated = c.getLong(18),
        )
    }
}
