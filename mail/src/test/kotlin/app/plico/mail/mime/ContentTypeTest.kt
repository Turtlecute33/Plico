package app.plico.mail.mime

import org.junit.Test
import kotlin.test.assertTrue
import kotlin.random.Random
import kotlin.test.assertEquals

class ContentTypeTest {
    private fun ct(type: String, subtype: String, vararg params: Pair<String, String>) =
        ContentTypeValue(type, subtype, mapOf(*params))

    @Test
    fun simpleTypes() {
        assertEquals(ct("text", "plain", "charset" to "utf-8"), ContentType.parse("text/plain; charset=\"utf-8\""))
        assertEquals(ct("text", "html", "charset" to "UTF-8"), ContentType.parse("TEXT/HTML; CHARSET=UTF-8"))
        assertEquals(ct("text", "plain", "charset" to "us-ascii"), ContentType.parse("text/plain;charset=us-ascii"))
        assertEquals("multipart/alternative", ContentType.parse("Multipart/Alternative; boundary=x").mimeType)
    }

    @Test
    fun gmailMultipart() {
        assertEquals(
            ct("multipart", "alternative", "boundary" to "000000000000a1b2c3d4e5f6"),
            ContentType.parse("multipart/alternative; boundary=\"000000000000a1b2c3d4e5f6\""),
        )
    }

    @Test
    fun quotedValuesWithSpecialsAndEscapes() {
        assertEquals(
            ct("application", "pdf", "name" to "a; b=c.pdf"),
            ContentType.parse("application/pdf; name=\"a; b=c.pdf\""),
        )
        assertEquals(
            ct("application", "pdf", "name" to "say \"hi\" \\ bye.pdf"),
            ContentType.parse("application/pdf; name=\"say \\\"hi\\\" \\\\ bye.pdf\""),
        )
        assertEquals(ct("text", "plain", "a" to "d e"), ContentType.parse("text/plain; a=\"d e\" junk"))
        assertEquals(ct("text", "plain", "a" to "open"), ContentType.parse("text/plain; a=\"open"))
    }

    @Test
    fun unquotedValuesFromBrokenMailers() {
        assertEquals(
            ct("application", "octet-stream", "name" to "photo (1).jpg"),
            ContentType.parse("application/octet-stream; name=photo (1).jpg"),
        )
    }

    @Test
    fun brokenOrMissingType() {
        assertEquals(ct("text", "plain"), ContentType.parse(""))
        assertEquals(ct("text", "plain"), ContentType.parse("   "))
        assertEquals(ct("text", "plain", "charset" to "utf-8"), ContentType.parse("; charset=utf-8"))
        assertEquals(ct("text", "plain"), ContentType.parse("text/"))
        assertEquals(ct("text", "plain"), ContentType.parse("/plain"))
        assertEquals(ct("text", "plain"), ContentType.parse("\"text/html\""))
        assertEquals(ct("text", "plain"), ContentType.parse("charset=utf-8"))
        assertEquals(ct("text", "html"), ContentType.parse("text / html"))
        assertEquals(ct("text", "html"), ContentType.parse(" text/html  "))
    }

    @Test
    fun parametersWithoutValueAreSkipped() {
        assertEquals(ct("text", "plain", "charset" to "utf-8"), ContentType.parse("text/plain; format; ; =x; charset=utf-8"))
    }

    @Test
    fun firstOccurrenceWins() {
        assertEquals(ct("text", "plain", "charset" to "utf-8"), ContentType.parse("text/plain; charset=utf-8; CHARSET=latin1"))
    }

