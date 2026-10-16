package app.plico.sync

import app.plico.data.Auth
import app.plico.mail.imap.FetchItem
import app.plico.mail.mime.Address
import app.plico.mail.mime.Addresses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeaderParserTest {
    private val section = "HEADER.FIELDS (DATE FROM TO)"

    private fun item(
        header: String,
        flags: Set<String>? = emptySet(),
        internalDate: Long? = 1_700_000_000_000,
        key: String = section,
    ) = FetchItem(
        seq = 3, uid = 42, flags = flags, modSeq = 9, internalDate = internalDate, size = 1234, gmMsgId = 77,
        gmLabels = null, bodyStructure = null, sections = mapOf(key to header.replace("\n", "\r\n").toByteArray()),
    )

    private fun parse(header: String, gmail: Boolean = false, flags: Set<String>? = emptySet(), internalDate: Long? = 1_700_000_000_000) =
        HeaderParser.newMessage(item(header, flags, internalDate), 1, 2, gmail, null, notified = false, now = 5)

    @Test
    fun mapsHeadersToARow() {
        val m = parse(
            """
            Date: Thu, 16 Oct 2026 09:14:03 +0000
            From: =?UTF-8?Q?J=C3=BCrgen_M=C3=BCller?= <juergen@example.org>
            To: "Doe, Jane" <jane@example.org>, bob@example.org
            Cc: Ann <ann@example.org>
            Bcc: hidden@example.org
            Reply-To: list@example.org
            Subject: =?UTF-8?B?R3LDvMOfZQ==?= and
             more
            Message-ID: <abc@example.org>
            In-Reply-To: <p1@example.org> junk
            References: <r1@example.org>
             <r2@example.org> <r1@example.org>
            List-Unsubscribe: <mailto:u@example.org>,
             <https://example.org/u>
            List-Unsubscribe-Post: List-Unsubscribe=One-Click

            """.trimIndent(),
            flags = setOf("\\Seen", "\\ANSWERED", "\$Forwarded"),
        )
        assertEquals(42, m.uid)
        assertEquals(2, m.folderId)
        assertEquals(1_700_000_000_000, m.date, "INTERNALDATE, not the Date header")
        assertEquals("Jürgen Müller", m.fromName)
        assertEquals("juergen@example.org", m.fromEmail)
        assertEquals("\"Doe, Jane\" <jane@example.org>, bob@example.org", m.toList)
        assertEquals("Ann <ann@example.org>", m.ccList)
        assertEquals("hidden@example.org", m.bccList)
        assertEquals("list@example.org", m.replyTo)
        assertEquals("Grüße and more", m.subject)
        assertEquals("<abc@example.org>", m.messageId)
        assertEquals("<p1@example.org>", m.inReplyTo)
        assertEquals("<r1@example.org> <r2@example.org>", m.refs)
        assertEquals("<mailto:u@example.org>, <https://example.org/u>", m.unsubscribe)
        assertTrue(m.unsubscribePost)
        assertTrue(m.seen)
        assertTrue(m.answered)
        assertFalse(m.flagged)
        assertFalse(m.draft)
        assertEquals(9, m.modSeq)
        assertEquals(1234, m.size)
        assertEquals(77, m.gmMsgId)
        assertFalse(m.notified)
        assertNull(m.labels)
    }

    @Test
    fun storedAddressListsReadBack() {
        val list = listOf(Address("Doe, Jane", "jane@example.org"), Address("Zoë", "z@example.org"), Address(null, "x@example.org"))
        assertEquals(list, Addresses.parseList(HeaderParser.format(list)))
        assertEquals("Zoë <z@example.org>", HeaderParser.format(listOf(list[1])), "decoded, not RFC 2047")
    }

    @Test
    fun dateFallsBackToTheHeaderThenNow() {
        assertEquals(1_792_142_043_000, parse("Date: Thu, 16 Oct 2026 09:14:03 +0000\n\n", internalDate = null).date)
        assertEquals(5, parse("Subject: x\n\n", internalDate = null).date)
    }

    @Test
    fun attachmentsAreGuessedFromMultipartMixed() {
        assertEquals(1, parse("Content-Type: multipart/mixed; boundary=\"b\"\n\n").attachments)
        assertEquals(0, parse("Content-Type: multipart/alternative; boundary=\"b\"\n\n").attachments)
        assertEquals(0, parse("Subject: x\n\n").attachments)
    }

    @Test
    fun gmailReadsOnlyTheFirstAuthenticationResults() {
        val header = """
            Authentication-Results: mx.google.com; dkim=pass; spf=pass; dmarc=pass (p=REJECT) header.from=example.org
            Authentication-Results: mx.google.com; dmarc=fail

        """.trimIndent()
        assertEquals(Auth.PASS, parse(header, gmail = true).auth)
        val forged = "Authentication-Results: evil.example; dmarc=pass\nAuthentication-Results: mx.google.com; dmarc=pass\n\n"
        assertEquals(Auth.UNKNOWN, parse(forged, gmail = true).auth, "only the topmost header counts")
        assertEquals(Auth.UNKNOWN, parse(header, gmail = false).auth, "Riseup: never")
    }

    @Test
    fun controlCharactersAndMissingHeadersAreSafe() {
        val m = parse("Subject: a\u0007b\nFrom: nobody\n\n")
        assertEquals("a b", m.subject)
        assertEquals("", m.fromEmail)
        assertNull(m.messageId)
        assertNull(m.unsubscribe)
        assertFalse(m.unsubscribePost)
        val noSection = HeaderParser.newMessage(item("", key = "1"), 1, 2, false, null, true, 5)
        assertEquals("", noSection.subject)
    }

    @Test
    fun serverFlags() {
        val flags = HeaderParser.serverFlags(item("", flags = setOf("\\Flagged", "\\Draft")), setOf(4L))!!
        assertTrue(flags.flagged)
        assertTrue(flags.draft)
        assertFalse(flags.seen)
        assertEquals(9, flags.modSeq)
        assertEquals(setOf(4L), flags.labels)
        assertNull(HeaderParser.serverFlags(item("", flags = null), null))
    }

    @Test
    fun fetchItems() {
        val riseup = HeaderParser.items(modSeq = true, gmMsgId = false, gmLabels = false)
        assertTrue(riseup.startsWith("UID FLAGS INTERNALDATE RFC822.SIZE MODSEQ BODY.PEEK[HEADER.FIELDS (DATE FROM TO CC BCC "))
        assertTrue(riseup.endsWith("LIST-UNSUBSCRIBE LIST-UNSUBSCRIBE-POST AUTHENTICATION-RESULTS)]"))
        assertEquals(
            "$riseup X-GM-MSGID X-GM-LABELS",
            HeaderParser.items(modSeq = true, gmMsgId = true, gmLabels = true),
        )
        assertFalse("MODSEQ" in HeaderParser.items(modSeq = false, gmMsgId = false, gmLabels = false))
        assertEquals("UID FLAGS X-GM-LABELS", HeaderParser.flagItems(gmLabels = true))
    }
}
