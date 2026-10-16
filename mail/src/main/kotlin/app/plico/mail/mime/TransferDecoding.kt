package app.plico.mail.mime

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Content-Transfer-Encoding and charset handling. */
object TransferDecoding {
    private val windows1252: Charset = Charset.forName("windows-1252")

    /** Labels that Java does not know or that senders use for a wider charset. */
    private val aliases = mapOf(
        "utf8" to "UTF-8",
        "ks_c_5601-1987" to "EUC-KR",
        "iso-8859-8-i" to "ISO-8859-8",
        "gb2312" to "GBK",
    )

    /** base64 / quoted-printable / 7bit / 8bit / binary (unknown = as-is). Lenient. */
    fun decode(bytes: ByteArray, encoding: String): ByteArray {
        val decoder = decoderFor(encoding) ?: return bytes
        val out = Sink(bytes.size)
        for (b in bytes) decoder.put(b.toInt() and 0xFF, out)
        decoder.finish(out)
        return out.buf.copyOf(out.size)
    }

    /** Streaming variant of [decode], for attachments. Closing the result closes [input]. */
    fun decodingStream(input: InputStream, encoding: String): InputStream {
        val decoder = decoderFor(encoding) ?: return input
        return DecodingStream(input, decoder)
    }

    /**
     * Decodes text with the charset (aliases handled); unknown -> UTF-8; invalid UTF-8 -> windows-1252.
     * US-ASCII is read as UTF-8, its superset; ISO-8859-1 as windows-1252, its superset. A leading BOM is removed.
     */
    fun text(bytes: ByteArray, charset: String?): String {
        val cs = resolve(charset)
        if (cs != null) return String(bytes, cs).removePrefix(BOM)
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val start = if (bom) 3 else 0
        return utf8OrNull(bytes, start, bytes.size - start) ?: String(bytes, start, bytes.size - start, windows1252)
    }

    /** Strict UTF-8 (no malformed or overlong sequences), else null. */
    internal fun utf8OrNull(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** Null means "UTF-8 with the windows-1252 fallback". */
    private fun resolve(label: String?): Charset? {
        val key = label?.trim()?.trim('"', '\'')?.lowercase(Locale.ROOT).orEmpty()
        if (key.isEmpty()) return null
        val cs = runCatching { Charset.forName(aliases[key] ?: key) }.getOrNull() ?: return null
        return when (cs) {
            Charsets.UTF_8, Charsets.US_ASCII -> null
            Charsets.ISO_8859_1 -> windows1252
            else -> cs
        }
    }

    private fun decoderFor(encoding: String): Decoder? = when (encoding.trim().lowercase(Locale.ROOT)) {
        "base64" -> Base64Decoder()
        "quoted-printable" -> QpDecoder()
        else -> null
    }

    private const val BOM = "\uFEFF"
}

/** A push decoder: one input byte at a time, output into a [Sink]. */
private interface Decoder {
    fun put(b: Int, out: Sink)
    fun finish(out: Sink)
}

/** Growable byte buffer with a read position. */
private class Sink(capacity: Int) {
    var buf = ByteArray(maxOf(capacity, 16))
    var size = 0
    var pos = 0

    fun put(b: Int) {
        if (size == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = b.toByte()
    }
}

/** Skips every byte outside the alphabet; `=` and the end flush a partial quantum, so missing padding is fine. */
private class Base64Decoder : Decoder {
    private var bits = 0
    private var count = 0

    override fun put(b: Int, out: Sink) {
        val v = if (b < 128) ALPHABET[b] else -1
        if (v >= 0) {
            bits = bits shl 6 or v
            if (++count == 4) {
                out.put(bits shr 16)
                out.put(bits shr 8)
                out.put(bits)
                bits = 0
                count = 0
            }
        } else if (b == '='.code) {
            finish(out)
        }
    }

    override fun finish(out: Sink) {
        if (count >= 2) out.put(bits shr (count * 6 - 8))
        if (count == 3) out.put(bits shr 2)
        bits = 0
        count = 0
    }

    private companion object {
        val ALPHABET = IntArray(128) { -1 }.also { table ->
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> table[c.code] = i }
        }
    }
}

/**
 * RFC 2045 quoted-printable, lenient: soft breaks `=CRLF`, `=LF` and `=` + trailing blanks + line break;
 * `=XX` in either case; any other `=` is kept literally. A final `=` is a soft break.
 */
private class QpDecoder : Decoder {
    private var state = TEXT
    private var high = 0
    private val blanks = ByteArray(MAX_BLANKS)
    private var blankCount = 0

    override fun put(b: Int, out: Sink) {
        when (state) {
            TEXT -> if (b == '='.code) state = EQUALS else out.put(b)
            EQUALS -> when {
                hex(b) >= 0 -> {
                    high = b
                    state = HEX
                }
                b == '\r'.code -> state = CR
                b == '\n'.code -> state = TEXT
                isBlank(b) -> {
                    blanks[0] = b.toByte()
                    blankCount = 1
                    state = BLANKS
                }
                else -> {
                    out.put('='.code)
                    asText(b, out)
                }
            }
            HEX -> {
                val low = hex(b)
                if (low >= 0) {
                    out.put(hex(high) shl 4 or low)
                    state = TEXT
                } else {
                    out.put('='.code)
                    out.put(high)
                    asText(b, out)
                }
            }
            CR -> if (b == '\n'.code) state = TEXT else asText(b, out)
            BLANKS -> when {
                b == '\r'.code -> state = CR
                b == '\n'.code -> state = TEXT
                isBlank(b) && blankCount < MAX_BLANKS -> blanks[blankCount++] = b.toByte()
                else -> {
                    out.put('='.code)
                    for (i in 0 until blankCount) out.put(blanks[i].toInt())
                    asText(b, out)
                }
            }
        }
    }

    override fun finish(out: Sink) {
        if (state == HEX) {
            out.put('='.code)
            out.put(high)
        }
        state = TEXT
    }

    private fun asText(b: Int, out: Sink) {
        state = TEXT
        put(b, out)
    }

    private fun isBlank(b: Int) = b == ' '.code || b == '\t'.code

    private fun hex(b: Int): Int = when (b) {
        in '0'.code..'9'.code -> b - '0'.code
        in 'A'.code..'F'.code -> b - 'A'.code + 10
        in 'a'.code..'f'.code -> b - 'a'.code + 10
        else -> -1
    }

    private companion object {
        const val TEXT = 0
        const val EQUALS = 1
        const val HEX = 2
        const val CR = 3
        const val BLANKS = 4
        const val MAX_BLANKS = 80
    }
}

private class DecodingStream(private val input: InputStream, private val decoder: Decoder) : InputStream() {
    private val raw = ByteArray(8192)
    private val out = Sink(8192)
    private var eof = false

    override fun read(): Int = if (fill()) out.buf[out.pos++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, out.size - out.pos)
        System.arraycopy(out.buf, out.pos, b, off, n)
        out.pos += n
        return n
    }

    override fun available(): Int = out.size - out.pos

    override fun close() = input.close()

    /** True when decoded bytes are ready, false at the end of the input. */
    private fun fill(): Boolean {
        while (out.pos == out.size) {
            if (eof) return false
            out.pos = 0
            out.size = 0
            val n = input.read(raw)
            if (n < 0) {
                decoder.finish(out)
                eof = true
            } else {
                for (i in 0 until n) decoder.put(raw[i].toInt() and 0xFF, out)
            }
        }
        return true
    }
}
