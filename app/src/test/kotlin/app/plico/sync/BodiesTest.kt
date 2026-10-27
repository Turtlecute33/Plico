package app.plico.sync

import app.plico.data.MessageRef
import app.plico.mail.imap.ImapReader
import app.plico.mail.imap.ImapValue
import app.plico.mail.imap.Untagged
import app.plico.mail.mime.MimePart
import app.plico.mail.mime.parseBodyStructure
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodiesTest {
    /** Parses `BODYSTRUCTURE <value>` from a FETCH line. */
    private fun structure(value: String): MimePart {
        val line = "* 1 FETCH (UID 1 BODYSTRUCTURE $value)\r\n"
        val r = ImapReader(ByteArrayInputStream(line.toByteArray())).read() as Untagged
        val items = (r.values[0] as ImapValue.ListV).items
        return parseBodyStructure(items[3])
    }

    private val plain = """("text" "plain" ("charset" "iso-8859-1") NIL NIL "quoted-printable" 20 1 NIL NIL NIL NIL)"""

    @Test
    fun plainTextWithCharset() {
        val plan = BodyPlan(structure(plain))
        assertEquals(listOf("1"), plan.sections)
        val body = assertNotNull(plan.assemble(mapOf("1" to "Gr=FC=DFe\r\n\r\n> old\r\n-- \r\nSig".toByteArray())))
        assertNull(body.html)
        assertEquals("Grüße\n\n> old\n-- \nSig", body.text)
        assertEquals("Grüße", body.snippet)
        assertEquals(emptyList(), body.attachments)
    }

    @Test
    fun alternativeTakesHtml() {
        val plan = BodyPlan(
            structure(
                """(("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 5 1)("text" "html" ("charset" "utf-8") NIL NIL "base64" 40 1) "alternative")""",
            ),
        )
        assertEquals(listOf("2"), plan.sections)
        val html = java.util.Base64.getMimeEncoder().encodeToString("<p>Hello <b>you</b></p>".toByteArray())
        val body = assertNotNull(plan.assemble(mapOf("2" to html.toByteArray())))
        assertEquals("<p>Hello <b>you</b></p>", body.html)
        assertEquals("Hello you", body.text)
        assertEquals("Hello you", body.snippet)
    }

    @Test
    fun mixedListsAttachmentsAndUnreferencedImages() {
        val plan = BodyPlan(
            structure(
                """((("text" "html" ("charset" "utf-8") NIL NIL "7bit" 60 1)""" +
                    """("image" "png" ("name" "logo.png") "<logo@x>" NIL "base64" 100)""" +
                    """("image" "gif" NIL "<unused@x>" NIL "base64" 50) "related")""" +
                    """("application" "pdf" ("name" "Report.pdf") NIL NIL "base64" 3000 NIL ("attachment" ("filename" "Report.pdf"))) "mixed")""",
            ),
        )
        assertEquals(listOf("1.1"), plan.sections)
        val body = assertNotNull(plan.assemble(mapOf("1.1" to "<p>Hi</p><img src=\"cid:logo@x\">".toByteArray())))
        val shown = body.attachments.map { Triple(it.section, it.name, it.inline) }
        assertEquals(
            listOf(Triple("1.3", "image.gif", false), Triple("2", "Report.pdf", false), Triple("1.2", "logo.png", true)),
            shown,
        )
        assertEquals("logo@x", body.attachments.last().cid)
        assertEquals("application/pdf", body.attachments[1].mime)
        assertEquals("base64", body.attachments[1].encoding)
        assertEquals(3000, body.attachments[1].size)
    }

    @Test
    fun hugeTextPartIsAnAttachment() {
        val size = BodyPlan.MAX_TEXT_PART + 1
        val plan = BodyPlan(structure("""("text" "plain" ("charset" "utf-8") NIL NIL "7bit" $size 1)"""))
        assertEquals(emptyList(), plan.sections)
        val body = assertNotNull(plan.assemble(emptyMap()))
        assertEquals("", body.text)
        assertEquals(listOf("message.txt"), body.attachments.map { it.name })
    }

    @Test
    fun manyTextPartsBecomeAttachmentsAfterTheCap() {
        val part = """("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 5 1)"""
        val plan = BodyPlan(structure("(" + part.repeat(500) + " \"mixed\")"))
        val first = (1..BodyPlan.MAX_TEXT_PARTS).map { it.toString() }
        assertEquals(first, plan.sections)
        val body = assertNotNull(plan.assemble(first.associateWith { "Hi $it".toByteArray() }))
        assertEquals(500 - BodyPlan.MAX_TEXT_PARTS, body.attachments.size)
        assertEquals("${BodyPlan.MAX_TEXT_PARTS + 1}", body.attachments.first().section)
        assertTrue(body.attachments.all { it.name == "message.txt" && !it.inline })
    }

    @Test
    fun failedBatchGoesAloneThenSkips() {
        val rows = (1L..30L).map { MessageRef(it, 1, 100 + it) }
        val skips = BodySkips()
        assertEquals(listOf(25, 5), skips.batches(rows, 30, 25).map { it.size })
        // A batch that failed: its rows go alone, before the others.
        skips.failed(rows.take(25))
        val next = skips.batches(rows, 30, 25)
        assertEquals(26, next.size)
        assertEquals(rows.take(25).map { listOf(it) }, next.take(25))
        assertEquals(rows.drop(25), next.last())
        // A row that fails alone is skipped; a row that passes alone joins the batches again.
        skips.failed(listOf(rows[3]))
        skips.passed(rows.take(25).filter { it != rows[3] })
        assertEquals(1, skips.skipped)
        val after = skips.batches(rows, 30, 25)
        assertEquals(listOf(rows.filter { it != rows[3] }.take(25), rows.drop(26)), after)
        assertEquals(listOf(rows[0]), skips.batches(listOf(rows[3], rows[0]), 1, 25).single())
    }

    @Test
    fun missingDataMeansNoBody() {
        assertNull(BodyPlan(structure(plain)).assemble(emptyMap()))
    }

    @Test
    fun namesForUnnamedParts() {
        val rfc822 = structure("""("message" "rfc822" NIL NIL NIL "7bit" 100)""")
        assertEquals("message.eml", BodyPlan.defaultName(rfc822))
        assertEquals("image.jpeg", BodyPlan.defaultName(structure("""("image" "jpeg" NIL NIL NIL "base64" 10)""")))
        assertEquals("attachment", BodyPlan.defaultName(structure("""("application" "x-weird+zip" NIL NIL NIL "base64" 10)""")))
        assertTrue(BodyPlan.defaultName(structure("""("text" "calendar" NIL NIL NIL "7bit" 10 1)""")).endsWith(".ics"))
    }
}
