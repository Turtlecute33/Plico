package app.plico.data

import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode

/**
 * The behaviour of an account's servers, not a brand: table providers such as Yahoo or Fastmail are [OTHER].
 * [brand] is the default account label base, the same in every language (never shown as "for X").
 */
enum class Provider(val id: String, val brand: String) {
    RISEUP("riseup", "Riseup"), GMAIL("gmail", "Gmail"), MICROSOFT("microsoft", "Outlook"), OTHER("other", "Mail");

    companion object {
        /** Unknown ids (a newer build, a bad row) read as OTHER, never a crash. */
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/** Incoming and outgoing servers of one account. [login] null = the email address. */
data class Servers(val imap: Endpoint, val smtp: Endpoint, val login: String? = null) {
    companion object {
        /** No servers; only in test fixtures. */
        val NONE = Servers(Endpoint("", 993, TlsMode.IMPLICIT), Endpoint("", 465, TlsMode.IMPLICIT))
    }
}

enum class AuthKind(val id: String) {
    /** Account password or app password. */
    PASSWORD("password"),

    /** Browser OAuth; the refresh token is the secret. */
    OAUTH("oauth"),

    /** Google account on the phone (AccountManager). */
    DEVICE("device");

    companion object {
        fun of(id: String) = entries.first { it.id == id }
    }
}

enum class PushMode(val id: Int) {
    INSTANT(0),

    /** Instant on unmetered Wi-Fi or while charging, polling on mobile data. */
    INSTANT_ON_WIFI(1),
    POLL(2);

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: INSTANT
    }
}

enum class AccountState(val id: Int) {
    OK(0), SIGN_IN_NEEDED(1), TLS_PROBLEM(2), SETUP_PROBLEM(3);

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: OK
    }
}

data class Account(
    val id: Long,
    val provider: Provider,
    val email: String,
    val senderName: String,
    val label: String,
    val color: Int,
    val auth: AuthKind,
    /** AccountManager account name for [AuthKind.DEVICE]. The secret itself is read with AccountDao.secret(). */
    val deviceAccount: String?,
    /** 0 = all mail offline. */
    val bodyDays: Int,
    val pushMode: PushMode,
    val pollMinutes: Int,
    /** Gmail categories that notify: primary, social, promotions, updates, forums. */
    val gmailCategories: Set<String>,
    val archiveFolder: Long?,
    val signature: String,
    val state: AccountState,
    val stateText: String?,
    val serverKeepaliveS: Int?,
    val quotaUsed: Long?,
    val quotaLimit: Long?,
    val sort: Int,
    /** [Servers.NONE] only in test fixtures. */
    val servers: Servers = Servers.NONE,
    /** False when the server has no IDLE: the account polls. */
    val idle: Boolean = true,
) {
    val isGmail: Boolean get() = provider == Provider.GMAIL

    /** The IMAP/SMTP user name (also the XOAUTH2 user). */
    val login: String get() = servers.login ?: email
}

data class Identity(val id: Long, val accountId: Long, val email: String, val name: String)

/**
 * What an account allows (DESIGN §7.3): Delete and Spam move to Trash and Spam. A Gmail account whose Trash or
 * Spam is hidden from IMAP has no such mailbox, so Delete or Spam is off; Riseup creates them on first use.
 */
data class AccountAbilities(val canDelete: Boolean = true, val canSpam: Boolean = true) {
    companion object {
        fun of(account: Account, folders: List<Folder>): AccountAbilities {
            if (!account.isGmail) return AccountAbilities()
            fun has(role: FolderRole) =
                folders.any { it.accountId == account.id && it.kind == FolderKind.MAILBOX && it.role == role }
            return AccountAbilities(canDelete = has(FolderRole.TRASH), canSpam = has(FolderRole.JUNK))
        }
    }
}

enum class FolderRole(val id: String) {
    INBOX("inbox"), SENT("sent"), DRAFTS("drafts"), TRASH("trash"), JUNK("junk"),
    ARCHIVE("archive"), ALL("all"), IMPORTANT("important");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

object FolderKind {
    /** IMAP mailbox that is SELECTed and synced (when not hidden). */
    const val MAILBOX = 0

