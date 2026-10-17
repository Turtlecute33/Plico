package app.plico.sync

import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.NewMessage
import app.plico.mail.imap.Command
import app.plico.mail.imap.UidSet
import app.plico.push.policy.NotifyRule

/**
 * Server search (DESIGN.md §7.7): Gmail `X-GM-RAW` in All Mail, other servers `UID SEARCH TEXT` in the open folder
 * (INBOX for the unified view); without ESEARCH in UID windows from the newest down. `CHARSET UTF-8` is added for
 * non-ASCII queries (protocol PS16). Hits that are not stored yet are fetched as header rows with their bodies, so
 * that the local search finds them. They do not notify (`notified = 1`), except new mail above the folder's UIDNEXT,
 * which the notification rule decides.
 */
object Search {
    /** At most this many unknown hits are fetched per search, newest first. */
    private const val MAX_NEW = 200

    /** Bodies of new hits fetched per search (the local index needs the text). */
    private const val MAX_BODIES = 25

    /** Server search (DESIGN §7.7); inserts missing hits as header rows; returns local message ids, newest first. */
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
        val sel = Ops.selectChecked(ctx, mailbox)
        val hits = if (account.isGmail || "ESEARCH" in ctx.client.caps) {
            val criteria = Command.build {
                raw(if (account.isGmail) "X-GM-RAW" else "TEXT")
                string(text)
            }
            ctx.client.uidSearch(criteria).toList().sortedDescending()
        } else {
            windowHits(ctx, mailbox, sel.uidNext, text)
        }
        if (hits.isEmpty()) return emptyList()
        val known = store.messages.idsByUid(mailbox.id, hits)
        val missing = hits.filter { it !in known }.take(MAX_NEW)
        val added = if (missing.isEmpty()) emptyMap() else fetchHeaders(ctx, mailbox, missing)
        Bodies.fetch(ctx, added.values.take(MAX_BODIES))
        val ids = known + added
        return hits.mapNotNull { ids[it] }
    }

    /**
     * `UID SEARCH UID a:b TEXT` in windows of 50,000 UIDs from [uidNext] down (no ESEARCH: each `* SEARCH` line lists
     * every hit). The first window ends at `*`, so that mail newer than a SELECT of earlier in the session is
     * included. Stops when [MAX_NEW] hits are not stored yet, or at UID 1. Newest first.
     */
    private fun windowHits(ctx: OpContext, mailbox: Folder, uidNext: Long, text: String): List<Long> {
        val hits = ArrayList<Long>()
        val seen = HashSet<Long>()
        var unknown = 0
        for ((i, window) in MailboxMath.searchWindows(maxOf(uidNext, 2)).withIndex()) {
            val criteria = Command.build {
                raw("UID")
                raw(if (i == 0) "${window.first}:*" else "${window.first}:${window.last}")
                raw("TEXT")
                string(text)
            }
            // `n:*` also matches the last message when n is above it, which a lower window can find again.
            val found = ctx.client.uidSearch(criteria).toList().filter(seen::add).sortedDescending()
            hits += found
            unknown += found.size - ctx.store.messages.idsByUid(mailbox.id, found).size
            if (unknown >= MAX_NEW) break
            ctx.progress()
        }
        return hits
    }

    /** Fetches and stores header rows for [uids] of the selected [mailbox] (HeaderParser of the sync); uid -> id. */
    private fun fetchHeaders(ctx: OpContext, mailbox: Folder, uids: List<Long>): Map<Long, Long> {
        val store = ctx.store
        val gmail = ctx.account.isGmail
        val items = HeaderParser.items(modSeq = "CONDSTORE" in ctx.client.caps, gmMsgId = gmail, gmLabels = gmail)
        val wanted = uids.toSet()
        val rows = LinkedHashMap<Long, NewMessage>()
        ctx.client.uidFetch(UidSet.of(uids), items) { item ->
            if (item.uid !in wanted || !HeaderParser.hasHeaderFields(item)) return@uidFetch
            val labels = if (gmail) Ops.labelIds(store, ctx.account.id, item.gmLabels.orEmpty()) else null
            // A hit at or above UIDNEXT is new mail that the new-UID fetch has not reached: the rule of §9 decides.
            val notified = NotifyRule.insertNotified(fromNewUidFetch = item.uid >= mailbox.uidNext, uidNextBefore = mailbox.uidNext)
            rows[item.uid] = HeaderParser.newMessage(item, ctx.account.id, mailbox.id, gmail, labels, notified, store.now())
        }
        if (rows.isEmpty()) return emptyMap()
        return rows.keys.zip(store.messages.upsert(rows.values.toList())).toMap()
    }
}
