package app.plico.sync

import app.plico.R
import app.plico.data.AccountState
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.mail.imap.Command
import app.plico.mail.imap.UidSet
import app.plico.push.policy.KeyValueStore
import app.plico.push.policy.NotifyRule
import app.plico.push.policy.UidNextCheck

/**
 * Gmail on top of [MailboxSync] (DESIGN.md §7.3): All Mail is the synced source, labels are views, IDLE watches
 * INBOX. Never APPENDs to Sent (the server keeps its own copy) and never expunges in All Mail.
 */
internal class GmailSync(
    private val env: SyncEnv,
    private val mailbox: MailboxSync,
    private val labels: GmailLabels,
) {
    fun allMail(): Folder? = env.folders.byRole(env.accountId, FolderRole.ALL, FolderKind.MAILBOX)

    /** Fast path on an INBOX change: the new UIDs of All Mail (step 4), then the one-time size-limit note. */
    fun fastPath(report: SyncReport) {
        val all = allMail() ?: return
        mailbox.newMail(all, report) ?: return
        sizeNote(all)
    }

    /** Refresh wake (push PB16): STATUS of All Mail; a change runs its incremental sync. */
    fun checkAllMail(report: SyncReport) {
        val all = allMail()?.takeIf { !it.guard } ?: return
        mailbox.sync(all, env.client.status(all.path), fullFlags = false, report)
    }

    /**
     * The category filter (DESIGN.md §9): one `UID SEARCH UID <new> X-GM-RAW "category:a OR category:b"` over the
     * notification candidates in All Mail; the ones that fail are marked notified before the claim.
     */
    fun filterCategories() {
        val enabled = CATEGORIES.filter { it in env.accounts.get(env.accountId)?.gmailCategories.orEmpty() }
        if (enabled.size == CATEGORIES.size) return
        val all = allMail() ?: return
        val since = env.clock() - NotifyRule.MAX_AGE_MS
        val candidates = env.messages.notifyCandidates(env.accountId, since).filter { it.folderId == all.id }
        if (candidates.isEmpty()) return
        val passing = if (enabled.isEmpty()) {
            UidSet.EMPTY
        } else {
            env.selection.ensure(all.path)
            env.client.uidSearch(
                Command.build {
                    raw("UID")
                    raw(UidSet.of(candidates.map { it.uid }).toString())
                    raw("X-GM-RAW")
                    string(categoryQuery(enabled))
                },
            )
        }
        env.messages.markNotified(candidates.filter { it.uid !in passing }.map { it.id })
    }

    /**
     * The UIDNEXT check before IDLE (push PB2, protocol PS3). When INBOX is still selected, the server reports
     * new mail itself. Else SELECT INBOX: a UIDNEXT above the last one plus this cycle's new inbox mail means
     * mail came after the All Mail fetch, so the fast path runs again, and the check repeats (at most
     * [MAX_RERUNS] times). Only a SELECT that passed the check is stored, because IDLE never reports mail that
     * was already there at the SELECT. Returns true when the fast path ran; the loop then runs one more cycle,
     * which checks again: the publish SELECTs All Mail for the category filter, and after [MAX_RERUNS] the
     * SELECT that failed the check is still current.
     */
    fun missedMail(report: SyncReport): Boolean {
        if (env.selection.current(FolderRules.INBOX) != null) return false
        val view = env.folders.byRole(env.accountId, FolderRole.INBOX, FolderKind.LABEL) ?: return false
        var expected = UidNextCheck.gmailExpected(view.uidNext, report.newInbox)
        for (pass in 0..MAX_RERUNS) {
            val uidNext = env.selection.select(FolderRules.INBOX).uidNext
            if (!UidNextCheck.missedMail(uidNext, expected)) {
                env.folders.setSyncState(view.id, uidNext = uidNext)
                if (pass == 0) return false
                break
            }
            // Mail still comes in: the old value stays, so the next cycle checks again.
            if (pass == MAX_RERUNS) break
            val before = report.newInbox
            fastPath(report)
            val fetched = report.newInbox - before
            // The fetched inbox mail counts on top. Nothing fetched: the rise was no new mail (a label added on the
            // web gives INBOX a new UID, but All Mail none), so this SELECT is the new base.
            expected = if (fetched > 0) expected + fetched else uidNext
        }
        env.onNewMail()
        return true
    }

    /**
     * One-time fill of label views that LIST shows for the first time (protocol PS6): EXAMINE the label,
     * `UID FETCH 1:* (X-GM-MSGID)`, and add the local rows. Skipped before All Mail has synced at all.
     */
    fun fill(views: List<Folder>) {
        if (views.isEmpty() || (allMail()?.backfillUid ?: 0L) == 0L) return
        val protected = env.protectedIds()
        for (view in views) {
            if (env.selection.examine(view.path).exists == 0L) continue
            val msgIds = ArrayList<Long>()
            env.client.uidFetch(UidSet.range(1), "X-GM-MSGID") { item -> item.gmMsgId?.let(msgIds::add) }
            val ids = env.messages.idsByGmMsgId(env.accountId, msgIds).values.filterNot { it in protected }
            env.messages.addToFolder(ids, view.id)
            env.progress()
        }
    }

    /** All Mail EXISTS at one of Gmail's IMAP folder size limits: one note (protocol PS13). */
    private fun sizeNote(all: Folder) {
        val exists = env.selection.current(all.path)?.exists ?: return
        if (exists !in SIZE_LIMITS) return
        val key = SIZE_NOTE_KEY + env.accountId
        if (env.notes.get(key) == exists.toString()) return
        env.notes.put(key, exists.toString())
        if (env.accounts.get(env.accountId)?.state == AccountState.OK) {
            env.accounts.setState(env.accountId, AccountState.SETUP_PROBLEM, env.text(R.string.sync_gmail_size_limit))
        }
    }

    companion object {
        /** Deletes the notes of a removed account. */
        fun forget(notes: KeyValueStore, accountId: Long) = notes.put(SIZE_NOTE_KEY + accountId, null)

        /** Gmail inbox categories in `account.gmail_categories`. */
        val CATEGORIES = listOf("primary", "social", "promotions", "updates", "forums")

        /** Fast-path runs of one UIDNEXT check while mail keeps coming. */
        private const val MAX_RERUNS = 2

        private val SIZE_LIMITS = setOf(1_000L, 2_000L, 5_000L, 10_000L)
        private const val SIZE_NOTE_KEY = "sync.gmail_size_note."

        /** `category:primary OR category:updates`. */
        fun categoryQuery(enabled: List<String>): String = enabled.joinToString(" OR ") { "category:$it" }
    }
}

/**
 * Maps X-GM-LABELS values to label-view ids. A label without a view row gets a hidden one at once, with the
 * membership, and the folder list runs again (protocol PS6).
 */
internal class GmailLabels(private val env: SyncEnv) {
    private val ids = HashMap<String, Long>()

    fun ids(labels: List<String>, report: SyncReport): Set<Long> = labels.mapNotNullTo(HashSet()) { id(it, report) }

    fun inboxId(report: SyncReport): Long? = id("\\Inbox", report)

    private fun id(label: String, report: SyncReport): Long? {
        val path = FolderRules.viewPath(label) ?: return null
        return ids.getOrPut(path) {
            env.folders.byPath(env.accountId, FolderKind.LABEL, path)?.id
                ?: env.folders.ensureLabel(env.accountId, path, FolderRules.leaf(path, "/")).also { report.relist = true }
        }
    }
}
