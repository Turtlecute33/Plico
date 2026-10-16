package app.plico.mail.mime

import app.plico.mail.imap.ImapReader
import app.plico.mail.imap.ImapValue
import app.plico.mail.imap.Untagged
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyStructureTest {

    /** Sections of each [DisplayParts] list, for readable failures. */
    private data class Shown(
        val html: List<String> = emptyList(),
        val plain: List<String> = emptyList(),
        val inline: List<String> = emptyList(),
        val attachments: List<String> = emptyList(),
    )

    private fun shown(part: MimePart): Shown {
        val d = part.displayParts()
        fun List<MimePart>.sections() = map { it.section }
        return Shown(d.html.sections(), d.plain.sections(), d.inline.sections(), d.attachments.sections())
    }

    /**
     * Reads a server FETCH line through [ImapReader] and parses its BODYSTRUCTURE. Fixtures are wrapped at
     * spaces; each line break becomes one space, which the reader skips like the server's own.
     */
    private fun fetch(structure: String): MimePart = fetchLine(structure.trimIndent().replace("\n", " "))

    private fun fetchLine(structure: String): MimePart {
        val line = "* 12 FETCH (UID 4827 BODYSTRUCTURE $structure FLAGS (\\Seen))\r\n"
        val response = assertIs<Untagged>(ImapReader(ByteArrayInputStream(line.toByteArray())).read())
        assertEquals("FETCH", response.keyword)
        val items = assertIs<ImapValue.ListV>(response.values.single()).items
        return parseBodyStructure(items[items.indexOf(ImapValue.Atom("BODYSTRUCTURE")) + 1])
    }

    /** An IMAP literal, as Gmail sends 8-bit strings. */
    private fun literal(s: String) = "{${s.toByteArray().size}}\r\n$s"

    private fun MimePart.at(section: String): MimePart = find(section) ?: error("No part $section")

    private fun MimePart.find(section: String): MimePart? =
        if (this.section == section) this else children.firstNotNullOfOrNull { it.find(section) }

    private fun part(
        section: String,
        mime: String,
        vararg children: MimePart,
        cid: String? = null,
        name: String? = null,
    ) = MimePart(
        section, mime.substringBefore('/'), mime.substringAfter('/'), emptyMap(), cid, "7bit", 10,
        null, name?.let { mapOf("filename" to it) }.orEmpty(), children.toList(),
    )

    // Gmail

    @Test
    fun gmailPlain() {
        val p = fetch("""("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 1234 20 NIL NIL NIL)""")
        assertEquals(
            MimePart(
                "1", "text", "plain", mapOf("charset" to "UTF-8"), null, "7bit", 1234, null, emptyMap(), emptyList(),
            ),
            p,
        )
        assertEquals(Shown(plain = listOf("1")), shown(p))
    }

    @Test
    fun gmailAlternative() {
        val p = fetch(
            """
            (("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "QUOTED-PRINTABLE" 397 10 NIL NIL NIL)
            ("TEXT" "HTML" ("CHARSET" "UTF-8") NIL NIL "QUOTED-PRINTABLE" 1185 24 NIL NIL NIL)
            "ALTERNATIVE" ("BOUNDARY" "000000000000a1b2c3d4e5f60708") NIL NIL)
            """,
        )
        val plain = MimePart(
            "1", "text", "plain", mapOf("charset" to "UTF-8"), null, "quoted-printable", 397,
            null, emptyMap(), emptyList(),
        )
        val html = plain.copy(section = "2", subtype = "html", size = 1185)
        assertEquals(
            MimePart(
                "", "multipart", "alternative", mapOf("boundary" to "000000000000a1b2c3d4e5f60708"), null, "7bit",
                1582, null, emptyMap(), listOf(plain, html),
            ),
            p,
        )
        assertTrue(p.isMultipart)
        assertEquals(Shown(html = listOf("2")), shown(p))
    }

    @Test
    fun gmailAttachmentsWithEncodedNames() {
        val p = fetch(
            """
            ((("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 52 2 NIL NIL NIL)
            ("TEXT" "HTML" ("CHARSET" "UTF-8") NIL NIL "QUOTED-PRINTABLE" 175 4 NIL NIL NIL)
            "ALTERNATIVE" ("BOUNDARY" "000000000000f1e2d3c4b5a69788") NIL NIL)
            ("APPLICATION" "PDF" ("NAME" "=?UTF-8?B?xb1sdcWlb3XEjWvDvSBrxa/FiC5wZGY=?=") "<f_m2k9x7p10>" NIL
            "BASE64" 52340 NIL ("ATTACHMENT" ("FILENAME" "=?UTF-8?B?xb1sdcWlb3XEjWvDvSBrxa/FiC5wZGY=?=")) NIL)
            ("APPLICATION" "VND.OPENXMLFORMATS-OFFICEDOCUMENT.SPREADSHEETML.SHEET"
            ("NAME*" "utf-8''Rozpo%C4%8Det%202026.xlsx") NIL NIL "BASE64" 8812
            NIL ("ATTACHMENT" ("FILENAME*0*" "utf-8''Rozpo%C4%8Det" "FILENAME*1*" "%202026.xlsx")) NIL)
            ("IMAGE" "JPEG" ("NAME" "IMG_20261012_101500.jpg") "<f_m2k9x7p32>" NIL "BASE64" 240330
            NIL ("ATTACHMENT" ("FILENAME" "IMG_20261012_101500.jpg")) NIL)
            "MIXED" ("BOUNDARY" "000000000000a9b8c7d6e5f40312") NIL NIL)
            """,
        )
        val pdf = p.at("2")
        assertEquals("application/pdf", pdf.mimeType)
        assertEquals("Žluťoučký kůň.pdf", pdf.fileName)
        assertEquals("Žluťoučký kůň.pdf", pdf.params["name"])
        assertEquals("f_m2k9x7p10", pdf.contentId)
        assertEquals("base64", pdf.encoding)
        assertEquals("attachment", pdf.disposition)

        val sheet = p.at("3")
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", sheet.mimeType)
        assertEquals("Rozpočet 2026.xlsx", sheet.fileName)
        assertEquals(mapOf("filename" to "Rozpočet 2026.xlsx"), sheet.dispositionParams)
        assertEquals("Rozpočet 2026.xlsx", sheet.params["name"])

        // Gmail gives every attachment a Content-ID; the attachment disposition keeps the photo an attachment.
        assertEquals("f_m2k9x7p32", p.at("4").contentId)
        assertEquals(Shown(html = listOf("1.2"), attachments = listOf("2", "3", "4")), shown(p))
    }

    @Test
    fun gmailLiteralFileName() {
        val name = "Fotka z výletu.jpg"
        val p = fetchLine(
            "(\"IMAGE\" \"JPEG\" (\"NAME\" ${literal(name)}) NIL NIL \"BASE64\" 1000 NIL " +
                "(\"ATTACHMENT\" (\"FILENAME\" ${literal(name)})) NIL)",
        )
        assertEquals(name, p.fileName)
        assertEquals(Shown(attachments = listOf("1")), shown(p))
    }

    @Test
    fun gmailInlineImageInRelated() {
        val p = fetch(
            """
            (((("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 40 2 NIL NIL NIL)
            ("TEXT" "HTML" ("CHARSET" "UTF-8") NIL NIL "QUOTED-PRINTABLE" 600 9 NIL NIL NIL)
            "ALTERNATIVE" ("BOUNDARY" "000000000000c3") NIL NIL)
            ("IMAGE" "PNG" ("NAME" "image.png") "<ii_m2kab1c20>" NIL "BASE64" 30210
            NIL ("INLINE" ("FILENAME" "image.png")) NIL)
            "RELATED" ("BOUNDARY" "000000000000c2") NIL NIL)
            ("APPLICATION" "PDF" ("NAME" "a.pdf") "<f_m2kab1c31>" NIL "BASE64" 9100
            NIL ("ATTACHMENT" ("FILENAME" "a.pdf")) NIL)
            "MIXED" ("BOUNDARY" "000000000000c1") NIL NIL)
            """,
        )
        assertEquals("ii_m2kab1c20", p.at("1.2").contentId)
        assertEquals("inline", p.at("1.2").disposition)
        assertEquals(Shown(html = listOf("1.1.2"), inline = listOf("1.2"), attachments = listOf("2")), shown(p))
    }

    @Test
    fun gmailCalendarInvite() {
        val p = fetch(
            """
            ((("TEXT" "PLAIN" ("CHARSET" "UTF-8" "DELSP" "yes" "FORMAT" "flowed") NIL NIL "BASE64" 1730 23 NIL NIL NIL)
            ("TEXT" "HTML" ("CHARSET" "UTF-8") NIL NIL "QUOTED-PRINTABLE" 8826 167 NIL NIL NIL)
            ("TEXT" "CALENDAR" ("CHARSET" "UTF-8" "METHOD" "REQUEST") NIL NIL "7BIT" 1626 39 NIL NIL NIL)
            "ALTERNATIVE" ("BOUNDARY" "000000000000e1d2c3b4a5968778") NIL NIL)
            ("APPLICATION" "ICS" ("NAME" "invite.ics") NIL NIL "BASE64" 2222
            NIL ("ATTACHMENT" ("FILENAME" "invite.ics")) NIL)
            "MIXED" ("BOUNDARY" "000000000000d0c1b2a3948576") NIL NIL)
            """,
        )
        assertEquals(mapOf("charset" to "UTF-8", "method" to "REQUEST"), p.at("1.3").params)
        assertEquals(Shown(html = listOf("1.2"), attachments = listOf("1.3", "2")), shown(p))
    }

    @Test
    fun outlookCalendarInviteInAlternative() {
        val p = fetch(
            """
            (("text" "plain" ("charset" "utf-8") NIL NIL "quoted-printable" 300 10 NIL NIL NIL NIL)
            ("text" "html" ("charset" "utf-8") NIL NIL "quoted-printable" 2900 60 NIL NIL NIL NIL)
            ("text" "calendar" ("charset" "utf-8" "method" "REQUEST") NIL NIL "base64" 4100 53 NIL NIL NIL NIL)
            "alternative" ("boundary" "_002_") NIL NIL NIL)
            """,
        )
        assertEquals(Shown(html = listOf("2"), attachments = listOf("3")), shown(p))
    }

    @Test
    fun gmailForwardedMessageAttachment() {
        val p = fetch(
            """
            (("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 3 1 NIL NIL NIL)
            ("MESSAGE" "RFC822" NIL NIL NIL "7BIT" 2861
            ("Mon, 12 Oct 2026 09:14:03 +0200" "Trip plan" (("Alice" NIL "alice" "example.org"))
            (("Alice" NIL "alice" "example.org")) (("Alice" NIL "alice" "example.org"))
            ((NIL NIL "bob" "example.net")) NIL NIL NIL "<4f2a9c1e@example.org>")
            (("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 120 4 NIL NIL NIL)
            ("TEXT" "HTML" ("CHARSET" "UTF-8") NIL NIL "7BIT" 480 12 NIL NIL NIL)
            "ALTERNATIVE" ("BOUNDARY" "000000000000b0") NIL NIL)
            71 NIL ("ATTACHMENT" ("FILENAME" "Trip plan.eml")) NIL)
            "MIXED" ("BOUNDARY" "000000000000a0") NIL NIL)
            """,
        )
        val message = p.at("2")
        assertEquals("message/rfc822", message.mimeType)
        assertEquals(2861L, message.size)
        assertEquals("attachment", message.disposition)
        assertEquals("Trip plan.eml", message.fileName)
        assertEquals(emptyList(), message.children)
        assertEquals(Shown(plain = listOf("1"), attachments = listOf("2")), shown(p))
    }

    // Dovecot (Riseup)

    @Test
    fun dovecotNestedThunderbirdMessage() {
        val p = fetch(
            """
            ((("text" "plain" ("charset" "UTF-8" "format" "flowed") NIL NIL "8bit" 312 9 NIL NIL NIL NIL)
            (("text" "html" ("charset" "UTF-8") NIL NIL "8bit" 1046 27 NIL NIL NIL NIL)
            ("image" "png" ("name" "map.png") "<part1.Xk3vQm9R.Lp2wZb7N@riseup.net>" NIL "base64" 48212
            NIL ("inline" ("filename" "map.png")) NIL NIL)
            "related" ("boundary" "------------8sQ1dP0kLmZ3vXb7Tn4yRc2W") NIL NIL NIL)
            "alternative" ("boundary" "------------J5hT2wQe9LrM6xZp0VbN3kYs") NIL NIL NIL)
            ("application" "pdf" ("name" "itinerary.pdf") NIL NIL "base64" 183406
            NIL ("attachment" ("filename" "itinerary.pdf")) NIL NIL)
            "mixed" ("boundary" "------------Wc4nV7bT1yQ8rK2mZ5xL0pHd") NIL "en-US" NIL)
            """,
        )
        assertEquals("", p.section)
        assertEquals("multipart/mixed", p.mimeType)
        assertEquals(listOf("1", "2"), p.children.map { it.section })
        assertEquals(listOf("1.1", "1.2"), p.at("1").children.map { it.section })
        assertEquals(listOf("1.2.1", "1.2.2"), p.at("1.2").children.map { it.section })
        assertEquals("multipart/related", p.at("1.2").mimeType)
        assertEquals(mapOf("charset" to "UTF-8", "format" to "flowed"), p.at("1.1").params)

        val image = p.at("1.2.2")
        assertEquals("part1.Xk3vQm9R.Lp2wZb7N@riseup.net", image.contentId)
        assertEquals("inline", image.disposition)
        assertEquals("map.png", image.fileName)
        assertEquals(48212L, image.size)
        assertEquals(312 + 1046 + 48212 + 183406L, p.size)

        assertEquals(Shown(html = listOf("1.2.1"), inline = listOf("1.2.2"), attachments = listOf("2")), shown(p))
    }

    @Test
    fun dovecotPostfixBounceReport() {
        val p = fetch(
            """
            (("text" "plain" ("charset" "us-ascii") NIL "Notification" "7bit" 612 15 NIL NIL NIL NIL)
            ("message" "delivery-status" NIL NIL "Delivery report" "7bit" 452 NIL NIL NIL NIL)
            ("message" "rfc822" NIL NIL "Undelivered Message" "8bit" 2318
            ("Mon, 12 Oct 2026 09:14:03 +0200" "Hello" ((NIL NIL "me" "riseup.net")) ((NIL NIL "me" "riseup.net"))
            ((NIL NIL "me" "riseup.net")) ((NIL NIL "nobody" "example.invalid")) NIL NIL NIL "<x1@riseup.net>")
            ("text" "plain" ("charset" "utf-8") NIL NIL "8bit" 40 2 NIL NIL NIL NIL) 58 NIL NIL NIL NIL)
            "report" ("report-type" "delivery-status" "boundary" "4C1F2A0B3D.1760000000/mx1.riseup.net") NIL NIL NIL)
            """,
        )
        assertEquals("delivery-status", p.params["report-type"])
        assertEquals(Shown(plain = listOf("1"), attachments = listOf("2", "3")), shown(p))
    }

    @Test
    fun gmailBounceReportWithRelatedFirstPart() {
        val p = fetch(
            """
            (((("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 300 8 NIL NIL NIL)
            ("TEXT" "HTML" ("CHARSET" "UTF-8") NIL NIL "QUOTED-PRINTABLE" 2100 40 NIL NIL NIL)
            "ALTERNATIVE" ("BOUNDARY" "c") NIL NIL)
            ("IMAGE" "PNG" ("NAME" "icon.png") "<icon.png>" NIL "BASE64" 1460
            NIL ("INLINE" ("FILENAME" "icon.png")) NIL)
            "RELATED" ("BOUNDARY" "b") NIL NIL)
            ("MESSAGE" "DELIVERY-STATUS" NIL NIL NIL "7BIT" 500 NIL NIL NIL)
            ("TEXT" "RFC822-HEADERS" NIL NIL NIL "7BIT" 900 20 NIL NIL NIL)
            "REPORT" ("REPORT-TYPE" "delivery-status" "BOUNDARY" "a") NIL NIL)
            """,
        )
        assertEquals("icon.png", p.at("1.2").contentId)
        assertEquals(Shown(html = listOf("1.1.2"), inline = listOf("1.2"), attachments = listOf("2", "3")), shown(p))
    }

    @Test
    fun dovecotBase64MessageWithoutEnvelope() {
        // Dovecot does not parse a base64 message/rfc822 and sends it as a basic part.
        val p = fetch(
            """
            ("message" "rfc822" ("name" "old.eml") NIL NIL "base64" 4000
            NIL ("attachment" ("filename" "old.eml")) NIL NIL)
            """,
        )
        assertEquals("attachment", p.disposition)
        assertEquals("old.eml", p.fileName)
        assertEquals(Shown(attachments = listOf("1")), shown(p))
    }

    @Test
    fun dovecotEmptyMultipartPlaceholder() {
        // A multipart without parts: Dovecot writes one empty text/plain part.
        val p = fetch(
            """
            (("text" "plain" ("charset" "us-ascii") NIL NIL "7bit" 0 0 NIL NIL NIL NIL)
            "mixed" ("boundary" "lost") NIL NIL NIL)
            """,
        )
        assertEquals(0L, p.size)
        assertEquals(Shown(plain = listOf("1")), shown(p))
    }

    // Other mailers

    @Test
    fun appleMailMixedHtmlImageHtml() {
        val p = fetch(
            """
            (("text" "html" ("charset" "us-ascii") NIL NIL "quoted-printable" 412 9 NIL NIL NIL NIL)
            ("image" "jpeg" ("name" "IMG_0042.jpeg" "x-unix-mode" "0644") NIL NIL "base64" 1418770
            NIL ("inline" ("filename" "IMG_0042.jpeg")) NIL NIL)
            ("text" "html" ("charset" "us-ascii") NIL NIL "quoted-printable" 288 7 NIL NIL NIL NIL)
            "mixed" ("boundary" "Apple-Mail=_5C0E1E3B-7D4F-4C1A-9A0E-2B6D8F3E1A47") NIL NIL NIL)
            """,
        )
        assertEquals("0644", p.at("2").params["x-unix-mode"])
        assertEquals(Shown(html = listOf("1", "3"), attachments = listOf("2")), shown(p))
    }

    @Test
    fun appleMailMixedInsideAlternative() {
        val p = fetch(
            """
            (("text" "plain" ("charset" "us-ascii") NIL NIL "quoted-printable" 96 6 NIL NIL NIL NIL)
            (("text" "html" ("charset" "us-ascii") NIL NIL "quoted-printable" 412 9 NIL NIL NIL NIL)
            ("image" "jpeg" ("name" "IMG_0042.jpeg" "x-unix-mode" "0644") NIL NIL "base64" 1418770
            NIL ("inline" ("filename" "IMG_0042.jpeg")) NIL NIL)
            ("text" "html" ("charset" "us-ascii") NIL NIL "quoted-printable" 288 7 NIL NIL NIL NIL)
            "mixed" ("boundary" "Apple-Mail=_5C0E1E3B-7D4F-4C1A-9A0E-2B6D8F3E1A47") NIL NIL NIL)
            "alternative" ("boundary" "Apple-Mail=_0F7A2C61-3B9E-4D25-8E14-6A5C9B2D7F30") NIL NIL NIL)
            """,
        )
        assertEquals(Shown(html = listOf("2.1", "2.3"), attachments = listOf("2.2")), shown(p))
    }

    @Test
    fun mailmanFooter() {
        val p = fetch(
            """
            (("text" "plain" ("charset" "utf-8") NIL NIL "8bit" 1840 41 NIL ("inline" NIL) NIL NIL)
            ("text" "plain" ("charset" "us-ascii") NIL NIL "7bit" 198 4 NIL ("inline" NIL) NIL NIL)
            "mixed" ("boundary" "===============0123456789012345678==") NIL NIL NIL)
            """,
        )
        assertEquals("inline", p.at("2").disposition)
        assertEquals(emptyMap(), p.at("2").dispositionParams)
        assertEquals(Shown(plain = listOf("1", "2")), shown(p))
    }

    @Test
    fun mailmanFooterAfterAlternative() {
        val p = fetch(
            """
            ((("text" "plain" ("charset" "utf-8") NIL NIL "quoted-printable" 900 20 NIL NIL NIL NIL)
            ("text" "html" ("charset" "utf-8") NIL NIL "quoted-printable" 3100 50 NIL NIL NIL NIL)
            "alternative" ("boundary" "inner") NIL NIL NIL)
            ("text" "plain" ("charset" "us-ascii") NIL NIL "7bit" 198 4 NIL ("inline" NIL) NIL NIL)
            "mixed" ("boundary" "===============7777777777777777777==") NIL NIL NIL)
            """,
        )
        assertEquals(Shown(html = listOf("1.2"), plain = listOf("2")), shown(p))
        assertEquals(listOf("1.2", "2"), p.displayParts().textParts.map { it.section })
    }

    @Test
    fun thunderbirdForwardAsAttachment() {
        val p = fetch(
            """
            (("text" "plain" ("charset" "UTF-8" "format" "flowed") NIL NIL "7bit" 28 2 NIL NIL NIL NIL)
            ("message" "rfc822" ("name" "Trip plan.eml") NIL NIL "7bit" 2861
            ("Mon, 12 Oct 2026 09:14:03 +0200" "Trip plan" (("Alice" NIL "alice" "example.org"))
            (("Alice" NIL "alice" "example.org")) (("Alice" NIL "alice" "example.org"))
            ((NIL NIL "bob" "example.net")) NIL NIL NIL "<4f2a9c1e@example.org>")
            ((("text" "plain" ("charset" "UTF-8") NIL NIL "7bit" 120 4 NIL NIL NIL NIL)
            ("text" "html" ("charset" "UTF-8") NIL NIL "7bit" 480 12 NIL NIL NIL NIL)
            "alternative" ("boundary" "------------abc") NIL NIL NIL)
            ("image" "png" ("name" "x.png") NIL NIL "base64" 900 NIL ("attachment" ("filename" "x.png")) NIL NIL)
            "mixed" ("boundary" "------------def") NIL NIL NIL)
            71 NIL ("attachment" ("filename" "Trip plan.eml")) NIL NIL)
            "mixed" ("boundary" "------------Q2w8E4r6T0y1U3i5O7p9A1sD") NIL NIL NIL)
            """,
        )
        val message = p.at("2")
        assertEquals("rfc822", message.subtype)
        assertEquals("7bit", message.encoding)
        assertEquals("attachment", message.disposition)
        assertEquals(emptyList(), message.children)
        assertEquals(Shown(plain = listOf("1"), attachments = listOf("2")), shown(p))
    }

    @Test
    fun smimeSigned() {
        val p = fetch(
            """
            ((("text" "plain" ("charset" "UTF-8") NIL NIL "quoted-printable" 342 12 NIL NIL NIL NIL)
            ("text" "html" ("charset" "UTF-8") NIL NIL "quoted-printable" 1290 30 NIL NIL NIL NIL)
            "alternative" ("boundary" "------------B7n2Kq") NIL NIL NIL)
            ("application" "pkcs7-signature" ("name" "smime.p7s") NIL "S/MIME Cryptographic Signature" "base64" 5482
            NIL ("attachment" ("filename" "smime.p7s")) NIL NIL)
            "signed" ("protocol" "application/pkcs7-signature" "micalg" "sha-256" "boundary" "------------ms0123")
            NIL NIL NIL)
            """,
        )
        assertEquals("application/pkcs7-signature", p.params["protocol"])
        assertEquals(Shown(html = listOf("1.2")), shown(p))
    }

    @Test
    fun pgpSignedWithAttachment() {
        val p = fetch(
            """
            ((("text" "plain" ("charset" "utf-8") NIL NIL "quoted-printable" 210 7 NIL NIL NIL NIL)
            ("application" "pdf" ("name" "report.pdf") NIL NIL "base64" 70210
            NIL ("attachment" ("filename" "report.pdf")) NIL NIL)
            "mixed" ("boundary" "------------Y8u3") NIL NIL NIL)
            ("application" "pgp-signature" ("name" "OpenPGP_signature.asc") NIL "OpenPGP digital signature" "7bit" 665
            NIL ("attachment" ("filename" "OpenPGP_signature.asc")) NIL NIL)
            "signed" ("micalg" "pgp-sha256" "protocol" "application/pgp-signature" "boundary" "------------Ppg1")
            NIL NIL NIL)
            """,
        )
        assertEquals(Shown(plain = listOf("1.1"), attachments = listOf("1.2")), shown(p))
    }

    // Tolerance

    @Test
    fun nilParamsAndNoExtensionData() {
        val p = fetch("""("text" "plain" NIL NIL NIL "7bit" 0 0)""")
        assertEquals(MimePart("1", "text", "plain", emptyMap(), null, "7bit", 0, null, emptyMap(), emptyList()), p)
        assertEquals(Shown(plain = listOf("1")), shown(p))
    }

    @Test
    fun nonExtensibleBodyForm() {
        val p = fetch("""(("text" "plain" NIL NIL NIL "7bit" 10 1)("image" "gif" NIL NIL NIL "base64" 100) "mixed")""")
        assertEquals("mixed", p.subtype)
        assertEquals(emptyMap(), p.params)
        assertNull(p.at("2").disposition)
        assertEquals(Shown(plain = listOf("1"), attachments = listOf("2")), shown(p))
    }

    @Test
    fun upperAndMixedCase() {
        val p = fetch(
            """
            (("Text" "HTML" ("Charset" "ISO-8859-1") NIL NIL "Quoted-Printable" 10 1 NIL ("INLINE" NIL) NIL)
            ("APPLICATION" "OCTET-STREAM" ("NAME" "A.BIN") NIL NIL "BASE64" 5
            NIL ("Attachment" ("FileName" "A.BIN")) NIL)
            "Mixed" ("Boundary" "XyZ") NIL NIL)
            """,
        )
        assertEquals("mixed", p.subtype)
        assertEquals(mapOf("boundary" to "XyZ"), p.params)
        val html = p.at("1")
        assertEquals("text/html", html.mimeType)
        assertEquals(mapOf("charset" to "ISO-8859-1"), html.params)
        assertEquals("quoted-printable", html.encoding)
        assertEquals("inline", html.disposition)
        val bin = p.at("2")
        assertEquals("application/octet-stream", bin.mimeType)
        assertEquals("attachment", bin.disposition)
        assertEquals(mapOf("filename" to "A.BIN"), bin.dispositionParams)
        assertEquals(Shown(html = listOf("1"), attachments = listOf("2")), shown(p))
    }

    @Test
    fun notAListGivesTheDefaultTextPart() {
        val default = MimePart("1", "text", "plain", emptyMap(), null, "7bit", 0, null, emptyMap(), emptyList())
        assertEquals(default, parseBodyStructure(ImapValue.Nil))
        assertEquals(default, parseBodyStructure(ImapValue.ListV(emptyList())))
        assertEquals(default, parseBodyStructure(ImapValue.Atom("x")))
        assertEquals(default, fetch("(NIL NIL NIL NIL NIL NIL NIL)"))
    }

    @Test
    fun missingFieldsTakeDefaults() {
        assertEquals("application/octet-stream", fetch("""("application" NIL NIL NIL NIL NIL NIL)""").mimeType)
        assertEquals("text/plain", fetch("""(NIL "plain" NIL NIL NIL "7bit" 1 1)""").mimeType)
        assertEquals(0L, fetch("""("text" "plain" NIL NIL NIL "7bit" NIL 1)""").size)
        assertEquals(0L, fetch("""("text" "plain" NIL NIL NIL "7bit" "lots" 1)""").size)
        assertEquals(0L, fetch("""("text" "plain" NIL NIL NIL "7bit" -4 1)""").size)
        assertEquals(42L, fetch("""("text" "plain" NIL NIL NIL "7bit" "42" 1)""").size)
        assertEquals("7bit", fetch("""("image" "png" NIL NIL NIL NIL 5)""").encoding)
        // A multipart without a subtype is mixed.
        assertEquals("multipart/mixed", fetch("""(("text" "plain" NIL NIL NIL "7bit" 1 1))""").mimeType)
        assertEquals("multipart/mixed", fetch("""(("text" "plain" NIL NIL NIL "7bit" 1 1) NIL)""").mimeType)
    }

    @Test
    fun oddParameterLists() {
        assertEquals(emptyMap(), fetch("""("text" "plain" ("charset") NIL NIL "7bit" 1 1)""").params)
        assertEquals(
            mapOf("name" to "a.txt"),
            fetch("""("text" "plain" ("charset" NIL "name" "a.txt" ("x") "y") NIL NIL "7bit" 1 1)""").params,
        )
        assertEquals(emptyMap(), fetch("""("text" "plain" "charset" NIL NIL "7bit" 1 1)""").params)
        // RFC 2231 wins over the plain form of the same name.
        assertEquals(
            mapOf("filename" to "ü.txt"),
            fetch(
                """
                ("text" "plain" NIL NIL NIL "7bit" 1 1 NIL
                ("attachment" ("filename" "u.txt" "filename*" "utf-8''%C3%BC.txt")) NIL NIL)
                """,
            ).dispositionParams,
        )
    }

    @Test
    fun contentIdWithoutBrackets() {
        assertEquals("ii_abc", fetch("""("image" "png" NIL "ii_abc" NIL "base64" 1)""").contentId)
        assertEquals("a@b", fetch("""("image" "png" NIL " <a@b> " NIL "base64" 1)""").contentId)
        assertNull(fetch("""("image" "png" NIL "<>" NIL "base64" 1)""").contentId)
        assertNull(fetch("""("image" "png" NIL "" NIL "base64" 1)""").contentId)
    }

    @Test
    fun extensionDataFoundByForm() {
        // Bare disposition string, as some servers send it.
        val bare = fetch("""("application" "pdf" NIL NIL NIL "base64" 10 NIL "attachment" NIL)""")
        assertEquals("attachment", bare.disposition)
        // Text part without a line count.
        val text = fetch("""("text" "plain" NIL NIL NIL "7bit" 10 NIL ("attachment" ("filename" "a.txt")) NIL NIL)""")
        assertEquals("a.txt", text.fileName)
        // Line count on a non-text part.
        val status = fetch("""("message" "delivery-status" NIL NIL NIL "7bit" 500 12 NIL ("inline" NIL) NIL NIL)""")
        assertEquals("inline", status.disposition)
        // Parsed message/rfc822 without a line count.
        val message = fetch(
            """
            ("message" "rfc822" NIL NIL NIL "7bit" 100 (NIL NIL NIL NIL NIL NIL NIL NIL NIL NIL)
            ("text" "plain" NIL NIL NIL "7bit" 10 1) NIL ("attachment" ("filename" "m.eml")) NIL NIL)
            """,
        )
        assertEquals("m.eml", message.fileName)
        assertEquals(emptyList(), message.children)
        // Multipart disposition.
        val multi = fetch("""(("text" "plain" NIL NIL NIL "7bit" 1 1) "mixed" NIL ("inline" ("x" "1")) NIL NIL)""")
        assertEquals("inline", multi.disposition)
        assertEquals(mapOf("x" to "1"), multi.dispositionParams)
    }

    @Test
    fun raw8BitNames() {
        fun named(bytes: ByteArray) = parseBodyStructure(
            ImapValue.ListV(
                listOf(
                    ImapValue.Str("application".toByteArray()), ImapValue.Str("pdf".toByteArray()),
                    ImapValue.ListV(listOf(ImapValue.Str("name".toByteArray()), ImapValue.Str(bytes))),
                    ImapValue.Nil, ImapValue.Nil, ImapValue.Str("base64".toByteArray()), ImapValue.Atom("10"),
                ),
            ),
        ).fileName
        assertEquals("café.pdf", named("café.pdf".toByteArray(Charsets.UTF_8)))
        assertEquals("café.pdf", named("café.pdf".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun deepNestingIsCut() {
        fun str(s: String) = ImapValue.Str(s.toByteArray())
        var value: ImapValue = ImapValue.ListV(listOf(str("text"), str("plain")))
        repeat(5_000) { value = ImapValue.ListV(listOf(value, str("mixed"))) }
        var part = parseBodyStructure(value)
        var depth = 0
        while (part.children.isNotEmpty()) {
            part = part.children.single()
            depth++
        }
        assertEquals(100, depth)
        assertEquals("1" + ".1".repeat(99), part.section)
        assertTrue(part.isMultipart)
        assertEquals(Shown(), shown(parseBodyStructure(value)))
    }

    @Test
    fun randomStructuresNeverThrow() {
        val rnd = Random(7)
        val words = listOf(
            "text", "TEXT", "plain", "html", "image", "png", "multipart", "mixed", "related", "alternative",
            "message", "rfc822", "attachment", "inline", "name", "filename", "name*0*", "name*1", "start", "<a@b>",
            "=?UTF-8?B?w6k=?=", "=?bogus?Q?a=ZZ?=", "utf-8''%C3", "x'''", "",
        )

        fun random(depth: Int): ImapValue = when (rnd.nextInt(if (depth > 5) 4 else 6)) {
            0 -> ImapValue.Nil
            1 -> ImapValue.Atom(rnd.nextInt(-5, 500).toString())
            2, 3 -> ImapValue.Str(words.random(rnd).toByteArray())
            else -> ImapValue.ListV(List(rnd.nextInt(0, 12)) { random(depth + 1) })
        }
        repeat(5_000) {
            val part = parseBodyStructure(random(0))
            val d = part.displayParts()
            assertTrue(part.type == part.type.lowercase() && part.subtype == part.subtype.lowercase())
            assertTrue(part.section == "" || part.section == "1")
            (d.html + d.plain + d.inline + d.attachments).forEach { assertTrue(!it.isMultipart) }
        }
    }

    // displayParts rules

    @Test
    fun relatedRootFromStartParameter() {
        val p = fetch(
            """
            (("image" "png" NIL "<logo@x>" NIL "base64" 10 NIL NIL NIL NIL)
            ("text" "html" ("charset" "utf-8") "<root@x>" NIL "7bit" 50 2 NIL NIL NIL NIL)
            "related" ("type" "text/html" "start" "<root@x>" "boundary" "b") NIL NIL NIL)
            """,
        )
        assertEquals(Shown(html = listOf("2"), inline = listOf("1")), shown(p))
    }

    @Test
    fun relatedWithoutHtmlRootIsMixed() {
        val p = fetch(
            """
            (("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 50 2 NIL NIL NIL NIL)
            ("image" "png" NIL "<a@x>" NIL "base64" 10 NIL ("inline" NIL) NIL NIL)
            ("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 20 1 NIL NIL NIL NIL)
            "related" ("boundary" "b") NIL NIL NIL)
            """,
        )
        // No HTML can show the cid image, so it is an attachment.
        assertEquals(Shown(plain = listOf("1", "3"), attachments = listOf("2")), shown(p))
    }

    @Test
    fun relatedImageWithAttachmentDispositionStaysInline() {
        val p = fetch(
            """
            (("text" "html" NIL NIL NIL "7bit" 50 2 NIL NIL NIL NIL)
            ("image" "gif" ("name" "a.gif") "<a@x>" NIL "base64" 10 NIL ("attachment" ("filename" "a.gif")) NIL NIL)
            ("image" "gif" ("name" "b.gif") NIL NIL "base64" 10 NIL NIL NIL NIL)
            ("application" "octet-stream" NIL "<c@x>" NIL "base64" 10 NIL NIL NIL NIL)
            "related" NIL NIL NIL NIL)
            """,
        )
        assertEquals(Shown(html = listOf("1"), inline = listOf("2"), attachments = listOf("3", "4")), shown(p))
    }

    @Test
    fun cidImageInMixed() {
        val withHtml = fetch(
            """
            (("text" "html" NIL NIL NIL "7bit" 50 2 NIL NIL NIL NIL)
            ("image" "png" NIL "<a@x>" NIL "base64" 10 NIL ("inline" NIL) NIL NIL)
            ("image" "png" NIL "<b@x>" NIL "base64" 10 NIL ("attachment" NIL) NIL NIL)
            "mixed" NIL NIL NIL NIL)
            """,
        )
        assertEquals(Shown(html = listOf("1"), inline = listOf("2"), attachments = listOf("3")), shown(withHtml))

        val plainOnly = fetch(
            """
            (("text" "plain" NIL NIL NIL "7bit" 50 2 NIL NIL NIL NIL)
            ("image" "png" NIL "<a@x>" NIL "base64" 10 NIL ("inline" NIL) NIL NIL)
            ("application" "pdf" NIL NIL NIL "base64" 10 NIL NIL NIL NIL)
            "mixed" NIL NIL NIL NIL)
            """,
        )
        assertEquals(Shown(plain = listOf("1"), attachments = listOf("2", "3")), shown(plainOnly))
    }

    @Test
    fun unreferencedCidImageBecomesAnAttachment() {
        // A client that gives every part a Content-ID; the HTML names none of them.
        val p = fetch(
            """
            ((("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 50 2 NIL NIL NIL NIL)
            ("text" "html" ("charset" "utf-8") NIL NIL "7bit" 90 3 NIL NIL NIL NIL)
            "alternative" ("boundary" "a") NIL NIL NIL)
            ("image" "jpeg" ("name" "photo.jpg") "<x@y>" NIL "base64" 10 NIL ("inline" ("filename" "photo.jpg")) NIL NIL)
            ("application" "pdf" ("name" "a.pdf") NIL NIL "base64" 10 NIL ("attachment" ("filename" "a.pdf")) NIL NIL)
            "mixed" ("boundary" "m") NIL NIL NIL)
            """,
        )
        assertEquals(Shown(html = listOf("1.2"), inline = listOf("2"), attachments = listOf("3")), shown(p))
        val unreferenced = p.displayParts(referencedCids = emptySet())
        assertEquals(emptyList(), unreferenced.inline)
        assertEquals(listOf("2", "3"), unreferenced.attachments.map { it.section })
        val referenced = p.displayParts(referencedCids = setOf("x@y"))
        assertEquals(listOf("2"), referenced.inline.map { it.section })
        assertEquals(listOf("3"), referenced.attachments.map { it.section })
    }

    @Test
    fun alternativePicksRichestChild() {
        // Wrong order from a broken mailer: HTML still wins.
        val reversed = fetch(
            """
            (("text" "html" NIL NIL NIL "7bit" 5 1)("text" "plain" NIL NIL NIL "7bit" 5 1) "alternative")
            """,
        )
        assertEquals(Shown(html = listOf("1")), shown(reversed))

        // Unsupported richer alternative: plain text is shown, the other is kept.
        val enriched = fetch(
            """
            (("text" "plain" NIL NIL NIL "7bit" 5 1)("text" "enriched" NIL NIL NIL "7bit" 5 1) "alternative")
            """,
        )
        assertEquals(Shown(plain = listOf("1"), attachments = listOf("2")), shown(enriched))

        // Two HTML versions: the last one.
        val twoHtml = fetch(
            """
            (("text" "html" NIL NIL NIL "7bit" 5 1)
            (("text" "html" NIL NIL NIL "7bit" 5 1)("image" "png" NIL "<i@x>" NIL "base64" 5) "related") "alternative")
            """,
        )
        assertEquals(Shown(html = listOf("2.1"), inline = listOf("2.2")), shown(twoHtml))

        // Nothing displayable: everything is an attachment.
        val none = fetch(
            """
            (("text" "calendar" NIL NIL NIL "7bit" 5 1)("application" "ics" NIL NIL NIL "base64" 5) "alternative")
            """,
        )
        assertEquals(Shown(attachments = listOf("1", "2")), shown(none))
    }

    @Test
    fun textWithFilenameOrAttachmentDispositionIsAttachment() {
        val p = fetch(
            """
            (("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 5 1 NIL NIL NIL NIL)
            ("text" "plain" ("charset" "utf-8" "name" "notes.txt") NIL NIL "7bit" 5 1 NIL NIL NIL NIL)
            ("text" "html" NIL NIL NIL "7bit" 5 1 NIL ("attachment" NIL) NIL NIL)
            ("text" "plain" NIL NIL NIL "7bit" 5 1 NIL ("inline" ("filename" "log.txt")) NIL NIL)
            ("text" "x-diff" NIL NIL NIL "7bit" 5 1 NIL ("inline" NIL) NIL NIL)
            ("text" "plain" NIL NIL NIL "7bit" 5 1 NIL ("inline" ("filename" " ")) NIL NIL)
            "mixed" NIL NIL NIL NIL)
            """,
        )
        assertEquals(Shown(plain = listOf("1", "6"), attachments = listOf("2", "3", "4", "5")), shown(p))
    }

    @Test
    fun topLevelNonTextIsAttachment() {
        val p = fetch(
            """
            ("APPLICATION" "PDF" ("NAME" "scan.pdf") NIL NIL "BASE64" 1000
            NIL ("ATTACHMENT" ("FILENAME" "scan.pdf")) NIL)
            """,
        )
        assertEquals("1", p.section)
        assertEquals(Shown(attachments = listOf("1")), shown(p))
        assertEquals(Shown(attachments = listOf("1")), shown(fetch("""("image" "png" NIL "<a@b>" NIL "base64" 1)""")))
    }

    @Test
    fun unknownMultipartIsMixed() {
        val p = fetch(
            """
            (("text" "plain" NIL NIL NIL "7bit" 5 1)("image" "png" NIL NIL NIL "base64" 5)
            (("application" "applefile" NIL NIL NIL "base64" 5)("application" "pdf" NIL NIL NIL "base64" 5)
            "appledouble")
            "parallel")
            """,
        )
        assertEquals(Shown(plain = listOf("1"), attachments = listOf("2", "3.1", "3.2")), shown(p))
        val encrypted = fetch(
            """
            (("application" "pgp-encrypted" NIL NIL NIL "7bit" 12)("application" "octet-stream" ("name" "encrypted.asc")
            NIL NIL "7bit" 900) "encrypted" ("protocol" "application/pgp-encrypted") NIL NIL NIL)
            """,
        )
        assertEquals(Shown(attachments = listOf("1", "2")), shown(encrypted))
    }

    @Test
    fun reportRestIsFlattenedIntoAttachments() {
        val p = part(
            "", "multipart/report",
            part("1", "text/plain"),
            part("2", "multipart/mixed", part("2.1", "text/plain"), part("2.2", "image/png", cid = "x")),
            part("3", "text/html"),
        )
        assertEquals(Shown(plain = listOf("1"), attachments = listOf("2.1", "2.2", "3")), shown(p))
    }

    @Test
    fun emptyMultipartsShowNothing() {
        for (subtype in listOf("mixed", "alternative", "related", "signed", "report")) {
            assertEquals(Shown(), shown(part("", "multipart/$subtype")))
        }
        val single = fetch("""("multipart" "mixed" NIL NIL NIL "7bit" 0)""")
        assertTrue(single.isMultipart)
        assertEquals(Shown(), shown(single))
    }

    @Test
    fun textPartsInDocumentOrder() {
        val p = part(
            "", "multipart/mixed",
            part("1", "text/plain"), part("2", "text/html"), part("3", "text/plain"),
            part("4", "multipart/mixed", *Array(11) { part("4.${it + 1}", "text/html") }),
        )
        val d = p.displayParts()
        assertEquals(listOf("2") + (1..11).map { "4.$it" }, d.html.map { it.section })
        assertEquals(listOf("1", "3"), d.plain.map { it.section })
        assertEquals(listOf("1", "2", "3") + (1..11).map { "4.$it" }, d.textParts.map { it.section })
    }

    @Test(timeout = 5_000)
    fun largeFlatMessageStaysFast() {
        val children = List(30_000) { i ->
            if (i % 2 == 0) part("${i + 1}", "text/plain") else part("${i + 1}", "application/pdf", name = "f$i.pdf")
        }
        val d = part("", "multipart/alternative", part("1", "multipart/mixed", *children.toTypedArray())).displayParts()
        assertEquals(15_000, d.plain.size)
        assertEquals(15_000, d.attachments.size)
    }
}
