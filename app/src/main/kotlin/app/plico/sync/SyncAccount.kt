package app.plico.sync

import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.push.policy.NotifyRule
import app.plico.push.policy.UidNextCheck

/**
 * The sync steps of one account on one connection, as the session loop runs them (DESIGN.md §7.1–§7.3).
 * Blocking; each step commits before it returns. The caller turns [claimNew] into notifications.
 */
internal class SyncAccount(val env: SyncEnv) {
    private val labels = if (env.gmail) GmailLabels(env) else null
    private val mailbox = MailboxSync(env, labels)
    private val gmail = labels?.let { GmailSync(env, mailbox, it) }
    private val folderList = FolderList(env)

    /** LIST and folder rows (step 1); Gmail fills the label views that are new. */
    fun refreshFolders() {
        val newViews = folderList.refresh()
        gmail?.fill(newViews)
    }

    /** §7.1 step 1: new mail of the IDLE mailbox (INBOX; Gmail All Mail behind INBOX). */
    fun fastPath(report: SyncReport) {
        if (gmail != null) gmail.fastPath(report) else inbox()?.let { mailbox.newMail(it, report) }
    }

    /** §7.1 step 4: flags and expunges of the IDLE mailbox. */
    fun idleMailboxFlags(fullFlags: Boolean, report: SyncReport) {
        idleMailbox()?.let { mailbox.flagsAndExpunge(it, fullFlags, report) }
    }

    /** Not Gmail: STATUS of the folders with notify = 1; a new UIDNEXT runs that folder's sync (push PB16). */
    fun notifySweep(report: SyncReport) {
        if (env.gmail) return
        val folders = synced().filter { it.notify && it.role != FolderRole.INBOX }
        if (folders.isEmpty()) return
        val statuses = folderList.statuses(folders)
        for (folder in folders) {
            val status = statuses[folder.id]
            if (status == null) {
                report.relist = true
            } else if (status.uidNext > folder.uidNext || status.uidValidity != folder.uidValidity) {
                // The STATUS value: a SELECT without UIDNEXT gives only an estimate (plan §5.5).
                mailbox.newMail(folder, report, statusUidNext = status.uidNext)
                    ?.let { mailbox.flagsAndExpunge(it, fullFlags = false, report) }
            }
        }
    }

    /** Gmail refresh wake: STATUS of All Mail, and its sync on a change. */
    fun checkAllMail(report: SyncReport) {
        gmail?.checkAllMail(report)
    }

    /**
     * The other synced mailboxes: a STATUS sweep, then the sync of the changed ones; servers other than Gmail also
     * report the quota. [fullFlags]: a flag check is due (SyncPlan.fullFlags); without it, a server without CONDSTORE
     * skips the mailboxes whose STATUS is unchanged (D8).
     */
    fun fullSweep(report: SyncReport, fullFlags: Boolean = true) {
        val idle = idleMailbox()
        val folders = synced().filter { it.id != idle?.id }
        val statuses = folderList.statuses(folders)
        for (folder in folders) {
            val status = statuses[folder.id]
            if (status == null) report.relist = true else mailbox.sync(folder, status, fullFlags, report)
        }
        if (!env.gmail) {
            env.client.quota()?.let { env.accounts.setQuota(env.accountId, it.usedKiB * KIB, it.limitKiB * KIB) }
        }
    }

    /**
     * New mail of the mailboxes that got messages through a move without COPYUID (DESIGN §7.4), on this
     * connection: the rows are stored as notified before the next connection forgets the moved Message-IDs (also
     * in POLL mode), and they show at once. A mailbox without its first sync stores its rows as notified anyway.
     * Returns false when there was nothing to do.
     */
    fun movedTargets(report: SyncReport): Boolean {
        val paths = env.movedIn.paths()
        for (path in paths) {
            val folder = env.folders.byPath(env.accountId, FolderKind.MAILBOX, path)
            if (folder != null && folder.backfillUid != 0L) mailbox.newMail(folder, report)
            env.movedIn.forget(path)
        }
        return paths.isNotEmpty()
    }

    /** A folder opened in the UI; a Gmail label view syncs All Mail. */
    fun syncFolder(folder: Folder, report: SyncReport) {
        val target = if (folder.kind == FolderKind.LABEL) gmail?.allMail() else folder
        target?.let { mailbox.sync(it, status = null, fullFlags = true, report) }
    }

    /** One backfill step for the first mailbox that is not complete (step 8). */
    fun backfillStep(report: SyncReport): Boolean {
        val folder = synced().filter { it.backfillUid > 1 }.minByOrNull(::backfillOrder) ?: return false
        return mailbox.backfillStep(folder, report)
    }

    /**
     * The UIDNEXT check before IDLE (§7.1 step 6). True when the fast path ran again. A step of this cycle may
     * have SELECTed INBOX again without a new-UID fetch, so a SELECT that is still current is compared too:
     * IDLE never reports mail that this SELECT already counted. EXISTS reports the mail that comes after it.
     */
    fun missedMail(report: SyncReport): Boolean {
        if (gmail != null) return gmail.missedMail(report)
        val inbox = inbox() ?: return false
        val sel = env.selection.current(inbox.path) ?: env.selection.select(inbox.path)
        if (!UidNextCheck.missedMail(sel.uidNext, inbox.uidNext)) return false
        mailbox.newMail(inbox, report, sel)
        return true
    }

    /** SELECTs INBOX for IDLE unless it is selected. */
    fun prepareIdle() {
        env.selection.ensure(FolderRules.INBOX)
    }

    /**
     * New mail to notify (DESIGN.md §9): the Gmail category filter first, then every row with notified = 0 is
     * claimed; the ones that pass the rule are returned.
     */
    fun claimNew(): List<Long> {
        gmail?.filterCategories()
        return env.messages.claimNotifications(env.accountId, env.clock() - NotifyRule.MAX_AGE_MS).map { it.id }
    }

    private fun inbox(): Folder? = env.folders.byRole(env.accountId, FolderRole.INBOX, FolderKind.MAILBOX)

    /** The synced mailbox behind IDLE: INBOX, Gmail All Mail. */
    fun idleMailbox(): Folder? = if (gmail != null) gmail.allMail() else inbox()

    private fun synced(): List<Folder> =
        env.folders.byAccount(env.accountId).filter { it.kind == FolderKind.MAILBOX && !it.hidden && !it.guard }

    private fun backfillOrder(folder: Folder): Int = when (folder.role) {
        FolderRole.INBOX, FolderRole.ALL -> 0
        FolderRole.TRASH, FolderRole.JUNK -> 3
        null -> 2
        else -> 1
    }

    private companion object {
        const val KIB = 1024L
    }
}
