package app.plico.mail.mime

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** RFC 5322 dates. */
object Dates {
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** RFC 5322 §4.3 names in minutes; other names (military letters included) mean +0000. */
    private val zones = mapOf(
        "est" to -300, "edt" to -240, "cst" to -360, "cdt" to -300,
        "mst" to -420, "mdt" to -360, "pst" to -480, "pdt" to -420,
    )

    /** IMAP INTERNALDATE style `16-Oct-2026`. */
    private val dashed = Regex("\\d{1,2}-[A-Za-z]{3}-\\d{2,4}")

    /**
     * Lenient parse (obsolete zones, missing seconds, extra comments). Returns epoch milliseconds or null.
     * Accepts an optional day name, 1-2 digit days, 2- and 3-digit years, `+hhmm`, `UT`/`GMT`/`Z` and the
     * US zone names; a missing zone is UTC and a missing time is midnight.
     */
    fun parse(value: String): Long? {
        var month = -1
        var time: IntArray? = null
        var offset: Int? = null
        val numbers = ArrayList<String>()
        for (t in tokens(value)) {
            when {
                time == null && ':' in t -> time = time(t) ?: return null
                t[0] == '+' || t[0] == '-' -> if (time != null && offset == null) offset = offset(t)
                t[0].isLetter() -> {
                    val m = monthOf(t)
                    if (month < 0 && m >= 0) month = m else if (time != null && offset == null) offset = zone(t)
                }
                t.all { it in '0'..'9' } -> numbers += t
            }
        }
        if (month < 0) return null
        val dayAt = numbers.indexOfFirst { it.length <= 2 }
        if (dayAt < 0) return null
        val year = numbers.filterIndexed { i, _ -> i != dayAt }.firstOrNull()?.let(::year) ?: return null
        val (hour, minute, second) = time ?: IntArray(3)
        return try {
            val local = LocalDateTime.of(year, month + 1, numbers[dayAt].toInt(), hour, minute, second)
            (local.toEpochSecond(ZoneOffset.UTC) - (offset ?: 0) * 60L) * 1000L
        } catch (e: DateTimeException) {
            null
        }
    }

    /** `Thu, 16 Oct 2026 09:14:03 +0000` (always UTC, English names). */
    fun formatUtc(epochMs: Long): String {
        val t = LocalDateTime.ofEpochSecond(epochMs.floorDiv(1000L), 0, ZoneOffset.UTC)
        return "${days[t.dayOfWeek.ordinal]}, ${t.dayOfMonth} ${months[t.monthValue - 1]} " +
            "${t.year.toString().padStart(4, '0')} ${two(t.hour)}:${two(t.minute)}:${two(t.second)} +0000"
    }

    /** Words without comments; commas are separators. */
    private fun tokens(value: String): List<String> {
        val text = StringBuilder(value.length)
        var depth = 0
        for (c in value) {
            when {
                c == '(' -> depth++
                c == ')' && depth > 0 -> depth--
                depth == 0 -> text.append(if (c == ',' || c <= ' ') ' ' else c)
            }
        }
        return text.split(' ').filter { it.isNotEmpty() }
            .flatMap { if (dashed.matches(it)) it.split('-') else listOf(it) }
    }

    /** `hh:mm[:ss[.fff]]`; a leap second becomes :59. */
    private fun time(t: String): IntArray? {
        val parts = t.split(':')
        if (parts.size !in 2..3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = if (parts.size == 3) parts[2].substringBefore('.').toIntOrNull() ?: return null else 0
        if (h !in 0..23 || m !in 0..59 || s !in 0..60) return null
        return intArrayOf(h, m, minOf(s, 59))
    }

    /** `+hhmm`, `+hh:mm` or `+hh` in minutes; out of range -> 0. */
    private fun offset(t: String): Int {
        val digits = t.substring(1).replace(":", "")
        if (digits.isEmpty() || digits.length > 4 || !digits.all { it in '0'..'9' }) return 0
        val h = if (digits.length <= 2) digits.toInt() else digits.dropLast(2).toInt()
        val m = if (digits.length <= 2) 0 else digits.takeLast(2).toInt()
        if (h > 18 || m > 59) return 0
        return (h * 60 + m) * if (t[0] == '-') -1 else 1
    }

    /** Zone names; `GMT+0200` style is read as the offset. */
    private fun zone(t: String): Int {
        val sign = t.indexOfFirst { it == '+' || it == '-' }
        if (sign > 0) return offset(t.substring(sign))
        return zones[t.lowercase(Locale.ROOT)] ?: 0
    }

    private fun monthOf(t: String): Int =
        if (t.length < 3) -1 else months.indexOfFirst { t.startsWith(it, ignoreCase = true) }

    /** Two-digit years: < 50 -> 20xx, else 19xx; three digits + 1900 (RFC 5322 §4.3). */
    private fun year(t: String): Int? = when (t.length) {
        1, 2 -> t.toInt().let { if (it < 50) 2000 + it else 1900 + it }
        3 -> 1900 + t.toInt()
        4 -> t.toInt()
        else -> null
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')
}
