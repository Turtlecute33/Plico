package app.plico.mail.mime

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** A file to attach; [bytes] are the final bytes (images already re-encoded by the app). */
class OutgoingAttachment(val name: String, val mimeType: String, val bytes: ByteArray)

/**
 * Builds an outgoing RFC 5322 message following DESIGN.md §10. [messageId] includes the angle brackets.
 * [date] is epoch milliseconds and is written in UTC.
 */
class MessageBuilder(
    val from: Address,
    val to: List<Address>,
    val cc: List<Address>,
    val bcc: List<Address>,
    val subject: String,
    val text: String,
    val messageId: String,
    val inReplyTo: String?,
    val references: List<String>,
    val attachments: List<OutgoingAttachment>,
    val date: Long,
) {
    /**
     * CRLF line endings. [includeBcc] only for drafts and the Riseup Sent copy, never for SMTP DATA.
     * The same input gives the same bytes, except for the multipart boundary.
     *
     * @throws IllegalArgumentException when an address is not a valid addr-spec or [messageId] is not one `<x@y>`.
     */
    fun build(includeBcc: Boolean): ByteArray {
        validate()
        val head = StringBuilder()
        head.field("Date", Dates.formatUtc(date))
        head.addressField("From", listOf(from))
        head.addressField("To", to)
        head.addressField("Cc", cc)
        if (includeBcc) head.addressField("Bcc", bcc)
        head.field("Subject", EncodedWords.encode(cleanText(subject)).split(' ').filter { it.isNotEmpty() })
        head.field("Message-ID", messageId)
        inReplyTo?.let(::messageIds)?.firstOrNull()?.let { head.field("In-Reply-To", it) }
        referenceIds().let { if (it.isNotEmpty()) head.field("References", it) }
        head.field("MIME-Version", "1.0")

        val (encoding, body) = textBody()
        val textHeaders = StringBuilder()
        textHeaders.field("Content-Type", "text/plain; charset=utf-8")
        textHeaders.field("Content-Transfer-Encoding", encoding)
        val out = ByteArrayOutputStream(sizeHint())
        if (attachments.isEmpty()) {
            out.text("$head$textHeaders$CRLF$body$CRLF")
            return out.toByteArray()
        }
        val boundary = newBoundary()
        // No Content-Transfer-Encoding for the multipart itself (7bit is implied), as Gmail and Thunderbird write it.
        head.field("Content-Type", listOf("multipart/mixed;", "boundary=\"$boundary\""))
        out.text("$head$CRLF--$boundary$CRLF$textHeaders$CRLF$body$CRLF")
        for (attachment in attachments) {
            out.text("--$boundary$CRLF${attachmentHeaders(attachment)}$CRLF")
            Base64.getMimeEncoder(BASE64_LINE, CRLF_BYTES).wrap(out).use { it.write(attachment.bytes) }
            out.text(CRLF)
        }
        out.text("--$boundary--$CRLF")
        return out.toByteArray()
    }

    private fun validate() {
        for ((field, list) in listOf("From" to listOf(from), "To" to to, "Cc" to cc, "Bcc" to bcc)) {
            require(list.all { AddrSpec.isValid(it.email) }) { "Invalid $field address" }
        }
        val valid = HeaderSafety.messageIds(HeaderSafety.clean(messageId)) == listOf(messageId)
        require(valid && messageId.toByteArray(Charsets.UTF_8).size <= MAX_ID) { "Invalid Message-ID" }
    }

    /** The first id and the last ten (`protocol PS23`). */
    private fun referenceIds(): List<String> {
        val ids = references.flatMap(::messageIds).distinct()
        return if (ids.size <= MAX_REFERENCES) ids else listOf(ids.first()) + ids.takeLast(MAX_REFERENCES - 1)
    }

    /** The text as CRLF-separated lines (without a final line break) and its transfer encoding. */
    private fun textBody(): Pair<String, String> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
        val plain = lines.all { line -> line.length <= MAX_LINE && line.all { it == '\t' || it in ' '..'~' } }
        return if (plain) {
            "7bit" to lines.joinToString(CRLF)
        } else {
            "quoted-printable" to lines.joinToString(CRLF, transform = ::quotedPrintable)
        }
    }

    /** Buffer capacity for the whole message, so that large attachments are not copied while it grows. */
    private fun sizeHint(): Int {
        val estimate = 16_384L + text.length * 3L + attachments.sumOf { it.bytes.size * 137L / 100 + 1024 }
        return estimate.coerceAtMost(Int.MAX_VALUE - 8L).toInt()
    }

    companion object {
        private val random = SecureRandom()

        /** `<UUIDv4-UPPER-CASE@domain>` */
        fun newMessageId(domain: String): String {
            require(AddrSpec.isValid("x@$domain")) { "Invalid domain" }
            return "<" + UUID.randomUUID().toString().uppercase(Locale.ROOT) + "@" + domain + ">"
        }

        /** `----` and 30 base36 characters from [SecureRandom], as Thunderbird for Android writes them. */
        private fun newBoundary(): String =
            "----" + String(CharArray(BOUNDARY_CHARS) { BASE36[random.nextInt(BASE36.length)] })
    }
}

