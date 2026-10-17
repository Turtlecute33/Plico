package app.plico.sync

import app.plico.data.Account
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.NewFolder
import app.plico.data.Provider
import app.plico.mail.imap.MailboxInfo
import app.plico.mail.imap.Namespace
import app.plico.mail.imap.Namespaces
import java.text.Normalizer
import java.util.Locale

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
     * Role names for servers without SPECIAL-USE, compared with the lower-case leaf of a direct child of the personal
     * root. For SENT, DRAFTS and ARCHIVE the first name of the list that a mailbox has wins; TRASH and JUNK need
     * exactly one match (a wrong Trash would be emptied, and Trash has no mass-delete guard).
     */
    internal val ROLE_NAMES: Map<FolderRole, List<String>> = linkedMapOf(
        FolderRole.SENT to listOf(
            "sent", "sent items", "sent messages", "sent mail", "gesendet", "gesendete elemente", "gesendete objekte",
            "posta inviata", "inviati", "elementi inviati", "envoyés", "éléments envoyés", "enviados",
            "elementos enviados", "verzonden", "verzonden items", "skickat", "wysłane", "elementy wysłane", "odeslané",
            "odeslaná pošta", "отправленные",
        ),
        FolderRole.DRAFTS to listOf(
            "drafts", "draft", "entwürfe", "bozze", "brouillons", "borradores", "concepten", "utkast", "kopie robocze",
            "wersje robocze", "koncepty", "черновики",
        ),
        FolderRole.TRASH to listOf(
            "trash", "deleted items", "deleted messages", "deleted", "bin", "papierkorb", "gelöschte elemente",
            "gelöschte objekte", "cestino", "posta eliminata", "elementi eliminati", "corbeille", "éléments supprimés",
            "papelera", "elementos eliminados", "prullenbak", "verwijderde items", "papperskorgen", "kosz",
            "elementy usunięte", "koš", "odstraněná pošta", "корзина",
        ),
        FolderRole.JUNK to listOf(
            "junk", "junk e-mail", "junk email", "junk-e-mail", "junk mail", "spam", "bulk mail", "posta indesiderata",
            "indésirables", "courrier indésirable", "correo no deseado", "ongewenste e-mail", "skräppost",
            "wiadomości-śmieci", "nevyžádaná pošta", "спам",
        ),
        FolderRole.ARCHIVE to listOf(
            "archive", "archives", "archiv", "archivio", "archivo", "archief", "arkiv", "archiwum", "архив",
        ),
    )

    /** Roles that need exactly one matching name. */
    private val UNIQUE_NAME_ROLES = setOf(FolderRole.TRASH, FolderRole.JUNK)

    /** Virtual mailboxes that would show every message twice (Dovecot `\All`, `\Flagged`). */
    private val VIRTUAL = setOf("\\all", "\\flagged")

    /**
     * Mailbox rows of a server that is not Gmail ([riseup] = the Riseup rules, unchanged). `\Noselect` mailboxes
     * are left out. Generic servers, in this order:
     * 1. a mailbox in another user's or a shared namespace of [ns] is stored hidden, without a role (not synced);
     * 2. INBOX by name, the user's archive [archivePath], SPECIAL-USE attributes (one mailbox per role);
     *    `\All` and `\Flagged` are stored hidden;
     * 3. role names ([ROLE_NAMES]) on the leaf of the direct children of the personal root ([prefixOf]).
     */
    fun mailboxes(
        accountId: Long,
        list: List<MailboxInfo>,
        archivePath: String? = null,
        ns: Namespaces? = null,
        riseup: Boolean,
    ): List<NewFolder> = if (riseup) riseupRows(accountId, list, archivePath) else genericRows(accountId, list, archivePath, ns)

    /**
     * The personal root of the account's mailboxes, for role names and for the default names that Plico creates:
     * the personal NAMESPACE prefix when it is not empty; else `INBOX<delimiter>` when every mailbox other than INBOX
     * (at least one) is below it (Courier and cPanel servers); else "".
     */
    fun prefixOf(nsPrefix: String?, paths: Collection<String>, delimiter: String?): String {
        if (!nsPrefix.isNullOrEmpty()) return nsPrefix
        if (delimiter.isNullOrEmpty()) return ""
        val inbox = INBOX + delimiter
        val others = paths.filterNot { it.equals(INBOX, ignoreCase = true) }
        return if (others.isNotEmpty() && others.all { it.startsWith(inbox) }) inbox else ""
    }

    /**
     * [path] below the personal [prefix] (a name that Plico creates). A path that is INBOX, or that already starts
     * with the prefix, stays as it is.
     */
    fun withPrefix(prefix: String, path: String): String =
        if (prefix.isEmpty() || path.startsWith(prefix) || path.equals(INBOX, ignoreCase = true)) path else prefix + path

    private fun riseupRows(accountId: Long, list: List<MailboxInfo>, archivePath: String?): List<NewFolder> {
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
        return boxes.map { info -> row(accountId, info, roles[info.name], hidden = false) }
    }

    private fun genericRows(accountId: Long, list: List<MailboxInfo>, archivePath: String?, ns: Namespaces?): List<NewFolder> {
        val boxes = list.filterNot { it.has(NOSELECT) }
        val hidden = boxes.filter { isForeign(it, ns) || it.attributes.any { a -> a.lowercase() in VIRTUAL } }
            .mapTo(HashSet()) { it.name }
        val open = boxes.filter { it.name !in hidden }
        val roles = HashMap<String, FolderRole>()
        fun assign(role: FolderRole, info: MailboxInfo?) {
            if (info != null && info.name !in roles && role !in roles.values) roles[info.name] = role
        }
        assign(FolderRole.INBOX, open.firstOrNull { it.name.equals(INBOX, ignoreCase = true) })
        assign(FolderRole.ARCHIVE, open.firstOrNull { it.name == archivePath })
        for (info in open) {
            info.attributes.firstNotNullOfOrNull { ATTRIBUTE_ROLES[it.lowercase()] }?.let { assign(it, info) }
        }
        val personal = ns?.personal?.firstOrNull()
        val delimiter = personal?.delimiter ?: delimiterOf(boxes)
        val root = prefixOf(personal?.prefix, boxes.map { it.name }, delimiter)
        val children = open.filter { it.name !in roles && isDirectChild(it.name, root, delimiter) }
        for ((role, names) in ROLE_NAMES) {
            if (role in roles.values) continue
            val matches = children.filter { it.name !in roles && leafOf(it.name, root) in names }
            if (role in UNIQUE_NAME_ROLES) {
                if (matches.size == 1) assign(role, matches.single())
            } else {
                assign(role, names.firstNotNullOfOrNull { name -> matches.firstOrNull { leafOf(it.name, root) == name } })
            }
        }
        return boxes.map { info -> row(accountId, info, roles[info.name], hidden = info.name in hidden) }
    }

    /**
     * True when [info] is in another user's or a shared namespace: the longest prefix that the name starts with
     * decides, a personal prefix wins a tie, and empty foreign prefixes are ignored. INBOX is always personal.
     */
    private fun isForeign(info: MailboxInfo, ns: Namespaces?): Boolean {
        if (ns == null || info.name.equals(INBOX, ignoreCase = true)) return false
        fun longest(spaces: List<Namespace>, allowEmpty: Boolean): Int = spaces
            .filter { allowEmpty || it.prefix.isNotEmpty() }
            .filter { inNamespace(info.name, it) }
            .maxOfOrNull { it.prefix.length } ?: -1
        val foreign = longest(ns.otherUsers + ns.shared, allowEmpty = false)
        return foreign >= 0 && foreign > longest(ns.personal, allowEmpty = true)
    }

    /** [name] starts with the prefix of [space], or is its root (the prefix without its last delimiter). */
    private fun inNamespace(name: String, space: Namespace): Boolean {
        val prefix = space.prefix
        if (name.startsWith(prefix)) return true
        val delimiter = space.delimiter
        return !delimiter.isNullOrEmpty() && prefix.endsWith(delimiter) && name == prefix.dropLast(delimiter.length)
    }

    private fun isDirectChild(name: String, root: String, delimiter: String?): Boolean {
        if (!name.startsWith(root) || name.length == root.length) return false
        return delimiter.isNullOrEmpty() || delimiter !in name.substring(root.length)
    }

    private fun leafOf(name: String, root: String): String =
        Normalizer.normalize(name.substring(root.length), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /** The delimiter of INBOX, else the first one that LIST reports. */
    private fun delimiterOf(boxes: List<MailboxInfo>): String? =
        boxes.firstOrNull { it.name.equals(INBOX, ignoreCase = true) }?.delimiter?.takeIf { it.isNotEmpty() }
            ?: boxes.firstNotNullOfOrNull { it.delimiter?.takeIf { d -> d.isNotEmpty() } }

    private fun row(accountId: Long, info: MailboxInfo, role: FolderRole?, hidden: Boolean): NewFolder {
        val inbox = role == FolderRole.INBOX
        return NewFolder(
            accountId = accountId,
            kind = FolderKind.MAILBOX,
            path = if (inbox) INBOX else info.name,
            name = if (inbox) "Inbox" else leaf(info.name, info.delimiter),
            role = role,
            delimiter = info.delimiter,
            hidden = hidden,
        )
    }

    /**
     * The archive of a server that is not Gmail. Riseup (protocol PS5, simplicity EM5): the chosen folder, else the
     * `\Archive` mailbox, else a mailbox named Archive or Archives (any case). Other servers: the chosen folder, else
     * the mailbox with the ARCHIVE role (SPECIAL-USE or a role name at LIST time). Null for Gmail and while none
     * exists (the first archive action creates "Archive").
     */
    fun archiveOf(account: Account, folders: List<Folder>): Folder? {
        if (account.isGmail) return null
        val own = folders.filter { it.accountId == account.id && it.kind == FolderKind.MAILBOX && !it.hidden }
            .sortedBy { it.id }
        val found = own.firstOrNull { it.id == account.archiveFolder } ?: own.firstOrNull { it.role == FolderRole.ARCHIVE }
        if (found != null || account.provider != Provider.RISEUP) return found
        return own.firstOrNull { it.role == null && it.path.lowercase() in ARCHIVE_NAMES }
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
