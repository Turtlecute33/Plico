package app.plico.mail.mime

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AddrSpecTest {

    @Test
    fun `common addresses are valid`() {
        for (s in listOf(
            "alice@riseup.net", "a.b+tag@gmail.com", "o'neil@example.org", "x_y-z@sub.example.co.uk",
            "user%host@example.com", "!#\$%&'*+-/=?^_`{|}~@example.com", "1@2.3", "a@b-c.example", "user@localhost",
            "UPPER@EXAMPLE.COM",
        )) assertTrue(AddrSpec.isValid(s), s)
    }

    @Test
    fun `quoted local parts are valid`() {
        for (s in listOf(
            "\"john doe\"@example.com", "\"john..doe\"@example.com", "\"a\\\"b\"@example.com", "\"a\\\\\"@example.com",
            "\"a@b\"@example.com", "\".leading\"@example.com", "\"(not a comment)\"@example.com",
        )) assertTrue(AddrSpec.isValid(s), s)
    }

    @Test
    fun `internationalised addresses are valid`() {
        for (s in listOf(
            "用户@例子.广告", "उपयोगकर्ता@उदाहरण.कॉम", "josé@exämple.de", "Δοκιμή@παράδειγμα.δοκιμή",
            "me@bücher.example", "почта@пример.рф",
        )) assertTrue(AddrSpec.isValid(s), s)
    }

    @Test
    fun `address literals are valid`() {
        for (s in listOf("x@[127.0.0.1]", "x@[255.255.255.255]", "x@[IPv6:2001:db8::1]", "x@[ipv6:::1]")) {
            assertTrue(AddrSpec.isValid(s), s)
        }
    }

    @Test
    fun `header and command injection is rejected`() {
        for (s in listOf(
            "a@b>\r\nRCPT TO:<spy@evil", "a@b\r\nBcc: spy@evil", "a@b\nX: y", "a@b\r", "a\r\n@b.example",
            "\"a\r\nb\"@example.com", "\"a\nb\"@example.com", "<a@b.example>", "a@b.example>", "\"<a>\"@b.example",
            "a@b.example\u0000", "a\u0000@b.example", "\"a\tb\"@example.com", "a@b\u0085.example",
        )) assertFalse(AddrSpec.isValid(s), s.replace("\r", "\\r").replace("\n", "\\n"))
    }

    @Test
    fun `malformed addresses are rejected`() {
        for (s in listOf(
            "", "@", "a", "a@", "@b.example", "a@@b.example", "a@b@c.example", "a b@c.example", "a@b c.example",
            " a@b.example", "a@b.example ", ".a@b.example", "a.@b.example", "a..b@c.example", "a@.b.example",
            "a@b.example.", "a@b..example",
            "a@-b.example", "a@b-.example", "a@b_c.example", "a(b)@c.example", "a,b@c.example", "a;b@c.example",
            "a:b@c.example", "a[b]@c.example", "a\\b@c.example", "a\"b@c.example", "\"a\"b@c.example", "\"\"@c.example",
            "\"a@c.example", "\"a\\\"@c.example", "a@\"b\".example",
        )) assertFalse(AddrSpec.isValid(s), s)
    }

    @Test
    fun `bad address literals are rejected`() {
        for (s in listOf(
            "x@[]", "x@[1.2.3]", "x@[300.1.1.1]", "x@[01.2.3.4]", "x@[IPv6:zz]", "x@[1.2.3.4", "x@1.2.3.4]", "x@[a b]",
            "x@[tag:x]",
        )) {
            assertFalse(AddrSpec.isValid(s), s)
        }
    }

    @Test
    fun `non-letter Unicode is rejected`() {
        for (s in listOf(
            "😀@example.com", "a@exa😀mple.com", "a\u202e@example.com", "a\u200b@example.com", "a@b\u2028.example",
            "a\uD800@example.com", "\"\uD800\"@example.com", "a\u00a0b@example.com",
        )) assertFalse(AddrSpec.isValid(s), s)
    }

    @Test
    fun `length limits apply`() {
        assertTrue(AddrSpec.isValid("a".repeat(64) + "@example.com"))
        assertFalse(AddrSpec.isValid("a".repeat(65) + "@example.com"))
        assertFalse(AddrSpec.isValid("ü".repeat(33) + "@example.com"), "64 octets in UTF-8")
        assertTrue(AddrSpec.isValid("a@" + "b".repeat(63) + ".example"))
        assertFalse(AddrSpec.isValid("a@" + "b".repeat(64) + ".example"))
        val longDomain = List(5) { "c".repeat(50) }.joinToString(".")
        assertFalse(AddrSpec.isValid("a@$longDomain"), "over 254 octets")
    }
}
