package app.plico.ui.message

import androidx.annotation.StringRes
import app.plico.R
import app.plico.account.MailError
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MailView
import app.plico.data.TrustedOn
import app.plico.html.Idn
import app.plico.html.Link
import app.plico.html.LinkWarning
import app.plico.html.ListUnsubscribe
import app.plico.html.Mailto
import app.plico.html.SenderTrust
import app.plico.html.SenderWarning
import app.plico.mail.mime.Address
import app.plico.mail.mime.Addresses
import app.plico.mail.mime.HeaderBlock
import app.plico.sync.DraftPrefill
import app.plico.ui.components.BannerKind
import app.plico.ui.components.ErrorText
import java.io.IOException

// Pure rules of the message screen (DESIGN §11, §13 Message). No Android types, so JVM tests cover them.

/** The sender as the header shows it: the display name, and the real address when the name is not that address. */
data class SenderLine(val name: String, val address: String?)

fun senderLine(fromName: String, fromEmail: String): SenderLine {
    val name = revealBidi(fromName.trim())
    return if (name.isEmpty() || name.equals(fromEmail, ignoreCase = true)) {
        SenderLine(fromEmail, null)
    } else {
        SenderLine(name, fromEmail)
    }
}

enum class RecipientField(@StringRes val label: Int) {
    TO(R.string.message_field_to),
    CC(R.string.message_field_cc),
    BCC(R.string.message_field_bcc),
    REPLY_TO(R.string.message_field_reply_to),
}

/** One expanded header line ("To", "Cc", "Bcc", "Reply to") with its addresses; empty fields have no line. */
data class RecipientLine(val field: RecipientField, val addresses: List<Address>)

fun recipientLines(to: String, cc: String, bcc: String): List<RecipientLine> =
    listOf(RecipientField.TO to to, RecipientField.CC to cc, RecipientField.BCC to bcc).mapNotNull { (field, value) ->
        Addresses.parseList(value).takeIf { it.isNotEmpty() }?.let { RecipientLine(field, it) }
    }

/** The Reply-To line, when Reply-To names an address other than the sender's. */
fun replyToLine(replyTo: String, fromEmail: String): RecipientLine? = Addresses.parseList(replyTo)
    .takeIf { list -> list.any { !it.email.equals(fromEmail, ignoreCase = true) } }
    ?.let { RecipientLine(RecipientField.REPLY_TO, it) }

/** "Name <address>" for the expanded header, else the address. */
fun addressText(address: Address): String =
    address.name?.trim()?.takeIf { it.isNotEmpty() }?.let { "${revealBidi(it)} <${address.email}>" } ?: address.email

/**
 * The collapsed recipient line, "to me, Alice and 2 more": the first [names] (null = one of the user's own
 * addresses, shown as "me") and the number of further recipients.
 */
data class RecipientSummary(val names: List<String?>, val more: Int)

/** [own] holds the user's addresses in lower case. Null when the message has no recipients. */
fun recipientSummary(lines: List<RecipientLine>, own: Set<String>, max: Int = 2): RecipientSummary? {
    val all = lines.flatMap { it.addresses }.distinctBy { it.email.lowercase() }
    if (all.isEmpty()) return null
    val names = all.take(max).map { a -> if (a.email.lowercase() in own) null else shortName(a) }
    return RecipientSummary(names, all.size - names.size)
}

/** The display name, else the address. */
fun shortName(address: Address): String =
    address.name?.trim()?.takeIf { it.isNotEmpty() }?.let(::revealBidi) ?: address.email

/** True when Reply all reaches more people than Reply: more than one other address in From, To and Cc. */
fun replyAllUseful(fromEmail: String, to: String, cc: String, own: Set<String>): Boolean {
    val others = (listOf(fromEmail) + Addresses.parseList("$to, $cc").map { it.email })
        .map { it.lowercase() }
        .filter { it.isNotEmpty() && it !in own }
        .toSet()
    return others.size > 1
}

/** A sender-check banner (DESIGN §11.4). */
data class SenderBanner(val warning: SenderWarning, val kind: BannerKind)

/**
 * Banners for the sender warnings, most serious first. A failed authentication is an error; the other checks
 * are warnings. [SenderWarning.FIRST_CONTACT] is not a banner: the header shows it as a subtle note.
 */
