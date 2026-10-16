package app.plico.sync

import app.plico.data.Account
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.NewFolder
import app.plico.mail.imap.MailboxInfo

/**
 * Folder rows from a LIST (DESIGN.md §7.2 step 1, §7.3; protocol PS5, PS6, PS13, simplicity EM5, EM23). Pure.
 */
internal object FolderRules {
    const val INBOX = "INBOX"

    /** Gmail label views of system labels, by their `X-GM-LABELS` value. */
    val SYSTEM_VIEWS = linkedMapOf(
        "\\Inbox" to FolderRole.INBOX,
        "\\Sent" to FolderRole.SENT,
        "\\Draft" to FolderRole.DRAFTS,
        "\\Important" to FolderRole.IMPORTANT,
    )

    /** The Gmail mailboxes (kind 0) whose absence the user must fix in Gmail's "Show in IMAP" settings. */
    val GMAIL_REQUIRED = setOf(FolderRole.ALL, FolderRole.TRASH, FolderRole.JUNK)

    private const val NOSELECT = "\\noselect"

    private val ATTRIBUTE_ROLES = mapOf(
        "\\sent" to FolderRole.SENT,
        "\\drafts" to FolderRole.DRAFTS,
        "\\trash" to FolderRole.TRASH,
        "\\junk" to FolderRole.JUNK,
        "\\archive" to FolderRole.ARCHIVE,
        "\\all" to FolderRole.ALL,
    )

    /** RFC 6154 attributes, plus Gmail's `\Important`. */
    private val SPECIAL_USE = setOf(
        "\\all", "\\archive", "\\drafts", "\\flagged", "\\junk", "\\sent", "\\trash", "\\important",
    )

    private val ARCHIVE_NAMES = setOf("archive", "archives")

    /** Riseup's spam filter files only into a mailbox named exactly this (riseup R12, R20). */
    private const val RISEUP_SPAM = "Spam"

    /**
     * Riseup mailboxes. Roles come from SPECIAL-USE attributes and INBOX from its name; each role goes to one
     * mailbox. Archive: [archivePath] (the user's choice), else `\Archive`, else a top-level `Archive` or
     * `Archives` (any case). `\Noselect` mailboxes are left out.
     */
    fun riseup(accountId: Long, list: List<MailboxInfo>, archivePath: String? = null): List<NewFolder> {
        val boxes = list.filterNot { it.has(NOSELECT) }
        val roles = HashMap<String, FolderRole>()
        fun assign(role: FolderRole, info: MailboxInfo?) {
            if (info != null && info.name !in roles && role !in roles.values) roles[info.name] = role
        }
        assign(FolderRole.INBOX, boxes.firstOrNull { it.name.equals(INBOX, ignoreCase = true) })
        assign(FolderRole.ARCHIVE, boxes.firstOrNull { it.name == archivePath })
        for (info in boxes) {
            info.attributes.firstNotNullOfOrNull { ATTRIBUTE_ROLES[it.lowercase()] }?.let { assign(it, info) }
        }
        assign(FolderRole.ARCHIVE, boxes.firstOrNull { it.name.lowercase() in ARCHIVE_NAMES })
        assign(FolderRole.JUNK, boxes.firstOrNull { it.name == RISEUP_SPAM })
        return boxes.map { info ->
            val inbox = roles[info.name] == FolderRole.INBOX
            NewFolder(
                accountId = accountId,
                kind = FolderKind.MAILBOX,
                path = if (inbox) INBOX else info.name,
                name = if (inbox) "Inbox" else leaf(info.name, info.delimiter),
                role = roles[info.name],
                delimiter = info.delimiter,
            )
        }
    }

    /**
     * The Riseup archive (protocol PS5, simplicity EM5): the chosen folder, else the `\Archive` mailbox, else a
     * mailbox named Archive or Archives (any case). Null for Gmail and while none exists (the first archive
     * action creates "Archive").
     */
    fun archiveOf(account: Account, folders: List<Folder>): Folder? {
        if (account.isGmail) return null
        val own = folders.filter { it.accountId == account.id && it.kind == FolderKind.MAILBOX && !it.hidden }
            .sortedBy { it.id }
        return own.firstOrNull { it.id == account.archiveFolder }
            ?: own.firstOrNull { it.role == FolderRole.ARCHIVE }
            ?: own.firstOrNull { it.role == null && it.path.lowercase() in ARCHIVE_NAMES }
    }

    /** Gmail folder rows and the required mailboxes that LIST does not show. */
    data class Gmail(val folders: List<NewFolder>, val missing: Set<FolderRole>)

    /**
     * Gmail: the mailboxes `\All`, `\Trash`, `\Junk` and `\Drafts` (stored hidden) by SPECIAL-USE, never by
     * name; the system label views; a label view for every other selectable mailbox without a SPECIAL-USE
     * attribute that is not INBOX.
     */
    fun gmail(accountId: Long, list: List<MailboxInfo>): Gmail {
        val folders = ArrayList<NewFolder>()
        val found = HashSet<FolderRole>()
        for (info in list) {
            if (info.has(NOSELECT)) continue
            val attributes = info.attributes.map { it.lowercase() }
            val role = when {
                "\\all" in attributes -> FolderRole.ALL
                "\\trash" in attributes -> FolderRole.TRASH
                "\\junk" in attributes -> FolderRole.JUNK
                "\\drafts" in attributes -> FolderRole.DRAFTS
                else -> null
            }
            when {
                role != null -> if (found.add(role)) {
                    folders += NewFolder(
                        accountId, FolderKind.MAILBOX, info.name, leaf(info.name, info.delimiter), role, info.delimiter,
                        hidden = role == FolderRole.DRAFTS,
                    )
                }
                attributes.any { it in SPECIAL_USE } || info.name.equals(INBOX, ignoreCase = true) -> Unit
                else -> folders += NewFolder(
                    accountId, FolderKind.LABEL, info.name, leaf(info.name, info.delimiter), null, info.delimiter,
                )
            }
        }
        SYSTEM_VIEWS.forEach { (path, role) ->
            folders += NewFolder(accountId, FolderKind.LABEL, path, systemName(role), role, null)
        }
        return Gmail(folders, GMAIL_REQUIRED - found)
    }

    /**
     * The label-view path of an `X-GM-LABELS` value: user labels as they are, `\Drafts` as `\Draft`, and null for
     * system labels without a view (such as `\Starred`, which is the flagged state).
     */
    fun viewPath(label: String): String? = when {
        !label.startsWith('\\') -> label.takeIf { it.isNotEmpty() }
        label.equals("\\Drafts", ignoreCase = true) -> "\\Draft"
        else -> SYSTEM_VIEWS.keys.firstOrNull { it.equals(label, ignoreCase = true) }
    }

    /** Display name: the last level of [path]. */
    fun leaf(path: String, delimiter: String?): String =
        if (delimiter.isNullOrEmpty()) path else path.substringAfterLast(delimiter).ifEmpty { path }

    private fun systemName(role: FolderRole) = when (role) {
        FolderRole.INBOX -> "Inbox"
        FolderRole.SENT -> "Sent"
        FolderRole.DRAFTS -> "Drafts"
        else -> "Important"
    }

    private fun MailboxInfo.has(attribute: String) = attributes.any { it.equals(attribute, ignoreCase = true) }
}
