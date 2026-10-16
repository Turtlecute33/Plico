package app.plico.ui.components

import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val patterns = DateFormats.Patterns(
        time = "HH:mm",
        weekday = "EEE",
        dayMonth = "d MMM",
        numericDate = "dd.MM.yyyy",
        full = "EEE, d MMM yyyy HH:mm",
    )
    private val us = DateFormats(Locale.US, zone, patterns)

    // Friday, 16 October 2026, 15:30 in Berlin.
    private val now = at(2026, 10, 16, 15, 30)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 5) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun todayShowsTheTime() {
        assertEquals("00:00", us.list(at(2026, 10, 16, 0, 0), now))
        assertEquals("09:05", us.list(at(2026, 10, 16), now))
        assertEquals("23:59", us.list(at(2026, 10, 16, 23, 59), now))
    }

    @Test
    fun theSixDaysBeforeShowTheWeekday() {
        assertEquals("Thu", us.list(at(2026, 10, 15, 23, 59), now))
        assertEquals("Sat", us.list(at(2026, 10, 10, 0, 0), now))
    }

    @Test
    fun olderDatesOfThisYearShowDayAndMonth() {
        assertEquals("9 Oct", us.list(at(2026, 10, 9, 23, 59), now))
        assertEquals("1 Jan", us.list(at(2026, 1, 1, 0, 0), now))
    }

    @Test
    fun earlierYearsShowTheNumericDate() {
        assertEquals("31.12.2025", us.list(at(2025, 12, 31, 23, 59), now))
        assertEquals("16.10.2025", us.list(at(2025, 10, 16), now))
    }

    @Test
    fun futureDatesNeverShowAWeekday() {
        assertEquals("17 Oct", us.list(at(2026, 10, 17), now))
        assertEquals("01.01.2027", us.list(at(2027, 1, 1), now))
    }

    @Test
    fun daysFollowTheCalendarAcrossTheDaylightSavingChange() {
        // Summer time ends on 25 October 2026; 24 h before 00:30 on the 26th is still the 25th.
        val monday = at(2026, 10, 26, 0, 30)
        assertEquals("Sun", us.list(at(2026, 10, 25, 0, 30), monday))
        assertEquals("Sat", us.list(at(2026, 10, 24, 23, 59), monday))
    }

    @Test
    fun theZoneDecidesWhatTodayIs() {
        // 23:30 UTC on 15 October is already the 16th in Berlin.
        val late = ZonedDateTime.of(2026, 10, 15, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("01:30", us.list(late, now))
        assertEquals("Thu", DateFormats(Locale.US, ZoneId.of("UTC"), patterns).list(late, at(2026, 10, 17)))
    }

    @Test
    fun namesFollowTheLocale() {
        val de = DateFormats(Locale.GERMANY, zone, patterns)
        assertEquals("Do.", de.list(at(2026, 10, 15), now))
        assertEquals("9 Okt.", de.list(at(2026, 10, 9), now))
        assertEquals("Fr., 16 Okt. 2026 09:05", de.full(at(2026, 10, 16)))
    }

    @Test
    fun fullDate() {
        assertEquals("Fri, 16 Oct 2026 15:30", us.full(now))
    }

    @Test
    fun unknownPatternLettersFallBackToALocalizedStyle() {
        val odd = DateFormats(Locale.US, zone, patterns.copy(time = "h:mm b"))
        assertTrue(odd.list(now, now).startsWith("3:30"))
    }

    // The formatter puts a no-break space between number and unit.
    private fun size(bytes: Long, locale: Locale) = formatFileSize(bytes, locale).replace('\u00A0', ' ')

    @Test
    fun numberAndUnitDoNotBreakApart() {
        assertEquals("1.2\u00A0kB", formatFileSize(1_234, Locale.US))
    }

    @Test
    fun bytesBelowOneKilobyte() {
        assertEquals("0 B", size(0, Locale.US))
        assertEquals("999 B", size(999, Locale.US))
        assertEquals("0 B", size(-5, Locale.US))
    }

    @Test
    fun oneDecimalBelowTenThenWholeNumbers() {
        assertEquals("1.0 kB", size(1_000, Locale.US))
        assertEquals("1.2 kB", size(1_234, Locale.US))
        assertEquals("9.9 kB", size(9_949, Locale.US))
        assertEquals("10 kB", size(9_950, Locale.US))
        assertEquals("34 kB", size(34_499, Locale.US))
        assertEquals("26 MB", size(25_600_000, Locale.US))
        assertEquals("1.5 GB", size(1_500_000_000, Locale.US))
    }

    @Test
    fun roundingNeverShowsAThousand() {
        assertEquals("999 kB", size(999_499, Locale.US))
        assertEquals("1.0 MB", size(999_500, Locale.US))
        assertEquals("1.0 GB", size(999_999_999, Locale.US))
    }

    @Test
    fun hugeSizesStayInTerabytes() {
        assertTrue(size(Long.MAX_VALUE, Locale.US).endsWith(" TB"))
    }

    @Test
    fun sizesUseTheLocaleSeparator() {
        assertEquals("1,2 MB", size(1_234_567, Locale.GERMANY))
    }
}
