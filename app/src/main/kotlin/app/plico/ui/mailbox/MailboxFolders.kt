package app.plico.ui.mailbox

import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MailView
import app.plico.sync.FolderRules
import java.text.Collator

/** One folder entry of the drawer with its unread count; [role] is the part it plays (a Riseup archive may have none). */
internal data class DrawerFolder(val folder: Folder, val unread: Int, val role: FolderRole? = folder.role)

/**
 * The drawer part of one account (DESIGN §13): the fixed folders, then every other one under "More".
 * [localDrafts] counts the local drafts of an account without a Drafts mailbox: they get their own entry.
 */
internal data class DrawerSection(
    val account: Account,
    val main: List<DrawerFolder>,
    val more: List<DrawerFolder>,
    val localDrafts: Int = 0,
)

/** Which folder plays which part in the drawer and the move/label pickers. Pure, unit-tested. */
internal object MailboxFolders {
    private val RISEUP_MAIN = listOf(
        FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS, FolderRole.ARCHIVE, FolderRole.JUNK, FolderRole.TRASH,
    )
    private val GMAIL_MAIN = listOf(
        FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS, FolderRole.ALL, FolderRole.IMPORTANT,
        FolderRole.JUNK, FolderRole.TRASH,
    )

    private val ROLE_ORDER = listOf(
        FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS, FolderRole.ARCHIVE, FolderRole.ALL, FolderRole.IMPORTANT,
        FolderRole.JUNK, FolderRole.TRASH,
    )

    // Gmail keeps these as label views (kind 1) and the others as mailboxes (DESIGN §7.3).
    private val GMAIL_LABEL_ROLES = setOf(FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS, FolderRole.IMPORTANT)

    // INBOX always notifies; the others never hold new mail worth an alert.
    private val NO_NOTIFY = setOf(FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS, FolderRole.TRASH, FolderRole.JUNK)

    /** The visible folder with [role] of [account]; with duplicates the oldest of the expected kind. */
    fun byRole(account: Account, folders: List<Folder>, role: FolderRole): Folder? {
        val kind = if (account.isGmail && role in GMAIL_LABEL_ROLES) FolderKind.LABEL else FolderKind.MAILBOX
        return folders
            .filter { it.accountId == account.id && it.role == role && !it.hidden }
            .minWithOrNull(compareBy({ it.kind != kind }, { it.id }))
    }

    /** The fixed folders of [account] in drawer order with the role each plays; missing ones are left out. */
    fun mainFolders(account: Account, folders: List<Folder>): List<Pair<FolderRole, Folder>> =
        (if (account.isGmail) GMAIL_MAIN else RISEUP_MAIN).mapNotNull { role ->
            val folder = if (role == FolderRole.ARCHIVE) FolderRules.archiveOf(account, folders) else byRole(account, folders, role)
            folder?.let { role to it }
        }

    /**
     * One section per account, in account order, with unread counts from [unread] (folder id to count) and the
     * local drafts per account ([localDrafts]) of accounts without a Drafts mailbox.
     */
    fun sections(
        accounts: List<Account>,
        folders: List<Folder>,
        unread: Map<Long, Int>,
        localDrafts: Map<Long, Int> = emptyMap(),
        collator: Collator = Collator.getInstance(),
    ): List<DrawerSection> = accounts.map { account ->
        val main = mainFolders(account, folders)
        val mainIds = main.mapTo(HashSet()) { it.second.id }
        // Duplicates of fixed roles stay out; a \Archive mailbox that is not the chosen archive stays in.
        val more = folders
            .filter {
                it.accountId == account.id && !it.hidden && it.id !in mainIds &&
                    (it.role == null || it.role == FolderRole.ARCHIVE)
            }
            .sortedWith(byPath(collator))
        DrawerSection(
            account = account,
            main = main.map { (role, folder) -> DrawerFolder(folder, unread[folder.id] ?: 0, role) },
            more = more.map { DrawerFolder(it, unread[it.id] ?: 0) },
            localDrafts = if (main.any { it.first == FolderRole.DRAFTS }) 0 else localDrafts[account.id] ?: 0,
        )
    }

