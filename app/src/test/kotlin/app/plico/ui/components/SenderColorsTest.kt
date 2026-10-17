package app.plico.ui.components

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SenderColorsTest {
    @Test
    fun stable() {
        val first = SenderColors.index("alice@example.org")
        repeat(3) { assertEquals(first, SenderColors.index("alice@example.org")) }
        // Fixed values: String.hashCode and the mix are specified, so a sender keeps its colour between versions.
        assertEquals(4, first)
        assertEquals(2, SenderColors.index("bob@example.org"))
    }

    @Test
    fun caseInsensitive() {
        assertEquals(SenderColors.index("alice@example.org"), SenderColors.index("Alice@EXAMPLE.org"))
        // Lower case of the ROOT locale: on a Turkish phone "I" must not become a dotless "ı".
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals(SenderColors.index("info@example.org"), SenderColors.index("INFO@EXAMPLE.ORG"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun alwaysInThePalette() {
        val addresses = (0 until 2_000).map { "user$it@example$it.org" } + listOf("", "a", "\u0000", "ÄÖÜ@例子.测试")
        for (address in addresses) assertTrue(SenderColors.index(address) in 0 until AccountColors.COUNT, address)
        // Similar senders spread over the whole palette, about evenly.
        val counts = addresses.groupingBy(SenderColors::index).eachCount()
        assertEquals(AccountColors.COUNT, counts.size)
        assertTrue(counts.values.all { it in 180..320 }, counts.toString())
    }
}
