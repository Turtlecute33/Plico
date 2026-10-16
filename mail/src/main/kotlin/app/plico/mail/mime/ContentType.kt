package app.plico.mail.mime

import java.io.ByteArrayOutputStream
import java.util.Locale

/** [type] and [subtype] are lower-case; parameter names are lower-case; values are decoded (RFC 2231 and 2047). */
data class ContentTypeValue(val type: String, val subtype: String, val params: Map<String, String>) {
    val mimeType: String get() = "$type/$subtype"
}

object ContentType {
    private const val TSPECIALS = "()<>@,;:\\\"/[]?="

    /** `name*` (extended), `name*0`, `name*1*`, ...: base name, section index, encoded flag. */
    private val section = Regex("(.+?)(?:\\*(\\d{1,4}))?(\\*)?")

    /**
     * Parses `text/plain; charset="utf-8"` and `Content-Disposition` style values (then [ContentTypeValue.type]
     * holds the disposition and subtype is empty). A missing or broken type gives `text/plain`.
     */
    fun parse(value: String): ContentTypeValue {
        val parts = split(value)
        val params = decodeParams(parts.drop(1).mapNotNull(::param))
        val main = parts[0].trim().lowercase(Locale.ROOT)
        val slash = main.indexOf('/')
        val type = if (slash < 0) main.takeWhile { it > ' ' } else main.substring(0, slash).trim()
        val subtype = if (slash < 0) "" else main.substring(slash + 1).trim().takeWhile { it > ' ' }
        val valid = isToken(type) && (slash < 0 || isToken(subtype))
        return if (valid) ContentTypeValue(type, subtype, params) else ContentTypeValue("text", "plain", params)
    }

    /**
     * Decodes RFC 2231 parameters (`name*0*=...`, `name*=utf-8''...`) from raw name/value pairs. Names lower-case.
     * Values without RFC 2231 encoding are RFC 2047-decoded. An RFC 2231 form wins over a plain one of the
     * same name; otherwise the first occurrence wins.
     */
    fun decodeParams(raw: List<Pair<String, String>>): Map<String, String> {
        val plain = HashMap<String, String>()
        val extended = HashMap<String, String>()
        val sections = HashMap<String, MutableMap<Int, Segment>>()
        val names = LinkedHashSet<String>()
        for ((rawName, value) in raw) {
            val m = section.matchEntire(rawName.trim().lowercase(Locale.ROOT)) ?: continue
            val (name, index, star) = m.destructured
            val encoded = star.isNotEmpty()
            names += name
            when {
                index.isNotEmpty() -> sections.getOrPut(name) { HashMap() }.putIfAbsent(index.toInt(), Segment(encoded, value))
                encoded -> extended.putIfAbsent(name, value)
                else -> plain.putIfAbsent(name, value)
            }
        }
        val result = LinkedHashMap<String, String>()
        for (name in names) {
            result[name] = extended[name]?.let { decodeExtended(it) }
                ?: sections[name]?.let { join(it) }
                ?: EncodedWords.decode(plain.getValue(name))
        }
        return result
    }

    private class Segment(val encoded: Boolean, val value: String)

    /** `charset'lang'%XX...`; without the two apostrophes the whole value is percent-encoded UTF-8. */
    private fun decodeExtended(value: String): String {
        val (charset, data) = splitCharset(value)
        return TransferDecoding.text(percentDecode(data), charset)
    }

    /** Joins continuation sections in index order; adjacent encoded sections are decoded together. */
    private fun join(sections: Map<Int, Segment>): String {
        val ordered = sections.toSortedMap().values.toList()
        if (ordered.none { it.encoded }) return EncodedWords.decode(ordered.joinToString("") { it.value })
        val first = ordered[0]
        val (charset, firstData) = if (first.encoded) splitCharset(first.value) else null to first.value
        val out = StringBuilder()
        val bytes = ByteArrayOutputStream()
        ordered.forEachIndexed { i, segment ->
            val data = if (i == 0) firstData else segment.value
            if (segment.encoded) {
                bytes.write(percentDecode(data))
            } else {
                out.append(TransferDecoding.text(bytes.toByteArray(), charset)).append(data)
                bytes.reset()
            }
        }
        return out.append(TransferDecoding.text(bytes.toByteArray(), charset)).toString()
    }

    private fun splitCharset(value: String): Pair<String?, String> {
        val q1 = value.indexOf('\'')
        val q2 = if (q1 < 0) -1 else value.indexOf('\'', q1 + 1)
        return if (q2 < 0) null to value else value.substring(0, q1) to value.substring(q2 + 1)
    }

    private fun percentDecode(data: String): ByteArray {
        val raw = data.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(raw.size)
        var i = 0
        while (i < raw.size) {
            val hi = if (raw[i] == '%'.code.toByte() && i + 2 < raw.size) hex(raw[i + 1]) else -1
            val lo = if (hi >= 0) hex(raw[i + 2]) else -1
            if (lo >= 0) {
                out.write(hi shl 4 or lo)
                i += 3
            } else {
                out.write(raw[i++].toInt())
            }
        }
        return out.toByteArray()
    }

    private fun hex(b: Byte): Int = Character.digit(b.toInt() and 0xFF, 16)

    /** Splits at `;` outside quoted strings. */
    private fun split(value: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var escaped = false
        for (c in value) {
            when {
                escaped -> escaped = false
                inQuotes && c == '\\' -> escaped = true
                c == '"' -> inQuotes = !inQuotes
                c == ';' && !inQuotes -> {
                    parts += current.toString()
                    current.setLength(0)
                    continue
                }
            }
            current.append(c)
        }
        parts += current.toString()
        return parts
    }

    /** `name=value` or `name="quoted \" value"`; anything after the closing quote is ignored. */
    private fun param(part: String): Pair<String, String>? {
        val eq = part.indexOf('=')
        if (eq <= 0) return null
        val name = part.substring(0, eq).trim()
        val value = part.substring(eq + 1).trim()
        if (name.isEmpty()) return null
        if (!value.startsWith('"')) return name to value
        val text = StringBuilder()
        var i = 1
        while (i < value.length) {
            val c = value[i++]
            when {
                c == '"' -> break
                c == '\\' && i < value.length -> text.append(value[i++])
                else -> text.append(c)
            }
        }
        return name to text.toString()
    }

    private fun isToken(s: String) = s.isNotEmpty() && s.all { it > ' ' && it < '\u007F' && it !in TSPECIALS }
}
