package app.plico.ui.compose

import android.content.Intent
import app.plico.mail.mime.Address
import app.plico.sync.DraftPrefill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeIntentsTest {
    private fun prefill(shared: ComposeIntents.Shared) = ComposeIntents.prefill(shared)

    @Test
    fun sendExtras() {
        val p = prefill(
            ComposeIntents.Shared(
                to = listOf("alice@example.com", "Bob <bob@example.org>"),
                cc = listOf("carol@example.net"),
                bcc = listOf("dave@example.com"),
                subject = "Look at this",
                text = "https://example.com/page",
            ),
        )
        assertEquals(
            DraftPrefill(
                to = "alice@example.com, Bob <bob@example.org>",
                cc = "carol@example.net",
                bcc = "dave@example.com",
                subject = "Look at this",
                body = "https://example.com/page",
            ),
            p,
        )
    }

    @Test
    fun oneExtraWithManyAddresses() {
        val p = prefill(ComposeIntents.Shared(to = listOf("a@example.com, b@example.com; a@EXAMPLE.com")))
        assertEquals("a@example.com, b@example.com", p.to)
    }

    @Test
    fun invalidAddressesAreDropped() {
        val p = prefill(
            ComposeIntents.Shared(
                to = listOf("not an address", "x@example.com>\r\nRCPT TO:<spy@evil.example", "ok@example.com"),
            ),
        )
        assertEquals(1, Recipients.parse(p.to).count { it is Recipient.Valid && it.address.email == "ok@example.com" })
        assertTrue(Recipients.parse(p.to).all { it is Recipient.Valid }, p.to)
        assertFalse('\r' in p.to || '\n' in p.to)
    }

    @Test
    fun subjectAndBodyAreCleaned() {
        val p = prefill(ComposeIntents.Shared(subject = "Hi\r\nBcc: spy@evil.example", text = "a\r\nb\rc\u0000d\te"))
        assertEquals("Hi Bcc: spy@evil.example", p.subject)
        assertEquals("a\nb\ncd\te", p.body)
    }

    @Test
    fun mailtoFromSendtoOrView() {
        val p = prefill(
            ComposeIntents.Shared(
                mailto = "mailto:a@example.com?cc=b@example.com&bcc=c@example.com" +
                    "&subject=Hello&body=Line%201%0D%0ALine%202",
            ),
        )
        assertEquals(DraftPrefill("a@example.com", "b@example.com", "c@example.com", "Hello", "Line 1\nLine 2"), p)
    }

    @Test
    fun mailtoMergesWithSendtoExtras() {
        val p = prefill(
            ComposeIntents.Shared(
                mailto = "mailto:",
                to = listOf("a@example.com"),
                subject = "From extras",
                text = "Body from extras",
            ),
        )
        assertEquals(DraftPrefill(to = "a@example.com", subject = "From extras", body = "Body from extras"), p)

        val both = prefill(
            ComposeIntents.Shared(
                mailto = "mailto:a@example.com?subject=Mailto",
                to = listOf("b@example.com"),
                subject = "Extra",
            ),
        )
        assertEquals("a@example.com, b@example.com", both.to)
        assertEquals("Mailto", both.subject)
    }

    @Test
    fun mailtoIgnoresAttachAndOtherFields() {
        val uri = "mailto:a@example.com?attach=/data/data/app.plico/databases/plico.db&from=x@y.z"
        val p = prefill(ComposeIntents.Shared(mailto = uri))
        assertEquals(DraftPrefill(to = "a@example.com"), p)
    }

    @Test
    fun emptyDetection() {
        assertTrue(ComposeIntents.isEmpty(prefill(ComposeIntents.Shared())))
        assertTrue(ComposeIntents.isEmpty(prefill(ComposeIntents.Shared(to = listOf("nobody"), subject = "\r\n"))))
        assertFalse(ComposeIntents.isEmpty(prefill(ComposeIntents.Shared(text = "x"))))
    }

    @Test
    fun namesWithSpecialsAreQuotedForTheDraft() {
        val p = prefill(ComposeIntents.Shared(to = listOf("\"Doe, Jane\" <jane@example.com>")))
        assertEquals("\"Doe, Jane\" <jane@example.com>", p.to)
        val jane = Recipient.Valid(Address("Doe, Jane", "jane@example.com"))
        assertEquals(listOf<Recipient>(jane), Recipients.parse(p.to))
    }

    @Test
    fun aMailtoDraftIsFreshAndASharedOneIsKept() {
        // DESIGN §13: a mailto draft left without an edit is deleted, so a web page cannot leave one behind.
        assertTrue(ComposeIntents.isMailto(Intent.ACTION_SENDTO))
        assertTrue(ComposeIntents.isMailto(Intent.ACTION_VIEW))
        assertFalse(ComposeIntents.isMailto(Intent.ACTION_SEND))
        assertFalse(ComposeIntents.isMailto(Intent.ACTION_SEND_MULTIPLE))
        assertFalse(ComposeIntents.isMailto(null))
    }
}