    @Test
    fun contentDisposition() {
        assertEquals(ct("attachment", "", "filename" to "x"), ContentType.parse("attachment; filename=x"))
        assertEquals(ct("inline", ""), ContentType.parse("INLINE"))
        val outlook = ContentType.parse(
            "attachment; filename=\"=?iso-8859-1?Q?Rechnung_M=E4rz.pdf?=\"; size=12345;\r\n" +
                "\tcreation-date=\"Fri, 16 Oct 2026 09:14:03 GMT\";\r\n\tmodification-date=\"Fri, 16 Oct 2026 09:14:03 GMT\"",
        )
        assertEquals("attachment", outlook.type)
        assertEquals("", outlook.subtype)
        assertEquals("Rechnung März.pdf", outlook.params["filename"])
        assertEquals("12345", outlook.params["size"])
        assertEquals("Fri, 16 Oct 2026 09:14:03 GMT", outlook.params["creation-date"])
    }

    @Test
    fun rfc2047InParameterValues() {
        assertEquals(
            "Rechnung März.pdf",
            ContentType.parse("application/pdf; name=\"=?UTF-8?B?UmVjaG51bmcgTcOkcnoucGRm?=\"").params["name"],
        )
        assertEquals(
            "Žluťoučký kůň úpěl ďábelské ódy.pdf",
            ContentType.parse(
                "application/pdf;\r\n name=\"=?UTF-8?B?xb1sdcWlb3XEjWvDvSBrxa/FiCDDunDEm2wgxI/DoWJlbHNrw6kgw7NkeS5wZGY=?=\"",
            ).params["name"],
        )
    }

    @Test
    fun rfc2231Examples() {
        // RFC 2231 section 3: continuations.
        assertEquals(
            "ftp://cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar",
            ContentType.parse(
                "message/external-body; access-type=URL;\r\n URL*0=\"ftp://\";\r\n" +
                    " URL*1=\"cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar\"",
            ).params["url"],
        )
        // Section 4: charset and language.
        assertEquals(
            "This is ***fun***",
            ContentType.parse("application/x-stuff;\r\n title*=us-ascii'en-us'This%20is%20%2A%2A%2Afun%2A%2A%2A").params["title"],
        )
        // Section 4.1: mixed encoded and unencoded continuations.
        assertEquals(
            "This is even more ***fun*** isn't it!",
            ContentType.parse(
                "application/x-stuff;\r\n title*0*=us-ascii'en'This%20is%20even%20more%20;\r\n" +
                    " title*1*=%2A%2A%2Afun%2A%2A%2A%20;\r\n title*2=\"isn't it!\"",
            ).params["title"],
        )
    }

    @Test
    fun rfc2231RealMailers() {
        // Apple Mail
        assertEquals(
            "Práce.pdf",
            ContentType.parse("inline; filename*=utf-8''Pr%C3%A1ce.pdf").params["filename"],
        )
        // Thunderbird long name in two sections
        assertEquals(
            "Žluťoučký kůň úpěl ďábelské ódy.pdf",
            ContentType.parse(
                "attachment;\r\n filename*0*=UTF-8''%C5%BDlu%C5%A5ou%C4%8Dk%C3%BD%20k%C5%AF%C5%88;\r\n" +
                    " filename*1*=%20%C3%BAp%C4%9Bl%20%C4%8F%C3%A1belsk%C3%A9%20%C3%B3dy.pdf",
            ).params["filename"],
        )
        // Apple Mail decomposed (NFD) letters split across sections
        assertEquals(
            "Velmi dlouhý název souboru.pdf",
            ContentType.parse(
                "attachment; filename*0*=utf-8''Velmi%20dlouhy%CC%81%20na%CC%81zev%20sou; filename*1*=boru.pdf",
            ).params["filename"],
        )
    }

