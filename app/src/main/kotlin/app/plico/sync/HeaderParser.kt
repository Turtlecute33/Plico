package app.plico.sync

import app.plico.data.Auth
import app.plico.data.NewMessage
import app.plico.data.ServerFlags
import app.plico.html.AuthResults
import app.plico.mail.imap.FetchItem
import app.plico.mail.mime.Addresses
import app.plico.mail.mime.ContentType
import app.plico.mail.mime.Dates
import app.plico.mail.mime.EncodedWords
import app.plico.mail.mime.HeaderBlock
import app.plico.mail.mime.HeaderSafety

/** Turns header FETCH responses into message rows (DESIGN.md §7.2 step 4, §5). Pure. */
internal object HeaderParser {
    /** The header fields stored for each message. */
    val FIELDS = listOf(
        "DATE", "FROM", "TO", "CC", "BCC", "REPLY-TO", "SUBJECT", "MESSAGE-ID", "IN-REPLY-TO", "REFERENCES",
        "CONTENT-TYPE", "LIST-UNSUBSCRIBE", "LIST-UNSUBSCRIBE-POST", "AUTHENTICATION-RESULTS",
    )

    private const val SECTION = "HEADER.FIELDS"
    private const val ONE_CLICK = "List-Unsubscribe=One-Click"
    private const val DELETED = "\\Deleted"

    /**
     * FETCH items for new rows. [modSeq] only with CONDSTORE; [gmMsgId] and [gmLabels] only on Gmail (labels only
     * in All Mail).
     */
    fun items(modSeq: Boolean, gmMsgId: Boolean, gmLabels: Boolean): String = buildString {
        append("UID FLAGS INTERNALDATE RFC822.SIZE")
        if (modSeq) append(" MODSEQ")
        append(" BODY.PEEK[").append(SECTION).append(FIELDS.joinToString(" ", " (", ")")).append(']')
        if (gmMsgId) append(" X-GM-MSGID")
        if (gmLabels) append(" X-GM-LABELS")
    }

    /** True when [item] has the header section; unsolicited FETCH responses (flag updates) have none (DESIGN §4.3). */
    fun hasHeaderFields(item: FetchItem): Boolean = item.sections.keys.any { it.startsWith(SECTION, ignoreCase = true) }

    /** FETCH items for a flags update. */
    fun flagItems(gmLabels: Boolean): String = if (gmLabels) "UID FLAGS X-GM-LABELS" else "UID FLAGS"

    /**
     * The row for [item]. The date is INTERNALDATE (protocol PS22); the Date header is only a fallback for a
     * server that sends none. [gmail] enables the `Authentication-Results` check (privacy PS4), which reads only
     * the first (topmost) header. [labels] are the label-view ids from X-GM-LABELS, null on other servers. The
     * server flag `\Deleted` marks the row deleted (not shown) on every server except Gmail (DESIGN §7.2).
     */
    fun newMessage(
        item: FetchItem,
        accountId: Long,
        folderId: Long,
        gmail: Boolean,
        labels: Set<Long>?,
        notified: Boolean,
        now: Long,
    ): NewMessage {
        val headers = HeaderBlock.parse(headerBytes(item))
        val from = headers["From"]?.let(Addresses::parseList)?.firstOrNull()
        return NewMessage(
            accountId = accountId,
            folderId = folderId,
            uid = item.uid,
            date = item.internalDate ?: headers["Date"]?.let(Dates::parse) ?: now,
            gmMsgId = item.gmMsgId,
            messageId = headers["Message-ID"]?.let(HeaderSafety::messageIds)?.firstOrNull(),
            inReplyTo = ids(headers["In-Reply-To"]),
            refs = ids(headers["References"]),
            subject = headers["Subject"]?.let { HeaderSafety.clean(EncodedWords.decode(it)) }.orEmpty(),
            fromName = from?.name.orEmpty(),
            fromEmail = from?.email?.let(HeaderSafety::clean).orEmpty(),
            toList = addresses(headers["To"]),
            ccList = addresses(headers["Cc"]),
            bccList = addresses(headers["Bcc"]),
            replyTo = addresses(headers["Reply-To"]),
            size = item.size ?: 0,
            seen = item.has("\\Seen"),
            flagged = item.has("\\Flagged"),
            answered = item.has("\\Answered"),
            draft = item.has("\\Draft"),
            modSeq = item.modSeq ?: 0,
            attachments = if (isMixed(headers["Content-Type"])) 1 else 0,
            auth = if (gmail) AuthResults.parse(headers["Authentication-Results"]) else Auth.UNKNOWN,
            unsubscribe = headers["List-Unsubscribe"]?.let(HeaderSafety::clean)?.takeIf { it.isNotEmpty() },
            unsubscribePost = headers["List-Unsubscribe-Post"]?.trim().equals(ONE_CLICK, ignoreCase = true),
            notified = notified,
            labels = labels,
            deleted = !gmail && item.has(DELETED),
        )
    }

    /** Server flags of an existing row; null when the response has no FLAGS. [gmail]: `\Deleted` is ignored. */
    fun serverFlags(item: FetchItem, labels: Set<Long>?, gmail: Boolean): ServerFlags? {
        if (item.flags == null) return null
        return ServerFlags(
            uid = item.uid,
            seen = item.has("\\Seen"),
            flagged = item.has("\\Flagged"),
            answered = item.has("\\Answered"),
            draft = item.has("\\Draft"),
            modSeq = item.modSeq,
            labels = labels,
            deleted = !gmail && item.has(DELETED),
        )
    }

    private fun headerBytes(item: FetchItem): ByteArray =
        item.sections.entries.firstOrNull { it.key.startsWith(SECTION, ignoreCase = true) }?.value ?: ByteArray(0)

    private fun FetchItem.has(flag: String) = flags.orEmpty().any { it.equals(flag, ignoreCase = true) }

    /** Stored `*_list` form: decoded, so that [Addresses.parseList] reads it back. */
    private fun addresses(value: String?): String =
        value?.let { Addresses.format(Addresses.parseList(it), encode = false) }.orEmpty()

    private fun ids(value: String?): String? =
        value?.let(HeaderSafety::messageIds)?.takeIf { it.isNotEmpty() }?.joinToString(" ")

    /** A first guess until the body is fetched: mail with attachments is nearly always multipart/mixed. */
    private fun isMixed(value: String?): Boolean = value?.let(ContentType::parse)?.mimeType == "multipart/mixed"
}
