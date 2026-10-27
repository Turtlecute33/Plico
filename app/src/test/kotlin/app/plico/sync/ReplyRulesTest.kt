package app.plico.sync

import app.plico.mail.mime.Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyRulesTest {
    private val me = "me@riseup.net"
    private val alias = "alias@riseup.net"
    private val own = setOf(me, alias)

    private fun a(email: String, name: String? = null) = Address(name, email)
    private fun emails(list: List<Address>) = list.map { it.email }

    private fun source(
        from: Address? = a("alice@example.org", "Alice"),
        replyTo: List<Address> = emptyList(),
        to: List<Address> = listOf(a(me)),
        cc: List<Address> = emptyList(),
        fromSelf: Boolean = false,
    ) = ReplyRules.Source(from, replyTo, to, cc, fromSelf)

    @Test
    fun replyGoesToReplyToElseFrom() {
        assertEquals(listOf("alice@example.org"), emails(ReplyRules.recipients(source(), own, all = false).to))
        val list = source(replyTo = listOf(a("list@lists.example.org")))
        assertEquals(listOf("list@lists.example.org"), emails(ReplyRules.recipients(list, own, all = false).to))
        assertEquals(emptyList(), ReplyRules.recipients(list, own, all = false).cc)
    }

    @Test
    fun replyToOwnMessageGoesToItsTo() {
        val sent = source(from = a(me), to = listOf(a("bob@example.org"), a(alias)), cc = listOf(a("carol@example.org")), fromSelf = true)
        assertEquals(listOf("bob@example.org"), emails(ReplyRules.recipients(sent, own, all = false).to))
        val all = ReplyRules.recipients(sent, own, all = true)
        assertEquals(listOf("bob@example.org"), emails(all.to))
        assertEquals(listOf("carol@example.org"), emails(all.cc))
    }

    @Test
    fun replyAllRemovesOwnAddressesAndDuplicates() {
        val s = source(
            to = listOf(a("ME@riseup.net"), a("bob@example.org"), a("alice@example.org")),
            cc = listOf(a("Bob@Example.org"), a(alias), a("carol@example.org")),
        )
        val r = ReplyRules.recipients(s, own, all = true)
        assertEquals(listOf("alice@example.org"), emails(r.to))
        assertEquals(listOf("bob@example.org", "carol@example.org"), emails(r.cc))
    }

    @Test
    fun aNoteToOneselfKeepsItsAddress() {
        val note = source(from = a(me), to = listOf(a(me)), fromSelf = true)
        assertEquals(listOf(me), emails(ReplyRules.recipients(note, own, all = true).to))
    }

    @Test
    fun fromIsTheAddressedIdentity() {
        assertEquals(me, ReplyRules.from(source(to = listOf(a("x@example.org"))), me, listOf(alias)))
        assertEquals(alias, ReplyRules.from(source(to = listOf(a("x@example.org")), cc = listOf(a("Alias@Riseup.net"))), me, listOf(alias)))
        assertEquals(me, ReplyRules.from(source(to = listOf(a(alias), a(me))), me, listOf(alias)))
    }

    @Test
    fun subjectPrefixesDoNotStack() {
        assertEquals("Re: Lunch", ReplyRules.replySubject("Lunch"))
        assertEquals("Re: Lunch", ReplyRules.replySubject("Re: Lunch"))
        assertEquals("Re: Lunch", ReplyRules.replySubject("RE: re:Lunch"))
        assertEquals("Re: Lunch", ReplyRules.replySubject("Re[2]: AW: Lunch"))
        assertEquals("Re: Fwd: Lunch", ReplyRules.replySubject("Fwd: Lunch"))
        assertEquals("Re: Reply needed", ReplyRules.replySubject("Reply needed"))
        assertEquals("Re:", ReplyRules.replySubject(""))
        assertEquals("Fwd: Lunch", ReplyRules.forwardSubject("Lunch"))
        assertEquals("Fwd: Lunch", ReplyRules.forwardSubject("FW: Fwd: Lunch"))
        assertEquals("Fwd: Re: Lunch", ReplyRules.forwardSubject("Re: Lunch"))
    }

    @Test
    fun attributionIsInUtc() {
        // 2026-10-16T09:14:59Z
        assertEquals("On 2026-10-16 09:14 UTC, Alice wrote:", ReplyRules.attribution(1_792_142_099_000, "Alice"))
    }

    @Test
    fun quoteMarksEveryLine() {
        assertEquals("> one\n>\n> two\n>> old", ReplyRules.quote("one\r\n\ntwo\n> old\n\n"))
    }

    @Test
    fun replyBodyHasRoomSignatureAttributionAndQuote() {
        val body = ReplyRules.replyBody("Me\n", "On x, A wrote:", "hi")
        assertEquals("\n\n-- \nMe\n\nOn x, A wrote:\n> hi", body)
        assertEquals("\n\nOn x, A wrote:", ReplyRules.replyBody("", "On x, A wrote:", " "))
        assertEquals("text\n\n-- \nMe", ReplyRules.newBody("text", "Me"))
        assertEquals("text", ReplyRules.newBody("text", " "))
    }

    @Test
    fun forwardBodyCarriesTheOriginalHeaders() {
        val body = ReplyRules.forwardBody(
            "", a("alice@example.org", "Alice"), 1_792_142_099_000, "Lunch", listOf(a(me)), emptyList(), "Hi\r\n",
        )
        assertEquals(
            "\n\n---------- Forwarded message ----------\nFrom: Alice <alice@example.org>\nDate: 2026-10-16 09:14 UTC\n" +
                "Subject: Lunch\nTo: me@riseup.net\n\nHi",
            body,
        )
    }

    @Test
    fun aLongSourceIsQuotedUpToTheCap() {
        val source = "A newsletter line of about sixty characters for the test.\n".repeat(1_800) // About 100 KB.
        val capped = ReplyRules.capQuote(source)
        assertTrue(capped.length <= ReplyRules.MAX_QUOTE_CHARS + "\n[…]".length)
        assertTrue(capped.endsWith("\n[…]"))
        assertTrue(source.startsWith(capped.removeSuffix("\n[…]")))
        assertTrue(ReplyRules.replyBody("", "On x, A wrote:", source).endsWith("\n> […]"))
        val forward = ReplyRules.forwardBody("", null, 0, "News", emptyList(), emptyList(), source)
        assertTrue(forward.endsWith("\n[…]"))
        assertTrue(forward.length < ReplyRules.MAX_QUOTE_CHARS + 200)
        assertEquals("short", ReplyRules.capQuote("short"))
        // A cut never splits a surrogate pair.
        val emoji = "x".repeat(ReplyRules.MAX_QUOTE_CHARS - 1) + "😀".repeat(10)
        assertEquals("x".repeat(ReplyRules.MAX_QUOTE_CHARS - 1) + "\n[…]", ReplyRules.capQuote(emoji))
    }

    @Test
    fun referencesChainTheParent() {
        assertEquals("<a@x> <b@x> <c@x>", ReplyRules.references("<a@x> <b@x>", "<b@x>", "<c@x>"))
        assertEquals("<b@x> <c@x>", ReplyRules.references(null, "<b@x>", "<c@x>"))
        assertEquals("<c@x>", ReplyRules.references("junk", null, "<c@x>"))
        assertNull(ReplyRules.references(null, null, null))
    }
}
