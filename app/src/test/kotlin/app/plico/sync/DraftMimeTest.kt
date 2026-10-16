package app.plico.sync

import app.plico.data.Draft
import app.plico.data.DraftAttachment
import app.plico.data.DraftState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DraftMimeTest {
    private val dir: File = Files.createTempDirectory("outbox").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun draft(
        to: String = "Bob <bob@example.org>",
        cc: String = "",
        bcc: String = "secret@example.org",
        attachments: List<DraftAttachment> = emptyList(),
    ) = Draft(
        id = 1, accountId = 1, messageId = "<ID-1@riseup.net>", fromEmail = "me@riseup.net", toList = to, ccList = cc,
        bccList = bcc, subject = "Plan", body = "Hello\n", inReplyTo = "<p@x>", refs = "<r@x> <p@x>",
        sourceMessage = null, attachments = attachments, onServer = false, state = DraftState.EDITING, sendAt = 0,
        attempts = 0, error = null, updated = 0,
    )

    private fun text(bytes: ByteArray) = bytes.toString(Charsets.UTF_8)

    @Test
    fun buildsTheWireMessageAndTheStoredCopy() {
        val builder = DraftMime.builder(draft(), "Me", dir, strict = true, date = 1_792_142_099_000)
        val wire = text(builder.build(includeBcc = false))
        assertTrue(wire.startsWith("Date: Fri, 16 Oct 2026 09:14:59 +0000\r\nFrom: Me <me@riseup.net>\r\n"), wire)
        assertTrue("Message-ID: <ID-1@riseup.net>\r\n" in wire)
        assertTrue("In-Reply-To: <p@x>\r\n" in wire)
        assertTrue("References: <r@x> <p@x>\r\n" in wire)
        assertFalse("Bcc:" in wire)
        assertTrue("Bcc: secret@example.org\r\n" in text(builder.build(includeBcc = true)))
    }

    @Test
    fun attachmentsAreReadFromTheOutbox() {
        File(dir, "a.txt").writeText("data")
        val d = draft(attachments = listOf(DraftAttachment("a.txt", "Report.txt", "text/plain", 4)))
        val wire = text(DraftMime.builder(d, null, dir, strict = true, date = 0).build(includeBcc = false))
        assertTrue("filename=\"Report.txt\"" in wire)
        assertTrue("ZGF0YQ==" in wire)
        val missing = draft(attachments = listOf(DraftAttachment("gone.bin", "x", "text/plain", 4)))
        assertFailsWith<java.io.IOException> { DraftMime.builder(missing, null, dir, strict = true, date = 0) }
    }

    @Test
    fun strictModeRefusesInvalidAddressesDraftsDropThem() {
        val d = draft(to = "bob@example.org, half@, \"x\" <bad address>")
        assertFailsWith<IllegalArgumentException> { DraftMime.builder(d, null, dir, strict = true, date = 0).build(false) }
        val stored = text(DraftMime.builder(d, null, dir, strict = false, date = 0).build(includeBcc = true))
        assertTrue("To: bob@example.org\r\n" in stored, stored)
    }

    @Test
    fun recipientsAreUnique() {
        val d = draft(to = "a@x.org, B@x.org", cc = "b@x.org", bcc = "c@x.org, A@X.org")
        assertEquals(listOf("a@x.org", "B@x.org", "c@x.org"), DraftMime.recipients(d))
    }

    @Test
    fun sizeEstimateCountsBase64() {
        val d = draft(attachments = listOf(DraftAttachment("f", "f", "image/png", 1_000_000)))
        assertEquals((4_096L + 6 + 1_000_000) * 137 / 100, DraftMime.estimatedSize(d))
    }
}