    /** Gmail label view; membership comes from X-GM-LABELS. */
    const val LABEL = 1
}

data class Folder(
    val id: Long,
    val accountId: Long,
    /** Decoded mailbox name, or Gmail label (system labels keep the backslash, e.g. `\Inbox`). */
    val path: String,
    val name: String,
    val role: FolderRole?,
    val kind: Int,
    val delimiter: String?,
    val hidden: Boolean,
    val notify: Boolean,
    val guard: Boolean,
    val uidValidity: Long,
    val uidNext: Long,
    val modSeq: Long,
    /** Headers are synced for UIDs >= this; 1 = complete; 0 = never synced. */
    val backfillUid: Long,
    val syncedAt: Long,
)

/** What a message list shows. */
sealed interface MailView {
    data object Unified : MailView
    data class InFolder(val folderId: Long) : MailView
    data class Starred(val accountId: Long?) : MailView
    data class Search(val query: String, val accountId: Long?) : MailView
}

/** One row of a message list. */
data class MessageSummary(
    val id: Long,
    val accountId: Long,
    val folderId: Long,
    val subject: String,
    val fromName: String,
    val fromEmail: String,
    val date: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val draft: Boolean,
    val attachments: Boolean,
    val snippet: String,
    /** Stored `to_list` form; the list shows the first recipient for mail the user sent. */
    val toList: String = "",
    /** [Auth] value (the sender logo gate). */
    val auth: Int = Auth.UNKNOWN,
)

object Auth {
    const val UNKNOWN = 0
    const val PASS = 1
    const val FAIL = 2
}

data class Message(
    val id: Long,
    val accountId: Long,
    val folderId: Long,
    val uid: Long,
    val gmMsgId: Long?,
    val messageId: String?,
    val inReplyTo: String?,
    val refs: String?,
    val subject: String,
    val fromName: String,
    val fromEmail: String,
    /** RFC 5322 formatted, decoded. */
    val toList: String,
    val ccList: String,
    val bccList: String,
    val replyTo: String,
    /** INTERNALDATE, epoch ms. */
    val date: Long,
    val size: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val draft: Boolean,
    val modSeq: Long,
    val attachments: Int,
    val snippet: String,
    /** [Auth] value. */
    val auth: Int,
    val unsubscribe: String?,
    val unsubscribePost: Boolean,
    val hasBody: Boolean,
    val notified: Boolean,
    val hidden: Boolean,
)

data class Body(val messageId: Long, val html: String?, val text: String?)

data class Attachment(
    val id: Long,
    val messageId: Long,
    val section: String,
    /** Display name from the sender. Never used as a file name on disk. */
    val name: String,
    val mime: String,
    val size: Long,
    val encoding: String,
    val cid: String?,
    val inline: Boolean,
)

object OpKind {
    const val FLAGS = "flags"
    const val LABELS = "labels"
    const val MOVE = "move"
    const val EXPUNGE = "expunge"
    const val APPEND = "append"
    const val DELETE_DRAFT = "delete_draft"
}

data class Op(
    val id: Long,
    val accountId: Long,
    val kind: String,
    val messageIds: List<Long>,
    /** JSON object with the operation arguments. */
    val arg: String,
    val attempts: Int,
    val runAt: Long,
    val created: Long,
    val error: String?,
)

object DraftState {
    const val EDITING = 0
    const val QUEUED = 1
    const val SENDING = 2
    const val FAILED = 3
    const val SENT = 4

    /** Failed, but it may have been sent (no reply to the final dot, or the process died while sending). */
    const val UNCERTAIN = 5

