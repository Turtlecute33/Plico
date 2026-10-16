package app.plico.ui.compose

import app.plico.mail.mime.Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipientsTest {
    private fun valid(email: String, name: String? = null) = Recipient.Valid(Address(name, email))

    @Test
    fun parsesSeparatorsAndNames() {
        val list = Recipients.parse("alice@example.com, Bob <bob@example.org>; carol@example.net\ndave@example.com")
        assertEquals(
            listOf(
                valid("alice@example.com"),
                valid("bob@example.org", "Bob"),
                valid("carol@example.net"),
                valid("dave@example.com"),
            ),
            list,
        )
    }

    @Test
    fun commaInsideQuotedNameDoesNotSplit() {
        val list = Recipients.parse("\"Doe, Jane\" <jane@example.com>, john@example.com")
        assertEquals(listOf(valid("jane@example.com", "Doe, Jane"), valid("john@example.com")), list)
    }

    @Test
    fun pastedSpaceSeparatedAddresses() {
        assertEquals(
            listOf(valid("a@example.com"), valid("b@example.com")),
            Recipients.parse("a@example.com b@example.com"),
        )
    }

    @Test
    fun invalidEntriesStayVisible() {
        val list = Recipients.parse("bob, alice@, @example.com, carol@example, ok@example.com")
        assertEquals(
            listOf(
                Recipient.Invalid("bob"),
                Recipient.Invalid("alice@"),
                Recipient.Invalid("@example.com"),
                Recipient.Invalid("carol@example"),
                valid("ok@example.com"),
            ),
            list,
        )
    }

    @Test
    fun invalidAddressKeepsItsName() {
        val list = Recipients.parse("Eve <eve@example>")
        assertEquals(1, list.size)
        assertEquals(Recipient.Invalid("Eve <eve@example>"), list[0])
        assertEquals(list, Recipients.parse(list[0].text))
    }

    @Test
    fun unclosedQuoteIsOneInvalidEntry() {
        val list = Recipients.parse("\"Doe, Jane <jane@example.com>, x@example.com")
        assertEquals(1, list.size)
        assertTrue(list.single() is Recipient.Invalid)
    }

    @Test
    fun duplicatesAreDroppedWithoutCase() {
        assertEquals(
            listOf(valid("a@example.com")),
            Recipients.parse("a@example.com, A@Example.com, Alias <a@EXAMPLE.com>"),
        )
    }

    @Test
    fun deliverableNeedsADotInTheDomain() {
        assertTrue(Recipients.isDeliverable("a@example.com"))
        assertTrue(Recipients.isDeliverable("a@[192.0.2.1]"))
        assertTrue(!Recipients.isDeliverable("a@localhost"))
        assertTrue(!Recipients.isDeliverable("a@example."))
        assertTrue(!Recipients.isDeliverable("a b@example.com"))
        assertTrue(!Recipients.isDeliverable("a@example.com>\r\nRCPT TO:<x@evil.example"))
    }

    @Test
    fun formatRoundTrips() {
        val list = listOf(
            valid("jane@example.com", "Doe, Jane"),
            valid("q@example.com", "Say \"hi\" \\ there"),
            valid("jose@example.com", "José Müller"),
            valid("plain@example.com"),
            Recipient.Invalid("bob"),
        )
        val text = Recipients.format(list)
        assertEquals(list, Recipients.parse(text))
        assertEquals("José Müller <jose@example.com>", list[2].text)
        assertEquals("\"Doe, Jane\" <jane@example.com>", list[0].text)
    }

    @Test
    fun takeFinishedAtSeparators() {
        val (done, pending) = Recipients.takeFinished("a@example.com, b@example.com; car")
        assertEquals(listOf(valid("a@example.com"), valid("b@example.com")), done)
        assertEquals("car", pending)
    }

    @Test
    fun takeFinishedAtSpaceAfterCompleteAddress() {
        assertEquals(listOf(valid("a@example.com")) to "", Recipients.takeFinished("a@example.com "))
        assertEquals(
            listOf(valid("j@example.com", "Jane Doe")) to "",
            Recipients.takeFinished("Jane Doe <j@example.com> "),
        )
    }

    @Test
    fun takeFinishedWaitsWhileANameOrAddressIsTyped() {
        assertEquals(emptyList<Recipient>() to "Jane ", Recipients.takeFinished("Jane "))
        assertEquals(emptyList<Recipient>() to "Jane <j@example.com ", Recipients.takeFinished("Jane <j@example.com "))
        assertEquals(emptyList<Recipient>() to "\"Doe, Jane", Recipients.takeFinished("\"Doe, Jane"))
        assertEquals(emptyList<Recipient>() to "bob@example ", Recipients.takeFinished("bob@example "))
        assertEquals(emptyList<Recipient>() to "", Recipients.takeFinished("  "))
    }

    @Test
    fun takeFinishedMarksInvalidEntries() {
        val (done, pending) = Recipients.takeFinished("bob, ")
        assertEquals(listOf(Recipient.Invalid("bob")), done)
        assertEquals("", pending)
    }

    @Test
    fun newOnesSkipsExistingAndRepeated() {
        val existing = listOf(valid("a@example.com"), Recipient.Invalid("x"))
        val added = listOf(
            valid("A@example.com"),
            valid("b@example.com"),
            valid("b@example.com"),
            Recipient.Invalid("x"),
        )
        assertEquals(listOf(valid("b@example.com")), Recipients.newOnes(existing, added))
    }

    @Test
    fun labels() {
        assertEquals("Jane", valid("j@example.com", "Jane").label)
        assertEquals("j@example.com", valid("j@example.com").label)
        assertEquals("bob", Recipient.Invalid("bob").label)
    }
}
