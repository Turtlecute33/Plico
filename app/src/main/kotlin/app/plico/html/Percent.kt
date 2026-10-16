package app.plico.html

import java.io.ByteArrayOutputStream

/** Percent-encoding (RFC 3986) with UTF-8. */
internal object Percent {
    private const val HEX = "0123456789ABCDEF"

    /** Decodes `%XX` sequences as UTF-8; malformed sequences stay as written and `+` stays `+`. */
    fun decode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hi = if (c == '%' && i + 2 < s.length) hex(s[i + 1]) else -1
            val lo = if (hi >= 0) hex(s[i + 2]) else -1
            if (lo >= 0) {
                out.write(hi * 16 + lo)
                i += 3
            } else {
                val end = if (c.isHighSurrogate() && i + 1 < s.length) i + 2 else i + 1
                out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    /** Encodes every UTF-8 byte of [s] whose character is not [keep] as `%XX`. */
    fun encode(s: String, keep: (Char) -> Boolean): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c < '\u0080' && keep(c)) {
                out.append(c)
                i++
                continue
            }
            val end = if (c.isHighSurrogate() && i + 1 < s.length) i + 2 else i + 1
            for (b in s.substring(i, end).toByteArray(Charsets.UTF_8)) {
                val v = b.toInt() and 0xFF
                out.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
            }
            i = end
        }
        return out.toString()
    }

    /** RFC 3986 unreserved characters. */
    fun isUnreserved(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~"

    fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