    /** A send that did not go: the user can edit it or try again. */
    fun failed(state: Int): Boolean = state == FAILED || state == UNCERTAIN
}

/**
 * A draft attachment; [file] is a safe name inside filesDir/outbox/<draftId>/. [metadataKept]: an image that
 * goes out as it is, with its metadata (DESIGN §10: GIF, animations, other types, "Original quality").
 */
data class DraftAttachment(
    val file: String,
    val name: String,
    val mime: String,
    val size: Long,
    val metadataKept: Boolean = false,
)

data class Draft(
    val id: Long,
    val accountId: Long,
    val messageId: String,
    val fromEmail: String,
    val toList: String,
    val ccList: String,
    val bccList: String,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val refs: String?,
    val sourceMessage: Long?,
    val attachments: List<DraftAttachment>,
    val onServer: Boolean,
    val state: Int,
    val sendAt: Long,
    val attempts: Int,
    val error: String?,
    val updated: Long,
)

data class Contact(val email: String, val name: String, val lastUsed: Long, val uses: Int)

/** A folder row from LIST (kind 0) or a Gmail label view (kind 1), before it has an id. */
data class NewFolder(
    val accountId: Long,
    val kind: Int,
    val path: String,
    val name: String,
    val role: FolderRole?,
    val delimiter: String?,
    val hidden: Boolean = false,
)

/** A header row from a FETCH, before it has an id. */
data class NewMessage(
    val accountId: Long,
    /** Source mailbox (kind 0). */
    val folderId: Long,
    val uid: Long,
    val date: Long,
    val gmMsgId: Long? = null,
    val messageId: String? = null,
    val inReplyTo: String? = null,
    val refs: String? = null,
    val subject: String = "",
    val fromName: String = "",
    val fromEmail: String = "",
    val toList: String = "",
    val ccList: String = "",
    val bccList: String = "",
    val replyTo: String = "",
    val size: Long = 0,
    val seen: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val draft: Boolean = false,
    val modSeq: Long = 0,
    val attachments: Int = 0,
    /** [Auth] value. */
    val auth: Int = Auth.UNKNOWN,
    val unsubscribe: String? = null,
    val unsubscribePost: Boolean = false,
    /** False only for rows of a step-4 new-UID fetch that may notify (DESIGN.md §9). */
    val notified: Boolean = true,
    /** Gmail label view ids (kind 1) from X-GM-LABELS; null leaves the memberships unchanged (Riseup). */
    val labels: Set<Long>? = null,
    /** The server flag `\Deleted` (not Gmail): the row is stored but not shown. */
    val deleted: Boolean = false,
)

/** Server state of one row of a source mailbox, from a flags FETCH. */
data class ServerFlags(
    val uid: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val draft: Boolean,
    val modSeq: Long? = null,
    /** Gmail label view ids (kind 1) from X-GM-LABELS; null leaves the memberships unchanged. */
    val labels: Set<Long>? = null,
    /** The server flag `\Deleted` (not Gmail). */
    val deleted: Boolean = false,
)

/** Where a message lives on the server. */
data class MessageRef(val id: Long, val folderId: Long, val uid: Long)

/** An attachment or inline part found in the BODYSTRUCTURE. */
data class NewAttachment(
    val section: String,
    /** Display name from the sender. Never used as a file name on disk. */
    val name: String,
    val mime: String,
    val size: Long,
    val encoding: String,
    val cid: String?,
    val inline: Boolean,
)

/** Everything the message screen shows from the database. */
data class MessageDetail(
    val message: Message,
    /** Null until the body is downloaded. */
    val body: Body?,
    val attachments: List<Attachment>,
    /** The source mailbox and the Gmail label views that hold the message. */
    val folders: List<Folder>,
)

/** A pending server operation (DESIGN.md §7.4), before it has an id. */
data class NewOp(
    val accountId: Long,
    /** [OpKind] value. */
    val kind: String,
    val messageIds: List<Long> = emptyList(),
    /** JSON object with the operation arguments. */
    val arg: String = "{}",
    /** Epoch ms when the op becomes due (same clock as [Op.created]); 0 = at once. */
    val runAt: Long = 0,
)

/** The first content of a draft. [messageId] is set only when an existing message is copied into a draft. */
data class NewDraft(
    val accountId: Long,
    val fromEmail: String,
    val toList: String = "",
    val ccList: String = "",
    val bccList: String = "",
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String? = null,
    val refs: String? = null,
    val sourceMessage: Long? = null,
    val messageId: String? = null,
)