fun senderBanners(warnings: List<SenderWarning>): List<SenderBanner> = warnings
    .filter { it != SenderWarning.FIRST_CONTACT }
    .map { SenderBanner(it, if (it == SenderWarning.AUTH_FAILED) BannerKind.ERROR else BannerKind.WARN) }

/** Where the message is, and so which actions apply (DESIGN §7.3, §13). */
data class Placement(
    val gmail: Boolean,
    /** Role of the source mailbox (kind 0): All Mail, Trash, Junk on Gmail; any mailbox on Riseup. */
    val sourceRole: FolderRole?,
    /** Gmail: has the `\Inbox` label. Riseup: is in INBOX. */
    val inInbox: Boolean,
    /** Gmail: has the `\Important` label. */
    val important: Boolean,
    /** Gmail label chips: visible label views other than Important. Empty on Riseup. */
    val labels: List<Folder>,
    /** Gmail: every label view the message has (for the Labels picker). Empty on Riseup. */
    val labelIds: Set<Long>,
    /** The view that archive and move act from: the Gmail inbox label when the message has it, else the source. */
    val view: MailView,
) {
    val inJunk: Boolean get() = sourceRole == FolderRole.JUNK
    val inTrash: Boolean get() = sourceRole == FolderRole.TRASH

    /** Delete in Trash or Spam removes the message for good (after a confirmation). */
    val deletesForever: Boolean get() = inJunk || inTrash

    /** Gmail: archive removes `\Inbox`, so only inbox messages. Riseup: anything outside Archive, Trash and Spam. */
    val canArchive: Boolean
        get() = if (gmail) inInbox else sourceRole != FolderRole.ARCHIVE && !inTrash && !inJunk
}

/**
 * The user's own message, which gets no sender checks: it is in Sent or Drafts (Gmail: it has that label).
 * Never decided by From: the sender chooses that header, so a forged "From: <own address>" is checked.
 */
fun sentByUser(folders: List<Folder>): Boolean =
    folders.any { it.role == FolderRole.SENT || it.role == FolderRole.DRAFTS }

/**
 * True when the sender block may show the cached logo of [fromEmail] (DESIGN §12), with the same gate as the
 * list: received mail only (not in Sent or Drafts ([ownMessage]), not a [draft], From not one of the user's [own]
 * lower-case addresses), and a [trust] other than NONE (never in Spam, never Gmail mail without a DMARC pass).
 */
fun logoAllowed(fromEmail: String, draft: Boolean, ownMessage: Boolean, own: Set<String>, trust: SenderTrust): Boolean =
    !ownMessage && !draft && fromEmail.isNotBlank() && fromEmail.lowercase() !in own && trust != SenderTrust.NONE

/** [folders] = the source mailbox and the label views of the message ([app.plico.data.MessageDetail.folders]). */
fun placementOf(sourceFolderId: Long, folders: List<Folder>, gmail: Boolean): Placement {
    val source = folders.firstOrNull { it.id == sourceFolderId }
    val labels = folders.filter { it.kind == FolderKind.LABEL }
    val inboxLabel = labels.firstOrNull { it.role == FolderRole.INBOX }
    return Placement(
        gmail = gmail,
        sourceRole = source?.role,
        inInbox = if (gmail) inboxLabel != null else source?.role == FolderRole.INBOX,
        important = gmail && labels.any { it.role == FolderRole.IMPORTANT },
        labels = if (gmail) labels.filter { !it.hidden && it.role != FolderRole.IMPORTANT } else emptyList(),
        labelIds = if (gmail) labels.mapTo(HashSet()) { it.id } else emptySet(),
        view = MailView.InFolder(if (gmail && inboxLabel != null) inboxLabel.id else sourceFolderId),
    )
}

/**
 * True when the trust of the user ([trustedOn], null = none) counts for a message of [trust] (DESIGN §11.4,
 * PLICO-PRIV-003). A trust set on a VERIFIED message counts only on VERIFIED messages: on another account From can
 * be forged, and the user did not see the "A sender can be faked" note. A trust set by From only counts on both.
 */
