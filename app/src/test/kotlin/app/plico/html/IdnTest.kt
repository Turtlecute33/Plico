package app.plico.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdnTest {
    @Test
    fun asciiForm() {
        assertEquals("example.com", Idn.toAscii("Example.COM."))
        assertEquals("xn--e1afmkfd.xn--p1ai", Idn.toAscii("пример.рф"))
        assertEquals("xn--pple-43d.com", Idn.toAscii("аpple.com"))
        assertEquals("my_host.example.com", Idn.toAscii("my_host.example.com"))
        assertEquals("例え.jp", Idn.toUnicode(Idn.toAscii("例え.jp")!!))
    }

    @Test
    fun invalidNames() {
        assertNull(Idn.toAscii(""))
        assertNull(Idn.toAscii("."))
        assertNull(Idn.toAscii("a..b"))
        assertNull(Idn.toAscii("exa mple.com"))
        assertNull(Idn.toAscii("evil.com/x"))
        assertNull(Idn.toAscii("user@host.com"))
        assertNull(Idn.toAscii("a".repeat(64) + ".com"))
        assertNull(Idn.toAscii((1..60).joinToString(".") { "abcd" }))
    }

    @Test
    fun ipAddresses() {
        for (host in listOf("1.2.3.4", "127.1", "0x7f.1", "0x7f000001", "2130706433", "[::1]", "::1", "10.0.0.1.")) {
            assertTrue(Idn.isIpAddress(host), host)
        }
        for (host in listOf("example.com", "1.2.3.com", "x0x7f.com", "0xg.com")) assertFalse(Idn.isIpAddress(host), host)
    }

    @Test
    fun mixedScript() {
        // Cyrillic а (U+0430) in "apple".
        assertTrue(Idn.isMixedScript("аpple.com"))
        assertTrue(Idn.isMixedScript("xn--pple-43d.com"))
        // Greek ο in "google".
        assertTrue(Idn.isMixedScript("gοogle.com"))
        assertFalse(Idn.isMixedScript("apple.com"))
        assertFalse(Idn.isMixedScript("пример.рф"))
        // Latin with Japanese is allowed; Cyrillic with Han is not.
        assertFalse(Idn.isMixedScript("sony例え.jp"))
        assertFalse(Idn.isMixedScript("例えば.jp"))
        assertTrue(Idn.isMixedScript("пример例.com"))
        // Digits and hyphens are common to all scripts.
        assertFalse(Idn.isMixedScript("пример-1.рф"))
    }

    @Test
    fun lookalikes() {
        // All Cyrillic: а р р ӏ е.
        assertTrue(Idn.isLookalike("аррӏе.com"))
        assertFalse(Idn.isMixedScript("аррӏе.com"))
        assertTrue(Idn.isLookalike("xn--80ak6aa92e.com"))
        assertTrue(Idn.isLookalike("аpple.com"))
        assertFalse(Idn.isLookalike("пример.рф"))
        assertFalse(Idn.isLookalike("apple.com"))
        assertFalse(Idn.isLookalike("bücher.de"))
    }
}
