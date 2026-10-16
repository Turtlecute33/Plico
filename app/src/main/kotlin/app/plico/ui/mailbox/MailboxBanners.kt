package app.plico.ui.mailbox

import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.push.PushStatus

/** Why push may not deliver mail in time (DESIGN §8.2), most important first. */
internal enum class PushProblem { PAUSED, STOPPED, RESTRICTED, LATE }

/** A notice above the message list (DESIGN §13 banners). */
internal sealed interface MailboxBanner {
    data class SignIn(val account: Account) : MailboxBanner
    data class Tls(val account: Account) : MailboxBanner
    data class Setup(val account: Account) : MailboxBanner

    /** [count] sends of [account] failed (DESIGN §7.5); View opens its drafts. */
    data class Unsent(val account: Account, val count: Int) : MailboxBanner

    /** A Gmail note that does not stop sync (hidden Trash or Spam, IMAP folder size limit): the state text of an OK account. */
    data class Note(val account: Account) : MailboxBanner

    /** The mass-delete guard froze [folder] (DESIGN §7.2 step 6). */
    data class Guard(val folder: Folder) : MailboxBanner
    data class Push(val problem: PushProblem) : MailboxBanner

    /** One-time note: Gmail writes the sender's public IP address into sent mail (DESIGN §12). */
    data object GmailIp : MailboxBanner
}

/** Chooses the banners of the mailbox. Pure, unit-tested. */
internal object MailboxBanners {
    /**
     * The push problem to show, or null when push works (or there is no status yet). Notification
     * permission is not nagged about here; Settings → Push & battery shows it (DESIGN §8.2).
     */
    fun pushProblem(status: PushStatus?): PushProblem? = when {
        status == null -> null
        status.pausedByUser -> PushProblem.PAUSED
        status.stoppedBySystem -> PushProblem.STOPPED
        status.grants.backgroundRestricted -> PushProblem.RESTRICTED
        !status.grants.batteryUnrestricted && !status.grants.exactAlarms -> PushProblem.LATE
        else -> null
    }

    /**
     * Frozen folders that matter for a list: for a folder view [open] itself and, for a Gmail label view,
     * the account's mailboxes behind it (All Mail); for the other views ([open] = null) every frozen folder.
     */
    fun guarded(open: Folder?, folders: List<Folder>): List<Folder> = folders.filter { f ->
        f.guard && (open == null || f.id == open.id || (open.kind == FolderKind.LABEL && f.accountId == open.accountId))
    }

    /**
     * Banners for a list that shows mail of [shown] accounts and the frozen folders [guarded], given every
     * account in [all], the push [status], whether the Gmail IP note was seen and the failed sends per account
     * ([unsent]).
     */
    fun of(
        shown: List<Account>,
        all: List<Account>,
        guarded: List<Folder>,
        status: PushStatus?,
        gmailIpNoteShown: Boolean,
        unsent: Map<Long, Int> = emptyMap(),
    ): List<MailboxBanner> = buildList {
        for (account in shown) {
            when (account.state) {
                AccountState.SIGN_IN_NEEDED -> add(MailboxBanner.SignIn(account))
                AccountState.TLS_PROBLEM -> add(MailboxBanner.Tls(account))
                AccountState.SETUP_PROBLEM -> add(MailboxBanner.Setup(account))
                AccountState.OK -> Unit
            }
        }
        for (account in shown) unsent[account.id]?.takeIf { it > 0 }?.let { add(MailboxBanner.Unsent(account, it)) }
        guarded.forEach { add(MailboxBanner.Guard(it)) }
        if (all.isNotEmpty()) pushProblem(status)?.let { add(MailboxBanner.Push(it)) }
        shown.filter { it.state == AccountState.OK && !it.stateText.isNullOrBlank() }.forEach { add(MailboxBanner.Note(it)) }
        if (!gmailIpNoteShown && all.any { it.isGmail }) add(MailboxBanner.GmailIp)
    }
}