    @Test
    fun rfc2231EdgeCases() {
        // A UTF-8 sequence split between sections, sections out of order.
        assertEquals(
            "Žlu",
            ContentType.decodeParams(listOf("name*1*" to "%BDlu", "name*0*" to "utf-8''%C5")).getValue("name"),
        )
        // Lower-case hex, quoted extended value, ISO-8859-1 charset.
        assertEquals("März", ContentType.parse("x/y; n*=\"iso-8859-1'de'M%e4rz\"").params["n"])
        // Missing charset part: percent-encoded UTF-8.
        assertEquals("€ rates.pdf", ContentType.decodeParams(listOf("filename*" to "%E2%82%AC%20rates.pdf"))["filename"])
        // Empty charset and language.
        assertEquals("a b", ContentType.decodeParams(listOf("x*" to "''a%20b"))["x"])
        // Invalid percent escape kept literally.
        assertEquals("100%-%zz", ContentType.decodeParams(listOf("x*" to "utf-8''100%-%zz"))["x"])
        // Invalid UTF-8 falls back to windows-1252.
        assertEquals("März", ContentType.decodeParams(listOf("x*" to "utf-8''M%E4rz"))["x"])
        // Apostrophes after the charset prefix are data.
        assertEquals("it's", ContentType.decodeParams(listOf("x*" to "utf-8''it's"))["x"])
    }

    @Test
    fun rfc2231WinsOverPlainForm() {
        val params = ContentType.decodeParams(
            listOf("filename" to "=?UTF-8?B?UmVjaG51bmcgTcOkcnoucGRm?=", "filename*" to "utf-8''Rechnung%20M%C3%A4rz.pdf"),
        )
        assertEquals(mapOf("filename" to "Rechnung März.pdf"), params)
        assertEquals(
            mapOf("filename" to "new.pdf"),
            ContentType.decodeParams(listOf("filename" to "old.pdf", "filename*0" to "new", "filename*1" to ".pdf")),
        )
    }

    @Test
    fun rfc2047InsideContinuations() {
        // Long encoded word split by RFC 2231 continuations (seen from some webmailers).
        assertEquals(
            "Rechnung März.pdf",
            ContentType.decodeParams(
                listOf("name*0" to "=?UTF-8?B?UmVjaG51bm", "name*1" to "cgTcOkcnoucGRm?="),
            )["name"],
        )
    }

    @Test
    fun bodyStructureStyleParams() {
        // Dovecot and Gmail send parameter names upper-case in BODYSTRUCTURE.
        val params = ContentType.decodeParams(
            listOf("CHARSET" to "utf-8", "NAME*0*" to "UTF-8''%C5%BDlu", "NAME*1*" to "%C5%A5ou%C4%8Dk%C3%BD.txt", "FORMAT" to "flowed"),
        )
        assertEquals(mapOf("charset" to "utf-8", "name" to "Žluťoučký.txt", "format" to "flowed"), params)
        assertEquals(listOf("charset", "name", "format"), params.keys.toList())
    }

    @Test
    fun emptyAndOddNames() {
        assertEquals(emptyMap(), ContentType.decodeParams(emptyList()))
        assertEquals(emptyMap(), ContentType.decodeParams(listOf("" to "x", "  " to "y")))
        assertEquals(mapOf("a" to "1"), ContentType.decodeParams(listOf(" A " to "1")))
    }

    @Test
    fun randomInputNeverThrows() {
        val rnd = Random(2)
        val alphabet = "\"a@b.c<>(),;:\\ \t\r\n=?_qQbB%*'é日\u0000/x*0123'"
        repeat(3000) {
            val s = String(CharArray(rnd.nextInt(0, 60)) { alphabet[rnd.nextInt(alphabet.length)] })
            val v = ContentType.parse(s)
            assertTrue(v.type.isNotEmpty())
            assertTrue(v.params.keys.all { it == it.lowercase() })
        }
    }

    @Test(timeout = 5_000)
    fun hostileInputStaysFast() {
        assertEquals(ct("text", "html"), ContentType.parse("text/" + " ".repeat(500_000) + "html"))
        assertEquals("text", ContentType.parse("text/plain;" + " a=\"\\\"".repeat(100_000)).type)
        assertEquals(1, ContentType.parse("a/b;" + " n*1*=%41;".repeat(50_000)).params.size)
        assertEquals("x".repeat(200_000), ContentType.decodeParams(listOf("n" + "*".repeat(200_000) to "x".repeat(200_000))).values.single())
    }
}
