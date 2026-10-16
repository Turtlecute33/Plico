package app.plico.mail.mime

import org.junit.Test
import kotlin.test.assertTrue
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeaderBlockTest {
    private fun parse(s: String) = HeaderBlock.parse(s.toByteArray(Charsets.UTF_8))

    @Test
    fun gmailHeaderFieldsFetch() {
        // BODY.PEEK[HEADER.FIELDS (...)] literal as Gmail returns it, with the trailing empty line.
        val h = parse(
            "From: =?UTF-8?Q?Jan_Nov=C3=A1k?= <jan.novak@gmail.com>\r\n" +
                "To: Petr <petr@riseup.net>\r\n" +
                "Subject: =?UTF-8?B?UmU6IMSMYXUsIGphayBzZSBtw6HFoT8=?=\r\n" +
                "Date: Fri, 16 Oct 2026 11:14:03 +0200\r\n" +
                "Message-ID: <CAFx3=abc+def@mail.gmail.com>\r\n" +
                "In-Reply-To: <CAFx3=prev@mail.gmail.com>\r\n" +
                "References: <CAFx3=first@mail.gmail.com>\r\n" +
                " <CAFx3=prev@mail.gmail.com>\r\n" +
                "\r\n",
        )
        assertEquals("=?UTF-8?Q?Jan_Nov=C3=A1k?= <jan.novak@gmail.com>", h["from"])
        assertEquals("=?UTF-8?B?UmU6IMSMYXUsIGphayBzZSBtw6HFoT8=?=", h["SUBJECT"])
        assertEquals("<CAFx3=first@mail.gmail.com> <CAFx3=prev@mail.gmail.com>", h["References"])
        assertEquals("<CAFx3=abc+def@mail.gmail.com>", h["message-id"])
        assertEquals(7, h.fields.size)
    }

    @Test
    fun dovecotFullHeaderWithRepeatedFields() {
        val h = parse(
            "Return-Path: <sender@example.org>\r\n" +
                "Delivered-To: user@riseup.net\r\n" +
                "Received: from mx0.riseup.net (mx0-pn.riseup.net [10.0.1.42])\r\n" +
                "\tby fews01-sea.riseup.net (Postfix) with ESMTPS id 4cXyZ1abcdz9tQ\r\n" +
                "\tfor <user@riseup.net>; Fri, 16 Oct 2026 09:14:05 +0000 (UTC)\r\n" +
                "Received: from [127.0.0.1] (localhost [127.0.0.1])\r\n" +
                "\tby fews03-sea.riseup.net (Postfix) with ESMTPSA id 4cXyZ0\r\n" +
                "X-Riseup-User-ID: 0123456789abcdef\r\n" +
                "Subject: Hello\r\n" +
                "\r\n" +
                "Body: not a header\r\n",
        )
        assertEquals(2, h.all("received").size)
        assertEquals(
            "from mx0.riseup.net (mx0-pn.riseup.net [10.0.1.42])\tby fews01-sea.riseup.net (Postfix) with ESMTPS id " +
                "4cXyZ1abcdz9tQ\tfor <user@riseup.net>; Fri, 16 Oct 2026 09:14:05 +0000 (UTC)",
            h["Received"],
        )
        assertEquals("Hello", h["subject"])
        assertNull(h["body"])
        assertEquals(emptyList(), h.all("cc"))
    }

    @Test
    fun lfLineEnds() {
        val h = parse("Subject: one\n two\nFrom: a@b.example\n\nX-Body: no\n")
        assertEquals("one two", h["subject"])
        assertEquals("a@b.example", h["from"])
        assertNull(h["x-body"])
    }

    @Test
    fun noTrailingEmptyLineAndNoFinalLineEnd() {
        assertEquals("x", parse("Subject: x")["subject"])
        assertEquals(listOf("A" to "1", "B" to "2"), parse("A: 1\r\nB: 2\r\n").fields)
        assertEquals(emptyList(), parse("").fields)
        assertEquals(emptyList(), parse("\r\nSubject: body").fields)
    }

    @Test
    fun unfoldingKeepsWhitespace() {
        assertEquals("a  b\tc", parse("Subject: a\r\n  b\r\n\tc\r\n\r\n")["subject"])
        assertEquals("", parse("Subject:\r\n\r\n")["subject"])
        assertEquals("folded", parse("Subject:\r\n folded\r\n\r\n")["subject"])
    }

    @Test
    fun obsoleteSpaceBeforeColonAndWhitespaceOnlyLine() {
        val h = parse("From  : John Doe <jdoe@machine.example>\r\nTo    : Mary Smith\r\n  \r\n          <mary@example.net>\r\n\r\n")
        assertEquals("John Doe <jdoe@machine.example>", h["from"])
        assertEquals("Mary Smith            <mary@example.net>", h["to"])
    }

    @Test
    fun malformedLinesAreSkipped() {
        val h = parse(
            " leading continuation\r\n" +
                "From sender@example.org Fri Oct 16 09:14:03 2026\r\n" +
                "no colon here\r\n" +
                "\tcontinues the bad line\r\n" +
                ": empty name\r\n" +
                "Bad Name: x\r\n" +
                "Subject: ok\r\n\r\n",
        )
        assertEquals(listOf("Subject" to "ok"), h.fields)
    }

    @Test
    fun valuesKeepColonsAndAreTrimmed() {
        assertEquals("Re: Fwd: x", parse("Subject:   Re: Fwd: x   \r\n\r\n")["subject"])
    }

    @Test
    fun raw8BitUtf8() {
        assertEquals("Grüße aus München", parse("Subject: Grüße aus München\r\n\r\n")["subject"])
    }

    @Test
    fun raw8BitLatin1PerLine() {
        val bytes = "Subject: Gr".toByteArray() + byteArrayOf(0xFC.toByte(), 0xDF.toByte()) + "e\r\n".toByteArray() +
            "From: Jörg <j@example.de>\r\n\r\n".toByteArray(Charsets.UTF_8)
        val h = HeaderBlock.parse(bytes)
        assertEquals("Grüße", h["subject"])
        assertEquals("Jörg <j@example.de>", h["from"])
    }

    @Test
    fun stopsAtEmptyLineWithLoneCrLf() {
        val h = parse("A: 1\r\n\r\nB: 2\r\n")
        assertEquals(listOf("A" to "1"), h.fields)
    }

    @Test
    fun randomBytesNeverThrow() {
        val rnd = Random(5)
        val alphabet = "A: \t\r\nb-".toByteArray() + byteArrayOf(0xC3.toByte(), 0xA9.toByte(), 0xFF.toByte(), 0)
        repeat(3000) {
            val bytes = ByteArray(rnd.nextInt(0, 80)) { alphabet[rnd.nextInt(alphabet.size)] }
            val h = HeaderBlock.parse(bytes)
            assertTrue(h.fields.all { (name, _) -> name.isNotEmpty() && name.none { it <= ' ' || it == ':' } })
        }
    }

    @Test(timeout = 5_000)
    fun hostileInputStaysFast() {
        val folded = ("X: a\r\n" + " b\r\n".repeat(200_000)).toByteArray()
        assertEquals(1, HeaderBlock.parse(folded).fields.size)
        val many = "A: 1\n".repeat(200_000).toByteArray()
        assertEquals(200_000, HeaderBlock.parse(many).fields.size)
    }
}
