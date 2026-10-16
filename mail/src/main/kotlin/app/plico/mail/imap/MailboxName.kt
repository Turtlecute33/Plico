package app.plico.mail.imap

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** RFC 3501 §5.1.3 modified UTF-7 for mailbox names and Gmail labels. */
object MailboxName {
    fun encode(name: String): String {
        val out = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val c = name[i]
            when {
                c == '&' -> { out.append("&-"); i++ }
                c in PRINTABLE -> { out.append(c); i++ }
                else -> {
                    val start = i
                    while (i < name.length && name[i] !in PRINTABLE) i++
                    val utf16 = name.substring(start, i).toByteArray(Charsets.UTF_16BE)
                    out.append('&').append(ENCODER.encodeToString(utf16).replace('/', ',')).append('-')
                }
            }
        }
        return out.toString()
    }

    /** Lenient: invalid sequences are kept as they are. */
    fun decode(wire: String): String {
        val out = StringBuilder(wire.length)
        var i = 0
        while (i < wire.length) {
            val amp = wire.indexOf('&', i)
            val dash = if (amp < 0) -1 else wire.indexOf('-', amp + 1)
            if (dash < 0) {
                out.append(wire, i, wire.length)
                break
            }
            out.append(wire, i, amp)
            val chunk = wire.substring(amp + 1, dash)
            out.append(if (chunk.isEmpty()) "&" else decodeChunk(chunk) ?: wire.substring(amp, dash + 1))
            i = dash + 1
        }
        return out.toString()
    }

    private fun decodeChunk(chunk: String): String? = try {
        val bytes = Base64.getDecoder().decode(chunk.replace(',', '/'))
        if (bytes.isEmpty() || bytes.size % 2 != 0) null
        else Charsets.UTF_16BE.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: CharacterCodingException) {
        null
    }

    private val PRINTABLE = ' '..'~'
    private val ENCODER = Base64.getEncoder().withoutPadding()
}
