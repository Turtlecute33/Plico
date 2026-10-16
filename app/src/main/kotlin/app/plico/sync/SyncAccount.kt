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

    /** §7.1 step 1: new mail of the IDLE mailbox (Riseup INBOX; Gmail All Mail behind INBOX). */
    fun fastPath(report: SyncReport) {
        if (gmail != null) gmail.fastPath(report) else inbox()?.let { mailbox.newMail(it, report) }
    }

    /** §7.1 step 4: flags and expunges of the IDLE mailbox. */
    fun idleMailboxFlags(fullFlags: Boolean, report: SyncReport) {
        idleMailbox()?.let { mailbox.flagsAndExpunge(it, fullFlags, report) }
    }

    /** Riseup: STATUS of the folders with notify = 1; a new UIDNEXT runs that folder's sync (push PB16). */
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
                mailbox.newMail(folder, report)?.let { mailbox.flagsAndExpunge(it, fullFlags = false, report) }
            }
        }
    }

    /** Gmail refresh wake: STATUS of All Mail, and its sync on a change. */
    fun checkAllMail(report: SyncReport) {
        gmail?.checkAllMail(report)
    }

    /** The other synced mailboxes: a STATUS sweep, then the sync of the changed ones; Riseup also reads the quota. */
    fun fullSweep(report: SyncReport) {
        val idle = idleMailbox()
        val folders = synced().filter { it.id != idle?.id }
        val statuses = folderList.statuses(folders)
        for (folder in folders) {
            val status = statuses[folder.id]
            if (status == null) report.relist = true else mailbox.sync(folder, status, fullFlags = true, report)
        }
        if (!env.gmail) {
            env.client.quota()?.let { env.accounts.setQuota(env.accountId, it.usedKiB * KIB, it.limitKiB * KIB) }
        }
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

    /** The UIDNEXT check before IDLE (§7.1 step 6). True when the fast path ran again. */
    fun missedMail(report: SyncReport): Boolean {
        if (gmail != null) return gmail.missedMail(report)
        val inbox = inbox() ?: return false
        if (env.selection.current(inbox.path) != null) return false
        val sel = env.selection.select(inbox.path)
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

    /** The synced mailbox behind IDLE: Riseup INBOX, Gmail All Mail. */
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
