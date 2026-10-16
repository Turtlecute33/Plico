package app.plico.html

import app.plico.mail.mime.Address
import app.plico.mail.mime.Addresses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailtoTest {
    private fun parse(uri: String) = assertNotNull(Mailto.parse(uri), uri)

    private fun emails(list: List<Address>) = list.map { it.email }

    @Test
    fun simpleAddress() {
        val m = parse("mailto:alice@example.com")
        assertEquals(listOf("alice@example.com"), emails(m.to))
        assertEquals(emptyList(), m.cc)
        assertEquals(emptyList(), m.bcc)
        assertEquals("", m.subject)
        assertEquals("", m.body)
        assertEquals(listOf("bob@example.com"), emails(parse("MAILTO:bob@example.com").to))
    }

    @Test
    fun allFields() {
        val m = parse(
            "mailto:a@example.com,b@example.org?to=c@example.net&cc=d@example.com&bcc=e@example.com" +
                "&subject=Hello%20there&body=Line%201%0D%0ALine%202",
        )
        assertEquals(listOf("a@example.com", "b@example.org", "c@example.net"), emails(m.to))
        assertEquals(listOf("d@example.com"), emails(m.cc))
        assertEquals(listOf("e@example.com"), emails(m.bcc))
        assertEquals("Hello there", m.subject)
        assertEquals("Line 1\nLine 2", m.body)
    }

    @Test
    fun fieldNamesIgnoreCaseAndFirstSubjectWins() {
        val m = parse("mailto:?To=a@example.com&SUBJECT=First&subject=Second&Body=x&BCC=b@example.com")
        assertEquals(listOf("a@example.com"), emails(m.to))
        assertEquals(listOf("b@example.com"), emails(m.bcc))
        assertEquals("First", m.subject)
        assertEquals("x", m.body)
    }

    @Test
    fun otherFieldsAreIgnored() {
        val m = parse(
            "mailto:a@example.com?attach=/data/data/app.plico/databases/plico.db&attachment=file:///sdcard/x" +
                "&from=boss@example.com&in-reply-to=%3Cx@y%3E&reply-to=evil@example.com&X-Header=1",
        )
        assertEquals(Mailto(listOf(Address(null, "a@example.com")), emptyList(), emptyList(), "", ""), m)
    }

    @Test
    fun headerInjectionIsRemoved() {
        val m = parse("mailto:a@example.com?subject=Hi%0D%0ABcc:%20spy@evil.example&body=a%00b%09c%0Dd")
        assertEquals("Hi Bcc: spy@evil.example", m.subject)
        assertEquals("ab\tc\nd", m.body)
        val tricky = parse("mailto:a@example.com%3E%0D%0ARCPT%20TO:%3Cspy@evil.example?cc=x%0D%0A@example.com")
        for (a in tricky.to + tricky.cc) {
            assertTrue(Addresses.isValidAddrSpec(a.email), a.email)
            assertTrue(a.name.orEmpty().none { it == '\r' || it == '\n' }, a.toString())
        }
    }

    @Test
    fun invalidAddressesAreDropped() {
        assertEquals(emptyList(), parse("mailto:not-an-address").to)
        assertEquals(listOf("ok@example.com"), emails(parse("mailto:@example.com,a@,%22a%0Db%22@example.com,ok@example.com").to))
        assertEquals(emptyList(), parse("mailto:?cc=<>").cc)
    }

    @Test
    fun decodingRules() {
        // "+" is a plus in mailto URIs (RFC 6068), not a space.
        assertEquals("a+b", parse("mailto:x@example.com?subject=a+b").subject)
        assertEquals(listOf("a+tag@example.com"), emails(parse("mailto:a+tag@example.com").to))
        assertEquals("été", parse("mailto:x@example.com?subject=%C3%A9t%C3%A9").subject)
        assertEquals("100%zz", parse("mailto:x@example.com?subject=100%zz").subject)
        assertEquals(listOf("a@example.com"), emails(parse("mailto:a%40example.com").to))
        // The fragment is not part of any field.
        assertEquals("x", parse("mailto:a@example.com?body=x#y").body)
    }

    @Test
    fun duplicatesAreRemoved() {
        val m = parse("mailto:a@example.com?to=A@Example.com&cc=b@example.com,b@example.com")
        assertEquals(listOf("a@example.com"), emails(m.to))
        assertEquals(listOf("b@example.com"), emails(m.cc))
    }

    @Test
    fun notMailto() {
        assertNull(Mailto.parse("https://example.com/"))
        assertNull(Mailto.parse("mailto"))
        assertNull(Mailto.parse("xmailto:a@example.com"))
        assertNull(Mailto.parse(""))
    }
}