fun trustedFor(trustedOn: TrustedOn?, trust: SenderTrust): Boolean = when (trustedOn) {
    null -> false
    TrustedOn.VERIFIED -> trust == SenderTrust.VERIFIED
    TrustedOn.FROM_ONLY -> trust.alwaysShowRemote
}

/**
 * Whether the message view loads remote content (DESIGN §11.4, privacy PS4): "Show" was tapped, or the setting
 * shows it by default (never in Spam), or the user trusts the sender and [trust] honours that.
 */
fun allowRemote(
    defaultOn: Boolean,
    shownOnce: Boolean,
    trusted: Boolean,
    trust: SenderTrust,
    inJunk: Boolean,
): Boolean = shownOnce || (defaultOn && !inJunk) || (trusted && trust.alwaysShowRemote)

enum class RemoteImages { NONE, BLOCKED, SHOWN }

/**
 * The remote-content bar: "Images blocked · N trackers removed", Show, and "Always from this sender".
 *
 * @property always state of the "Always from this sender" switch; null when it is not offered.
 * @property note text under the switch (Riseup: "A sender can be faked").
 */
data class RemoteBar(val images: RemoteImages, val trackers: Int, val always: Boolean?, @StringRes val note: Int?)

/**
 * The bar for a sanitized body, or null when there is nothing to say. The switch is offered only when the
 * message has remote content, [trust] honours it and the setting does not already show all remote content.
 */
fun remoteBar(
    hasRemote: Boolean,
    allowed: Boolean,
    trackers: Int,
    trust: SenderTrust,
    trusted: Boolean,
    defaultOn: Boolean,
): RemoteBar? {
    val images = when {
        !hasRemote -> RemoteImages.NONE
        allowed -> RemoteImages.SHOWN
        else -> RemoteImages.BLOCKED
    }
    val always = trusted.takeIf { hasRemote && trust.alwaysShowRemote && !defaultOn }
    if (images != RemoteImages.BLOCKED && trackers == 0 && always == null) return null
    return RemoteBar(images, trackers, always, trust.note.takeIf { always != null })
}

/** How Unsubscribe works for a message (DESIGN §11.4, §13). */
sealed interface Unsubscribe {
    /** RFC 8058 POST through MailActions.unsubscribeOneClick, after a confirmation. */
    data object OneClick : Unsubscribe

    /** A prefilled draft to the list address. */
    data class Mail(val mailto: Mailto) : Unsubscribe

    /** The unsubscribe page, through the link sheet. */
    data class Web(val url: String) : Unsubscribe
}

/**
 * The unsubscribe method from `List-Unsubscribe` ([header]) and `List-Unsubscribe-Post` ([post]). One-click
 * needs an https URI and [SenderTrust.oneClickUnsubscribe]; else a mailto URI, else a web page.
 */
fun unsubscribeOf(header: String?, post: Boolean, trust: SenderTrust): Unsubscribe? {
    val methods = ListUnsubscribe.parse(header) ?: return null
    return when {
        post && trust.oneClickUnsubscribe && methods.oneClick != null -> Unsubscribe.OneClick
        methods.mail != null -> Unsubscribe.Mail(methods.mail)
        methods.web != null -> Unsubscribe.Web(methods.web)
        else -> null
    }
}

/** A draft prefill from a `mailto:` link (bare addresses; RFC 6068 fields only). */
fun prefillOf(mailto: Mailto): DraftPrefill = DraftPrefill(
    to = mailto.to.joinToString(", ") { it.email },
    cc = mailto.cc.joinToString(", ") { it.email },
    bcc = mailto.bcc.joinToString(", ") { it.email },
    subject = mailto.subject,
    body = mailto.body,
)

/**
 * The link sheet (DESIGN §11.3, privacy PS5/PS6).
 *
 * @property title the registrable domain (else the host) in ASCII form, shown in bold; null for links that Plico
 *   does not open.
 * @property unicodeTitle [title] in Unicode, when the host has non-ASCII labels (the title then shows punycode).
 * @property url the cleaned URL that Open opens, exactly as shown.
 * @property titleRange where [title] is in [url], to show it in bold there too.
 * @property original the link as tapped, when it differs from [url] ("Open original").
 */