    /**
     * True while the first sync of what [view] shows has not finished (`backfill_uid = 0`, or no main mailbox
     * yet: Riseup INBOX, Gmail All Mail). An empty list then says "Loading mail", not "Your inbox is empty".
     * Accounts with a problem have a banner instead, so the list never waits for them.
     */
    fun firstSyncPending(view: MailView, accounts: List<Account>, folders: List<Folder>): Boolean {
        fun waits(account: Account) = account.state == AccountState.OK &&
            byRole(account, folders, if (account.isGmail) FolderRole.ALL else FolderRole.INBOX)
                .let { it == null || it.backfillUid == 0L }
        return when (view) {
            MailView.Unified -> accounts.any(::waits)
            is MailView.Starred -> accounts.any { (view.accountId == null || it.id == view.accountId) && waits(it) }
            is MailView.InFolder -> {
                val folder = folders.firstOrNull { it.id == view.folderId }
                val account = accounts.firstOrNull { it.id == folder?.accountId }
                when {
                    folder == null || account == null -> false
                    folder.kind == FolderKind.MAILBOX -> account.state == AccountState.OK && folder.backfillUid == 0L
                    else -> waits(account)
                }
            }
            is MailView.Search -> false
        }
    }

    /**
     * Whether the user may turn on "Notify for new mail" for [folder] (DESIGN §9 notify scope): not for
     * INBOX (always on), Sent, Drafts, Trash, Spam; on Gmail only label views.
     */
    fun canNotify(folder: Folder, gmail: Boolean): Boolean =
        folder.role !in NO_NOTIFY && (!gmail || folder.kind == FolderKind.LABEL)

    /**
     * Where messages of [account] can move to: Riseup mailboxes except Drafts; Gmail Inbox and the user's
     * labels. Folders in [exclude] (the current one) are left out. Inbox first, then by name.
     */
    fun moveTargets(
        account: Account,
        folders: List<Folder>,
        exclude: Set<Long> = emptySet(),
        collator: Collator = Collator.getInstance(),
    ): List<Folder> {
        val targets = if (account.isGmail) {
            labelTargets(account, folders, collator)
        } else {
            folders.filter {
                it.accountId == account.id && it.kind == FolderKind.MAILBOX && !it.hidden && it.role != FolderRole.DRAFTS
            }.sortedWith(byRoleThenPath(collator))
        }
        return targets.filter { it.id !in exclude }
    }

    /** The Gmail labels a message can carry in the label picker: Inbox and the user's labels. */
    fun labelTargets(account: Account, folders: List<Folder>, collator: Collator = Collator.getInstance()): List<Folder> {
        if (!account.isGmail) return emptyList()
        return folders.filter {
            it.accountId == account.id && it.kind == FolderKind.LABEL && !it.hidden &&
                (it.role == null || it.role == FolderRole.INBOX)
        }.sortedWith(byRoleThenPath(collator))
    }

    /** A folder path for display: the hierarchy delimiter becomes " / ". */
    fun displayPath(folder: Folder): String {
        val delimiter = folder.delimiter
        if (delimiter.isNullOrEmpty() || delimiter !in folder.path) return folder.path
        return folder.path.split(delimiter).filter(String::isNotEmpty).joinToString(" / ")
    }

    private fun byPath(collator: Collator): Comparator<Folder> =
        compareBy<Folder, String>(collator) { displayPath(it) }.thenBy { it.path }

    private fun byRoleThenPath(collator: Collator): Comparator<Folder> =
        compareBy<Folder> { folder -> ROLE_ORDER.indexOf(folder.role).let { if (it < 0) Int.MAX_VALUE else it } }
            .then(byPath(collator))
}