/** RFC 5322 §2.1.1: lines SHOULD have at most 78 characters and MUST have at most 998. */
private const val SOFT_LIMIT = 78
private const val MAX_LINE = 998

/** Longest ASCII word kept in a subject or display name; quoted with every character escaped it still fits a line. */
private const val MAX_WORD = 400

/** Longest Message-ID kept: `In-Reply-To: <id>` must fit in [MAX_LINE]. */
private const val MAX_ID = MAX_LINE - 13
private const val MAX_REFERENCES = 11

/** RFC 6838 §4.2: type and subtype names have at most 127 characters. */
private const val MAX_TYPE_NAME = 127
private const val QP_LINE = 76
private const val BASE64_LINE = 76
private const val BOUNDARY_CHARS = 30
private const val BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val HEX = "0123456789ABCDEF"
private const val TSPECIALS = "()<>@,;:\\\"/[]?="

/** RFC 5987 attr-char punctuation; ASCII letters and digits are attribute characters too. */
private const val ATTRIBUTE_CHARS = "!#\$&+-.^_`|~"
private const val CRLF = "\r\n"
private val CRLF_BYTES = CRLF.toByteArray(Charsets.US_ASCII)

private fun ByteArrayOutputStream.text(s: String) = write(s.toByteArray(Charsets.UTF_8))

/**
 * Appends `name: items` and CRLF, folded so that lines stay within [SOFT_LIMIT] where possible. An item stays
 * whole when it fits on a line, else it breaks at its spaces. Continuation lines start with a space.
 */
private fun StringBuilder.field(name: String, items: List<String>) {
    var lineStart = length
    var empty = true
    append(name).append(':')
    fun put(word: String) {
        val overflow = length - lineStart + 1 + word.length > SOFT_LIMIT
        // A fold right after the name only helps when the word then fits.
        if (overflow && (!empty || 1 + word.length <= SOFT_LIMIT)) {
            append(CRLF)
            lineStart = length
        }
        append(' ').append(word)
        empty = false
    }
    for (item in items) {
        if (1 + item.length <= SOFT_LIMIT || ' ' !in item) put(item) else item.split(' ').forEach(::put)
    }
    append(CRLF)
}

private fun StringBuilder.field(name: String, value: String) = field(name, listOf(value))

/** Address list folded at `, ` boundaries; names pass [cleanText]. */
private fun StringBuilder.addressField(name: String, list: List<Address>) {
    if (list.isEmpty()) return
    val items = list.map { Addresses.format(listOf(Address(it.name?.let(::cleanText), it.email))) }
    field(name, items.mapIndexed { i, item -> if (i < items.lastIndex) "$item," else item })
}

/**
 * [HeaderSafety.clean]; in ASCII text, which is not encoded and so folds only at spaces, words too long for any
 * header line are cut (only hostile input has them).
 */
private fun cleanText(value: String): String {
    val clean = HeaderSafety.clean(value)
    return if (clean.any { it > '~' }) clean else clean.split(' ').joinToString(" ") { it.take(MAX_WORD) }
}

/** Ids from an In-Reply-To or References value that are printable ASCII and fit on one header line. */
private fun messageIds(value: String): List<String> = HeaderSafety.messageIds(HeaderSafety.clean(value))
    .filter { id -> id.length <= MAX_ID && id.all { it in '!'..'~' } }