data class LinkSheet(
    val title: String?,
    val unicodeTitle: String?,
    val url: String,
    val titleRange: IntRange?,
    val warnings: List<LinkWarning>,
    val original: String?,
) {
    val openable: Boolean get() = title != null
}

/** What a tap on a link does. */
sealed interface LinkTap {
    /** Open at once ("Confirm links" is off and nothing warns). */
    data class Open(val url: String) : LinkTap
    data class Sheet(val sheet: LinkSheet) : LinkTap
    data class Compose(val prefill: DraftPrefill) : LinkTap
}

/** [confirm] = Prefs.confirmLinks; links with warnings and links Plico does not open always show the sheet. */
fun linkTap(link: Link, confirm: Boolean): LinkTap = when (link) {
    is Link.Mail -> LinkTap.Compose(prefillOf(link.mailto))
    is Link.Web -> if (!confirm && link.warnings.isEmpty()) LinkTap.Open(link.url) else LinkTap.Sheet(linkSheet(link))
    is Link.Unsupported -> LinkTap.Sheet(LinkSheet(null, null, link.text, null, emptyList(), link.original))
}

fun linkSheet(link: Link.Web): LinkSheet {
    val title = link.domain ?: link.host
    val hostEnd = link.url.indexOf("://") + 3 + link.host.length
    val start = hostEnd - title.length
    val range = (start until hostEnd).takeIf { start >= 0 && link.url.regionMatches(start, title, 0, title.length) }
    return LinkSheet(
        title = title,
        unicodeTitle = Idn.toUnicode(title).takeIf { it != title },
        url = link.url,
        titleRange = range,
        warnings = link.warnings.sortedBy { it.ordinal },
        original = link.original,
    )
}

/** User text for a failed load or action; [ErrorText.signIn] offers "Sign in". */
fun errorText(error: Throwable): ErrorText = when (error) {
    is MailError.AuthRequired -> ErrorText(R.string.message_error_sign_in, signIn = true)
    is MailError.Tls -> ErrorText(R.string.ui_error_tls)
    is MailError.Network, is IOException -> ErrorText(R.string.message_error_network)
    is MailError.Throttled -> ErrorText(R.string.ui_error_busy)
    is MailError.Server -> ErrorText(R.string.message_error_server, error.message)
    is MailError.Setup -> ErrorText(R.string.message_error_setup, error.message)
    else -> ErrorText(R.string.message_error_generic)
}

/** Bidi embedding, override and isolate controls, and C0/C1 controls other than tab and line breaks. */
private val disguising =
    Regex("[\\u202A-\\u202E\\u2066-\\u2069" + "\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]")

/**
 * A sender-supplied name for display: controls that reorder or hide text become U+FFFD, so that
 * "invoice<U+202E>fdp.exe" cannot read as "invoiceexe.pdf".
 */
fun revealBidi(text: String): String = disguising.replace(text, "\uFFFD")

/** "PDF" for an attachment card: the file extension, else the MIME subtype. */
fun typeLabel(name: String, mime: String): String {
    val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
    val subtype = mime.substringAfter('/', "").substringBefore(';').substringAfterLast('+').substringAfterLast('.')
        .trim().takeIf { it.isNotEmpty() && it.length <= 12 && it != "octet-stream" }
    return (extension ?: subtype ?: "").uppercase()
}

/** About the file size of a part whose BODYSTRUCTURE size is [encodedSize]: base64 needs 4 bytes for 3. */
fun decodedSize(encodedSize: Long, encoding: String): Long =
    if (encoding.equals("base64", ignoreCase = true)) encodedSize * 3 / 4 else encodedSize

/** The Date header as the sender wrote it (time zone included), from a raw header block. */
fun senderDate(rawHeaders: String): String? =
    HeaderBlock.parse(rawHeaders.toByteArray(Charsets.UTF_8))["Date"]?.trim()?.takeIf { it.isNotEmpty() }

/** Content-IDs compare without angle brackets and case (DESIGN §11.2). */
fun sameContentId(a: String, b: String): Boolean =
    a.trim().removeSurrounding("<", ">").equals(b.trim().removeSurrounding("<", ">"), ignoreCase = true)
