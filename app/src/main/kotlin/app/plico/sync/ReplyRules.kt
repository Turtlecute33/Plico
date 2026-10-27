package app.plico.sync

import app.plico.mail.mime.Address
import app.plico.mail.mime.Addresses
import app.plico.mail.mime.HeaderSafety
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reply, reply-all and forward rules (DESIGN.md §10 and §13 Compose, simplicity EM16), and the text of new
 * drafts. Pure; dates are epoch ms and are written in UTC, so a quote does not reveal the user's time zone.
 */
internal object ReplyRules {
    /** The message being answered. [fromSelf]: the user sent it (a message in Sent): a reply goes to its To. */
    data class Source(
        val from: Address?,
        val replyTo: List<Address>,
        val to: List<Address>,
        val cc: List<Address>,
        val fromSelf: Boolean,
    )

    data class Recipients(val to: List<Address>, val cc: List<Address>)

    private val replyPrefix = Regex("""^(?:\s*(?:re|aw|sv|antw)\s*(?:\[\d+\])?\s*:)+\s*""", RegexOption.IGNORE_CASE)
    private val forwardPrefix = Regex("""^(?:\s*(?:fwd?|wg)\s*(?:\[\d+\])?\s*:)+\s*""", RegexOption.IGNORE_CASE)
    private val minute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** The first line of a forwarded message in a draft. */
    const val FORWARD_HEADER = "---------- Forwarded message ----------"

    /** The most characters of the source that a reply or a forward quotes (PLICO-PERF-007). */
    const val MAX_QUOTE_CHARS = 32_000

    /**
     * Reply: Reply-To, else From; for the user's own message its To. Reply all adds To and Cc of the source as
     * Cc. Every own address ([own], lower-case) is removed, unless that would leave no recipient (a note to
     * oneself). Each address appears once.
     */
    fun recipients(source: Source, own: Set<String>, all: Boolean): Recipients {
        val primary = if (source.fromSelf) source.to else source.replyTo.ifEmpty { listOfNotNull(source.from) }
        val to = primary.filterNot { it.key in own }.unique().ifEmpty { primary.unique() }
        if (!all) return Recipients(to, emptyList())
        val taken = to.mapTo(HashSet()) { it.key }
        val cc = (source.to + source.cc).filterNot { it.key in own || it.key in taken }.unique()
        return Recipients(to, cc)
    }

    /** The From address: the account address or an identity that the source was sent to, else the account address. */
    fun from(source: Source, accountEmail: String, identities: List<String>): String {
        val addressed = (source.to + source.cc).mapTo(HashSet()) { it.key }
        return (listOf(accountEmail) + identities).firstOrNull { it.lowercase(Locale.ROOT) in addressed } ?: accountEmail
    }

    /** "Re: " once, whatever reply prefixes the subject already has. */
    fun replySubject(subject: String): String = ("Re: " + subject.replace(replyPrefix, "").trim()).trimEnd()

    /** "Fwd: " once, whatever forward prefixes the subject already has. */
    fun forwardSubject(subject: String): String = ("Fwd: " + subject.replace(forwardPrefix, "").trim()).trimEnd()

    /** The parent's References (or its In-Reply-To), then its Message-ID; null when there is none. */
    fun references(refs: String?, inReplyTo: String?, messageId: String?): String? {
        val parent = HeaderSafety.messageIds(refs.orEmpty()).ifEmpty { HeaderSafety.messageIds(inReplyTo.orEmpty()) }
        val own = HeaderSafety.messageIds(messageId.orEmpty()).take(1)
        return (parent + own).distinct().joinToString(" ").ifEmpty { null }
    }

    /** "On 2026-10-16 09:14 UTC, Name wrote:" */
    fun attribution(date: Long, name: String): String = "On ${minute.format(Instant.ofEpochMilli(date))} UTC, $name wrote:"

    /** [text] with "> " before each line; quoted lines get one more ">"; trailing blank lines are dropped. */
    fun quote(text: String): String = text.replace("\r\n", "\n").trimEnd().lines().joinToString("\n") { line ->
        when {
            line.isEmpty() -> ">"
            line.startsWith(">") -> ">$line"
            else -> "> $line"
        }
    }

    /**
     * [text], cut after [MAX_QUOTE_CHARS] and then ended with a "[…]" line. The quote is in the editable body,
     * and a long body makes each keystroke slow.
     */
    fun capQuote(text: String): String {
        if (text.length <= MAX_QUOTE_CHARS) return text
        val end = if (Character.isHighSurrogate(text[MAX_QUOTE_CHARS - 1])) MAX_QUOTE_CHARS - 1 else MAX_QUOTE_CHARS
        return text.substring(0, end).trimEnd() + "\n[…]"
    }

    /** The body of a new draft: [text], then the signature after "-- ". */
    fun newBody(text: String, signature: String): String =
        if (signature.isBlank()) text else "$text\n\n${signatureBlock(signature)}"

    /** Room for the answer, the signature, then the attribution and the quoted [text]. */
    fun replyBody(signature: String, attribution: String, text: String): String = buildString {
        append("\n\n")
        if (signature.isNotBlank()) append(signatureBlock(signature)).append("\n\n")
        append(attribution)
        if (text.isNotBlank()) append('\n').append(quote(capQuote(text)))
    }

    /** Room for a note, the signature, then the original headers and [text]. */
    fun forwardBody(
        signature: String,
        from: Address?,
        date: Long,
        subject: String,
        to: List<Address>,
        cc: List<Address>,
        text: String,
    ): String = buildString {
        append("\n\n")
        if (signature.isNotBlank()) append(signatureBlock(signature)).append("\n\n")
        append(FORWARD_HEADER).append('\n')
        from?.let { append("From: ").append(Addresses.format(listOf(it), encode = false)).append('\n') }
        append("Date: ").append(minute.format(Instant.ofEpochMilli(date))).append(" UTC\n")
        append("Subject: ").append(subject).append('\n')
        if (to.isNotEmpty()) append("To: ").append(Addresses.format(to, encode = false)).append('\n')
        if (cc.isNotEmpty()) append("Cc: ").append(Addresses.format(cc, encode = false)).append('\n')
        append('\n').append(capQuote(text.replace("\r\n", "\n").trimEnd()))
    }

    private fun signatureBlock(signature: String) = "-- \n" + signature.trimEnd()

    private val Address.key: String get() = email.lowercase(Locale.ROOT)

    private fun List<Address>.unique() = distinctBy { it.key }
}