/** RFC 2045 §6.7 quoted-printable of one line: UTF-8 bytes, soft line breaks keep lines at most 76 characters. */
private fun quotedPrintable(line: String): String {
    val bytes = line.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size + bytes.size / 2)
    var column = 0
    bytes.forEachIndexed { i, byte ->
        val b = byte.toInt() and 0xFF
        val last = i == bytes.lastIndex
        val literal = b in 33..126 && b != '='.code || (b == ' '.code || b == '\t'.code) && !last
        val width = if (literal) 1 else 3
        // A line that goes on needs room for the `=` of its soft break.
        if (column + width > if (last) QP_LINE else QP_LINE - 1) {
            out.append('=').append(CRLF)
            column = 0
        }
        if (literal) out.append(b.toChar()) else out.append('=').append(HEX[b shr 4]).append(HEX[b and 0xF])
        column += width
    }
    return out.toString()
}

/** Content-Type, Content-Disposition (`privacy PS3`) and Content-Transfer-Encoding lines of one attachment. */
private fun attachmentHeaders(attachment: OutgoingAttachment): String {
    val name = HeaderSafety.clean(attachment.name).ifEmpty { "attachment" }
    val ascii = name.all { it in ' '..'~' }
    val quoted = name.map { if (it == '"' || it == '\\') "\\$it" else it.toString() }
    val nameParam = if (ascii) parameter("name", quoted, extended = false) else encodedParameter("name", name)
    val filenameParam = if (ascii) {
        parameter("filename", quoted, extended = false)
    } else {
        parameter("filename", percentEncoded(name), extended = true)
    }
    val headers = StringBuilder()
    headers.field("Content-Type", listOf("${mimeType(attachment.mimeType)};") + nameParam)
    headers.field("Content-Disposition", listOf("attachment;") + filenameParam)
    headers.field("Content-Transfer-Encoding", "base64")
    return headers.toString()
}

/**
 * `type/subtype` of RFC 2045 tokens of at most [MAX_TYPE_NAME] characters each, lower-case, so that the
 * unfoldable word stays far below 998 octets. Composite types may not be base64-encoded (RFC 2046).
 */
private fun mimeType(value: String): String {
    val type = HeaderSafety.clean(value).lowercase(Locale.ROOT)
    val main = type.substringBefore('/', "")
    val sub = type.substringAfter('/', "")
    val valid = main.length <= MAX_TYPE_NAME && sub.length <= MAX_TYPE_NAME &&
        isToken(main) && isToken(sub) && main != "multipart" && main != "message"
    return if (valid) type else "application/octet-stream"
}

private fun isToken(s: String) = s.isNotEmpty() && s.all { it in '!'..'~' && it !in TSPECIALS }

/**
 * `key="value"`, or `key*=utf-8''value` when [extended] ([units] percent-encoded), as one word; RFC 2231
 * sections `key*0…;`, `key*1…` when that word would not fit on a line. A unit is never split.
 */
private fun parameter(key: String, units: List<String>, extended: Boolean): List<String> {
    fun word(section: Int?, value: CharSequence): String {
        val name = if (section == null) key else "$key*$section"
        return if (extended) "$name*=$value" else "$name=\"$value\""
    }
    val prefix = if (extended) "utf-8''" else ""
    val whole = word(null, prefix + units.joinToString(""))
    if (1 + whole.length <= SOFT_LIMIT) return listOf(whole)
    val sections = ArrayList<String>()
    val value = StringBuilder(prefix)
    for (unit in units) {
        // A section line holds a leading space, the word and `;`.
        if (value.isNotEmpty() && 2 + word(sections.size, "").length + value.length + unit.length > SOFT_LIMIT) {
            sections += word(sections.size, value) + ";"
            value.setLength(0)
        }
        value.append(unit)
    }
    sections += word(sections.size, value)
    return sections
}

/** `key="=?UTF-8?B?…?= =?UTF-8?B?…?="`: the RFC 2047 form that old clients read; it folds between the words. */
private fun encodedParameter(key: String, value: String): List<String> {
    val words = EncodedWords.encode(value).split(' ')
    return words.mapIndexed { i, w -> (if (i == 0) "$key=\"" else "") + w + if (i == words.lastIndex) "\"" else "" }
}

/** RFC 2231 units: an attribute character as it is, any other character as its percent-encoded UTF-8 bytes. */
private fun percentEncoded(value: String): List<String> {
    val units = ArrayList<String>(value.length)
    var i = 0
    while (i < value.length) {
        val end = i + Character.charCount(value.codePointAt(i))
        val c = value[i]
        units += if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ATTRIBUTE_CHARS) {
            c.toString()
        } else {
            value.substring(i, end).toByteArray(Charsets.UTF_8)
                .joinToString("") { b -> "%" + HEX[(b.toInt() shr 4) and 0xF] + HEX[b.toInt() and 0xF] }
        }
        i = end
    }
    return units
}
