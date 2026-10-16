package app.plico.mail.mime

import java.io.ByteArrayOutputStream
import java.util.Base64

/** RFC 2047 encoded words. */
object EncodedWords {
    /** `=?charset[*lang]?B|Q?text?=`. Spaces in the text are tolerated (broken mailers). */
    private val word = Regex("=\\?([^?\\s*]+)(?:\\*[^?\\s]*)?\\?([bBqQ])\\?([^?]*)\\?=")

    /** UTF-8 bytes per word: base64 of 45 bytes is 60 chars, plus 12 chars of `=?UTF-8?B?` and `?=`. */
    private const val MAX_WORD_BYTES = 45

    /**
     * Decodes every encoded word (B and Q), also inside quoted strings; whitespace between adjacent encoded
     * words is dropped. Adjacent words in the same charset are decoded together, so a character split across
     * two words survives. Malformed words stay as they are.
     */
    fun decode(value: String): String {
        if (!value.contains("=?")) return value
        val out = StringBuilder(value.length)
        val run = ByteArrayOutputStream()
        var runCharset: String? = null
        var last = 0
        for (m in word.findAll(value)) {
            val gap = value.substring(last, m.range.first)
            val adjacent = runCharset != null && gap.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
            val charset = m.groupValues[1]
            if (!adjacent || !charset.equals(runCharset, ignoreCase = true)) {
                if (runCharset != null) out.append(TransferDecoding.text(run.toByteArray(), runCharset))
                run.reset()
                if (!adjacent) out.append(gap)
            }
            run.write(bytes(m.groupValues[2], m.groupValues[3]))
            runCharset = charset
            last = m.range.last + 1
        }
        if (runCharset != null) out.append(TransferDecoding.text(run.toByteArray(), runCharset))
        return out.append(value, last, value.length).toString()
    }

    /**
     * Returns [value] unchanged when it is printable ASCII, else UTF-8 B-encoded words of at most 75 chars,
     * separated by single spaces. A word never splits a UTF-8 sequence or a surrogate pair.
     */
    fun encode(value: String): String {
        if (value.all { it in ' '..'~' }) return value
        val words = ArrayList<String>()
        val chunk = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val next = i + Character.charCount(value.codePointAt(i))
            val bytes = value.substring(i, next).toByteArray(Charsets.UTF_8)
            if (chunk.size() + bytes.size > MAX_WORD_BYTES) {
                words += word(chunk.toByteArray())
                chunk.reset()
            }
            chunk.write(bytes)
            i = next
        }
        if (chunk.size() > 0) words += word(chunk.toByteArray())
        return words.joinToString(" ")
    }

    /** End index of the encoded word that starts at [start], or -1. */
    internal fun wordEnd(value: String, start: Int): Int = word.matchAt(value, start)?.range?.last?.plus(1) ?: -1

    private fun bytes(encoding: String, text: String): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        return if (encoding.equals("B", ignoreCase = true)) {
            TransferDecoding.decode(raw, "base64")
        } else {
            for (i in raw.indices) if (raw[i] == '_'.code.toByte()) raw[i] = ' '.code.toByte()
            TransferDecoding.decode(raw, "quoted-printable")
        }
    }

    private fun word(bytes: ByteArray) = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(bytes) + "?="
}
