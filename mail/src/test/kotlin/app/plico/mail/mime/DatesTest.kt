package app.plico.mail.mime

import org.junit.Test
import kotlin.random.Random
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatesTest {
    private val base = 1792142043000L // Fri 2026-10-16T09:14:03Z

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, offsetMinutes: Int = 0) =
        OffsetDateTime.of(y, mo, d, h, mi, s, 0, ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toInstant().toEpochMilli()

    @Test
    fun rfc5322() {
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 09:14:03 +0000"))
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 11:14:03 +0200"))
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 04:14:03 -0500"))
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 14:44:03 +0530"))
        assertEquals(880127706000L, Dates.parse("Fri, 21 Nov 1997 09:55:06 -0600"))
    }

    @Test
    fun realHeaders() {
        // Gmail
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 02:14:03 -0700 (PDT)"))
        // Outlook / Exchange
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 09:14:03 +0000"))
        // Apple Mail
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 11:14:03 +0200"))
        // Riseup sender keeps the local offset
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 06:14:03 -0300"))
        // Google service mail
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 09:14:03 GMT"))
        // Mailing list software with comment
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 11:14:03 +0200 (CEST)"))
    }

    @Test
    fun obsoleteForms() {
        assertEquals(880106106000L, Dates.parse("21 Nov 97 09:55:06 GMT"))
        assertEquals(at(1969, 2, 13, 23, 32, 0, -210), Dates.parse("Thu,\r\n      13\r\n        Feb\r\n          1969\r\n      23:32\r\n               -0330 (Newfoundland Time)"))
        assertEquals(at(2026, 10, 6, 9, 14, 0), Dates.parse("6 oct 2026 09:14 UT"))
        assertEquals(at(2026, 10, 16, 9, 14, 3), Dates.parse("16 OCT 2026 09:14:03 Z"))
        assertEquals(at(1950, 1, 2, 3, 4, 5), Dates.parse("2 Jan 50 03:04:05 +0000"))
        assertEquals(at(2049, 1, 2, 3, 4, 5), Dates.parse("2 Jan 49 03:04:05 +0000"))
        assertEquals(at(2004, 1, 2, 3, 4, 5), Dates.parse("2 Jan 104 03:04:05 +0000"))
    }

    @Test
    fun usZones() {
        val utc = at(2026, 1, 15, 12, 0, 0)
        assertEquals(utc, Dates.parse("15 Jan 2026 07:00:00 EST"))
        assertEquals(utc, Dates.parse("15 Jan 2026 08:00:00 EDT"))
        assertEquals(utc, Dates.parse("15 Jan 2026 06:00:00 CST"))
        assertEquals(utc, Dates.parse("15 Jan 2026 07:00:00 CDT"))
        assertEquals(utc, Dates.parse("15 Jan 2026 05:00:00 MST"))
        assertEquals(utc, Dates.parse("15 Jan 2026 06:00:00 MDT"))
        assertEquals(utc, Dates.parse("15 Jan 2026 04:00:00 PST"))
        assertEquals(utc, Dates.parse("15 Jan 2026 05:00:00 pdt"))
    }

    @Test
    fun militaryAndUnknownZonesAreUtc() {
        val utc = at(2026, 1, 15, 12, 0, 0)
        for (zone in listOf("A", "M", "N", "Y", "z", "CEST", "XYZ", "-0000", "+9999", "+12345")) {
            assertEquals(utc, Dates.parse("15 Jan 2026 12:00:00 $zone"), zone)
        }
        assertEquals(utc, Dates.parse("Thu, 15 Jan 2026 12:00:00"))
    }

    @Test
    fun lenientZoneForms() {
        val utc = at(2026, 1, 15, 12, 0, 0)
        assertEquals(utc, Dates.parse("15 Jan 2026 14:00:00 +02:00"))
        assertEquals(utc, Dates.parse("15 Jan 2026 14:00:00 +02"))
        assertEquals(utc, Dates.parse("15 Jan 2026 14:00:00 GMT+0200"))
        assertEquals(utc, Dates.parse("15 Jan 2026 07:00:00 UTC-05:00"))
        assertEquals(utc, Dates.parse("15 Jan 2026 14:00:00 +0200 +0500"))
    }

    @Test
    fun extraSpacesNamesAndCommas() {
        assertEquals(base, Dates.parse("  Fri ,  16   Oct   2026   09:14:03   +0000  "))
        assertEquals(base, Dates.parse("Friday, 16 October 2026 09:14:03 +0000"))
        assertEquals(base, Dates.parse("16 Oct 2026 09:14:03 +0000"))
        assertEquals(base, Dates.parse("Fri,16 Oct 2026 09:14:03 +0000"))
        assertEquals(base, Dates.parse("Fri, 16 Oct 2026 09:14:03.123 +0000"))
        // Wrong day name is ignored.
        assertEquals(base, Dates.parse("Mon, 16 Oct 2026 09:14:03 +0000"))
    }

    @Test
    fun asctimeAndImapForms() {
        assertEquals(base, Dates.parse("Fri Oct 16 09:14:03 2026"))
        assertEquals(base, Dates.parse("16-Oct-2026 11:14:03 +0200"))
        assertEquals(at(2026, 10, 6, 9, 14, 3), Dates.parse(" 6-Oct-2026 09:14:03 +0000"))
    }

    @Test
    fun missingTimeIsMidnightAndLeapSecond() {
        assertEquals(at(2026, 10, 16, 0, 0, 0), Dates.parse("16 Oct 2026"))
        assertEquals(at(2026, 12, 31, 23, 59, 59), Dates.parse("31 Dec 2026 23:59:60 +0000"))
    }

    @Test
    fun invalidDates() {
        for (bad in listOf(
            "", "   ", "garbage", "(only a comment)", "16 2026 09:14:03", "Oct 2026 09:14:03",
            "30 Feb 2026 10:00:00 +0000", "32 Jan 2026 10:00:00", "16 Oct 2026 25:00:00", "16 Oct 2026 10:61:00",
            "16 Oct 2026 10:00:00:00", "16 Oct 12345 10:00:00", "16 Oct 2026 aa:bb", "16 Oct",
        )) {
            assertNull(Dates.parse(bad), bad)
        }
    }

    @Test
    fun formatUtc() {
        assertEquals("Fri, 16 Oct 2026 09:14:03 +0000", Dates.formatUtc(base))
        assertEquals("Fri, 16 Oct 2026 09:14:03 +0000", Dates.formatUtc(base + 999))
        assertEquals("Sat, 1 Jan 2000 00:00:00 +0000", Dates.formatUtc(946684800000L))
        assertEquals("Sun, 1 Mar 2026 00:00:00 +0000", Dates.formatUtc(1772323200000L))
        assertEquals("Thu, 1 Jan 1970 00:00:00 +0000", Dates.formatUtc(0))
        assertEquals("Wed, 31 Dec 1969 23:59:59 +0000", Dates.formatUtc(-1))
    }

    @Test
    fun formatIgnoresDefaultLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("Fri, 16 Oct 2026 09:14:03 +0000", Dates.formatUtc(base))
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals(base, Dates.parse("FRI, 16 OCT 2026 09:14:03 +0000"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun roundTrip() {
        for (t in listOf(base, 0L, 946684800000L, 4102444799000L)) assertEquals(t, Dates.parse(Dates.formatUtc(t)))
    }

    @Test
    fun randomInputNeverThrows() {
        val rnd = Random(3)
        val alphabet = "0123456789 :+-,()JanOctThuGMTZ"
        repeat(5000) {
            val s = String(CharArray(rnd.nextInt(0, 40)) { alphabet[rnd.nextInt(alphabet.length)] })
            Dates.parse(s)
        }
    }

    @Test(timeout = 5_000)
    fun hostileInputStaysFast() {
        assertNull(Dates.parse(" ".repeat(500_000) + "x"))
        assertNull(Dates.parse("(".repeat(500_000)))
        assertNull(Dates.parse("1 ".repeat(200_000)))
    }
}
