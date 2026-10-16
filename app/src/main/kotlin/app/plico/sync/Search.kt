package app.plico.sync

import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.NewMessage
import app.plico.mail.imap.Command
import app.plico.mail.imap.UidSet

/**
 * Server search (DESIGN.md §7.7): Gmail `X-GM-RAW` in All Mail, Riseup `UID SEARCH TEXT` in the open folder
 * (INBOX for the unified view). `CHARSET UTF-8` is added for non-ASCII queries (protocol PS16). Hits that are
 * not stored yet are fetched as header rows (`notified = 1`, so they never notify) with their bodies, so that
 * the local search finds them.
 */
object Search {
    /** At most this many unknown hits are fetched per search, newest first. */
    private const val MAX_NEW = 200

    /** Bodies of new hits fetched per search (the local index needs the text). */
    private const val MAX_BODIES = 25

    /** Server search (DESIGN §7.7); inserts missing hits as header rows; returns local message ids. */
    suspend fun server(ctx: SyncContext, query: String, folder: Folder?): List<Long> =
        server(SessionOpContext(ctx), query, folder)

    internal fun server(ctx: OpContext, query: String, folder: Folder?): List<Long> {
        val store = ctx.store
        val account = ctx.account
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val mailbox = if (account.isGmail) {
            store.mailbox(account.id, FolderRole.ALL)
        } else {
            folder?.takeIf { it.accountId == account.id && it.kind == FolderKind.MAILBOX }
                ?: store.mailbox(account.id, FolderRole.INBOX)
        } ?: return emptyList()
        Ops.selectChecked(ctx, mailbox)
        val criteria = Command.build {
            raw(if (account.isGmail) "X-GM-RAW" else "TEXT")
            string(text)
        }
        val hits = ctx.client.uidSearch(criteria).toList().sortedDescending()
        if (hits.isEmpty()) return emptyList()
        val known = store.messages.idsByUid(mailbox.id, hits)
        val missing = hits.filter { it !in known }.take(MAX_NEW)
        val added = if (missing.isEmpty()) emptyMap() else fetchHeaders(ctx, mailbox, missing)
        Bodies.fetch(ctx, added.values.take(MAX_BODIES))
        val ids = known + added
        return hits.mapNotNull { ids[it] }
    }

    /** Fetches and stores header rows for [uids] of the selected [mailbox] (HeaderParser of the sync); uid -> id. */
    private fun fetchHeaders(ctx: OpContext, mailbox: Folder, uids: List<Long>): Map<Long, Long> {
        val store = ctx.store
        val gmail = ctx.account.isGmail
        val items = HeaderParser.items(modSeq = "CONDSTORE" in ctx.client.caps, gmMsgId = gmail, gmLabels = gmail)
        val wanted = uids.toSet()
        val rows = LinkedHashMap<Long, NewMessage>()
        ctx.client.uidFetch(UidSet.of(uids), items) { item ->
            // Unsolicited FETCH responses carry no header section.
            if (item.uid !in wanted || item.sections.keys.none { it.startsWith("HEADER", ignoreCase = true) }) return@uidFetch
            val labels = if (gmail) Ops.labelIds(store, ctx.account.id, item.gmLabels.orEmpty()) else null
            rows[item.uid] = HeaderParser.newMessage(item, ctx.account.id, mailbox.id, gmail, labels, notified = true, now = store.now())
        }
        if (rows.isEmpty()) return emptyMap()
        return rows.keys.zip(store.messages.upsert(rows.values.toList())).toMap()
    }
}
