package app.plico.ui.components

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Locale-aware dates for lists and message headers. On the phone use [rememberDateFormats] or
 * [DateFormats.forDevice]; the patterns then follow the locale and the 12/24-hour setting.
 */
class DateFormats(locale: Locale, private val zone: ZoneId, patterns: Patterns) {
    /** java.time patterns (the ICU letters that Android returns for the usual skeletons). */
    data class Patterns(
        val time: String,
        val weekday: String,
        val dayMonth: String,
        val numericDate: String,
        val full: String,
    )

    private val time = formatter(patterns.time, locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    private val weekday = formatter(patterns.weekday, locale) { DateTimeFormatter.ofPattern("EEE", locale) }
    private val dayMonth = formatter(patterns.dayMonth, locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    private val numericDate = formatter(patterns.numericDate, locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT) }
    private val fullFormat = formatter(patterns.full, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }

    /**
     * The date column of a message list: the time for today, the weekday for the 6 days before, day
     * and month for older dates of this year, else the numeric date. Dates in the future (wrong
     * sender clock) get day and month or the numeric date.
     */
    fun list(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val date = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val daysAgo = ChronoUnit.DAYS.between(date.toLocalDate(), today)
        val format = when {
            daysAgo == 0L -> time
            daysAgo in 1..6 -> weekday
            date.year == today.year -> dayMonth
            else -> numericDate
        }
        return format.format(date)
    }

    /** Weekday, date and time, e.g. for the message header. */
    fun full(epochMs: Long): String = fullFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    companion object {
        /** Patterns from the platform for [locale], with the user's 12/24-hour choice. */
        fun forDevice(context: Context, locale: Locale = context.resources.configuration.locales[0]): DateFormats {
            val hour = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
            fun best(skeleton: String) = DateFormat.getBestDateTimePattern(locale, skeleton)
            val patterns = Patterns(
                time = best(hour),
                weekday = best("EEE"),
                dayMonth = best("MMMd"),
                numericDate = best("yMd"),
                full = best("yMMMEd$hour"),
            )
            return DateFormats(locale, ZoneId.systemDefault(), patterns)
        }

        // ICU can return letters that java.time does not know (e.g. 'b'); then use a localized style.
        private fun formatter(pattern: String, locale: Locale, fallback: () -> DateTimeFormatter): DateTimeFormatter =
            try {
                DateTimeFormatter.ofPattern(pattern, locale)
            } catch (_: IllegalArgumentException) {
                fallback().withLocale(locale)
            }
    }
}

/** [DateFormats] for the current locale; created again when the configuration changes. */
@Composable
fun rememberDateFormats(): DateFormats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(context, locale) { DateFormats.forDevice(context, locale) }
}

/** A file or message size as Android shows it ("1.2 MB"), the same text as the send and upload errors. */
@Composable
fun fileSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)
