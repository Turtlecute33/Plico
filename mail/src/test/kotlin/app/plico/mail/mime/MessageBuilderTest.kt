package app.plico.mail.mime

import org.junit.Test
import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageBuilderTest {
    private val me = Address("Petr Svoboda", "petr@riseup.net")
    private val jan = Address("Jan Novák", "jan.novak@gmail.com")
    private val id = "<6F9619FF-8B86-4D01-B42D-00C04FC964FF@riseup.net>"

    /** 2001-09-09T01:46:40Z, a Sunday. */
    private val date = 1_000_000_000_000L

    private fun builder(
        from: Address = me,
        to: List<Address> = listOf(jan),
        cc: List<Address> = emptyList(),
        bcc: List<Address> = emptyList(),
        subject: String = "Hello",
        text: String = "Hi Jan,\nsee you.\n",
        messageId: String = id,
        inReplyTo: String? = null,
        references: List<String> = emptyList(),
        attachments: List<OutgoingAttachment> = emptyList(),
    ) = MessageBuilder(from, to, cc, bcc, subject, text, messageId, inReplyTo, references, attachments, date)

    private fun MessageBuilder.message(includeBcc: Boolean = false): String {
        val bytes = build(includeBcc)
        assertWellFormed(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun file(name: String = "f", type: String = "text/plain", bytes: ByteArray = ByteArray(1)) =
        OutgoingAttachment(name, type, bytes)

    /** The single attachment part of a message with [attachment]. */
    private fun partOf(attachment: OutgoingAttachment): String =
        parts(builder(attachments = listOf(attachment)).message())[1]

    private fun headers(message: String) = HeaderBlock.parse(message.toByteArray(Charsets.UTF_8))
    private fun headerNames(message: String) = headers(message).fields.map { it.first }
    private fun headerLines(message: String) = message.substringBefore("\r\n\r\n").split("\r\n")
    private fun body(message: String) = message.substringAfter("\r\n\r\n")
    private fun addresses(message: String, field: String) = Addresses.parseList(headers(message)[field]!!)
    private fun param(part: String, field: String, key: String) = ContentType.parse(headers(part)[field]!!).params[key]
    private fun decode(body: String, encoding: String) = String(TransferDecoding.decode(body.toByteArray(), encoding))

    /** Raw lines of the field [name] (the first line and its continuation lines). */
    private fun fieldLines(message: String, name: String): List<String> {
        val lines = headerLines(message)
        val start = lines.indexOfFirst { it.startsWith("$name:", ignoreCase = true) }
        assertTrue(start >= 0, "no $name")
        return listOf(lines[start]) + lines.drop(start + 1).takeWhile { it.startsWith(" ") }
    }

    /** The parts of a multipart message, each as `headers CRLF CRLF content` without the delimiter's CRLF. */
    private fun parts(message: String): List<String> {
        val boundary = ContentType.parse(headers(message)["Content-Type"]!!).params.getValue("boundary")
        val body = body(message)
        assertTrue(body.startsWith("--$boundary\r\n"))
        assertTrue(body.endsWith("\r\n--$boundary--\r\n"))
        return body.removePrefix("--$boundary\r\n").removeSuffix("\r\n--$boundary--\r\n").split("\r\n--$boundary\r\n")
    }

    private fun decodedPart(part: String): ByteArray {
        val encoding = headers(part)["Content-Transfer-Encoding"]!!
        return TransferDecoding.decode((body(part) + "\r\n").toByteArray(Charsets.US_ASCII), encoding)
    }

    /** CRLF only, ends with CRLF, no line over 998 octets. */
    private fun assertWellFormed(bytes: ByteArray) {
        assertTrue(bytes.size >= 2 && bytes[bytes.size - 2] == CR && bytes[bytes.size - 1] == LF, "ends with CRLF")
        var lineStart = 0
        for (i in bytes.indices) {
            if (bytes[i] == CR) assertEquals(LF, bytes.getOrNull(i + 1), "bare CR at $i")
            if (bytes[i] == LF) {
                assertTrue(i > 0 && bytes[i - 1] == CR, "bare LF at $i")
                assertTrue(i - 1 - lineStart <= 998, "line of ${i - 1 - lineStart} octets")
                lineStart = i + 1
            }
        }
    }

    private fun assertHeaderLinesWithin78(message: String) {
        for (line in headerLines(message)) assertTrue(line.length <= 78, "${line.length}: $line")
    }

    /**
     * Like [assertHeaderLinesWithin78], but the old-client `name="=?UTF-8?B?…?=` line may have 79: a full encoded
     * word (72) after ` name="` (7) cannot be shorter.
     */
    private fun assertPartHeaderLinesFolded(part: String) {
        for (line in headerLines(part)) {
            val limit = if (line.startsWith(" name=\"=?UTF-8?B?")) 79 else 78
            assertTrue(line.length <= limit, "${line.length}: $line")
        }
    }

    @Test
    fun simpleMessageExactBytes() {
        val message = builder(
            cc = listOf(Address(null, "eva@example.org")),
            bcc = listOf(Address("Hidden", "hidden@example.net")),
            inReplyTo = "<CAFx3=prev@mail.gmail.com>",
            references = listOf("<CAFx3=first@mail.gmail.com>", "<CAFx3=prev@mail.gmail.com>"),
        ).message()
        assertEquals(
            "Date: Sun, 9 Sep 2001 01:46:40 +0000\r\n" +
                "From: Petr Svoboda <petr@riseup.net>\r\n" +
                "To: =?UTF-8?B?SmFuIE5vdsOhaw==?= <jan.novak@gmail.com>\r\n" +
                "Cc: eva@example.org\r\n" +
                "Subject: Hello\r\n" +
                "Message-ID: <6F9619FF-8B86-4D01-B42D-00C04FC964FF@riseup.net>\r\n" +
                "In-Reply-To: <CAFx3=prev@mail.gmail.com>\r\n" +
                "References: <CAFx3=first@mail.gmail.com> <CAFx3=prev@mail.gmail.com>\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: 7bit\r\n" +
                "\r\n" +
                "Hi Jan,\r\n" +
                "see you.\r\n",
            message,
        )
    }

    @Test
    fun headerOrder() {
        val b = builder(
            cc = listOf(Address(null, "eva@example.org")),
            bcc = listOf(Address(null, "hidden@example.net")),
            inReplyTo = "<p@x>",
            references = listOf("<p@x>"),
        )
        assertEquals(ALL.filter { it != "Bcc" }, headerNames(b.message()))
        assertEquals(ALL, headerNames(b.message(includeBcc = true)))
    }

    @Test
    fun bccOnlyWhenIncluded() {
        val b = builder(bcc = listOf(Address("Boss", "boss@example.net"), Address(null, "audit@example.net")))
        val wire = b.message()
        assertFalse("boss@example.net" in wire)
        assertFalse("audit@example.net" in wire)
        assertNull(headers(wire)["Bcc"])

        val stored = b.message(includeBcc = true)
        assertEquals(b.bcc, addresses(stored, "Bcc"))
        // The stored copy is the wire message plus the Bcc line.
        assertEquals(wire, stored.replace("Bcc: Boss <boss@example.net>, audit@example.net\r\n", ""))
    }

    @Test
    fun emptyOptionalFieldsAreOmitted() {
        val message = builder(to = emptyList(), inReplyTo = "no id here", references = listOf("", "junk")).message(true)
        assertEquals(PLAIN - "To", headerNames(message))
    }

    @Test
    fun noBannedOrExtraHeaders() {
        val banned = listOf(
            "User-Agent", "X-Mailer", "Disposition-Notification-To", "Content-Language", "Return-Receipt-To",
        )
        for (message in listOf(builder().message(true), builder(attachments = listOf(file())).message(true))) {
            assertTrue(ALL.containsAll(headerNames(message)), headerNames(message).toString())
            for (line in message.split("\r\n")) {
                for (name in banned) assertFalse(line.startsWith("$name:", ignoreCase = true), line)
            }
        }
    }

    @Test
    fun dateIsUtcWhateverTheDeviceZone() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            val value = headers(builder().message())["Date"]!!
            assertEquals("Sun, 9 Sep 2001 01:46:40 +0000", value)
            assertEquals(date, Dates.parse(value))
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun newMessageIdForm() {
        val uuid = "[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}"
        val first = MessageBuilder.newMessageId("riseup.net")
        assertTrue(Regex("<$uuid@riseup\\.net>").matches(first), first)
        assertNotEquals(first, MessageBuilder.newMessageId("riseup.net"))
        assertTrue(Regex("<$uuid@bücher\\.example>").matches(MessageBuilder.newMessageId("bücher.example")))
        assertEquals(listOf(first), HeaderSafety.messageIds(first))
        // A fresh id is accepted by the builder.
        assertEquals(first, headers(builder(messageId = first).message())["Message-ID"])
    }

    @Test
    fun newMessageIdRejectsBadDomains() {
        val bad = listOf("", "a b.example", "x.example>\r\nBcc: spy@evil.example", "a..example", "-a.example", "a@b")
        for (domain in bad) {
            assertFailsWith<IllegalArgumentException>(domain) { MessageBuilder.newMessageId(domain) }
        }
    }

    @Test
    fun invalidMessageIdRejected() {
        val bad = listOf(
            "", "no-brackets@x", "<nodomain>", "<a@b> <c@d>", "<a@b>\r\nBcc: spy@evil.example", "<a\u0000b@c>",
        )
        for (messageId in bad) {
            assertFailsWith<IllegalArgumentException>(messageId) { builder(messageId = messageId).build(false) }
        }
    }

    @Test
    fun invalidAddressRejectedWithoutLeakingIt() {
        val bad = listOf(
            "bob@",
            "no-at-sign",
            "",
            "a b@example.org",
            "a@b.example>\r\nRCPT TO:<spy@evil.example",
            "eve@evil.example\r\nBcc: spy@evil.example",
            "<eve@evil.example>",
        )
        for (email in bad) {
            val a = Address("X", email)
            val builders = listOf(
                builder(from = a), builder(to = listOf(jan, a)), builder(cc = listOf(a)), builder(bcc = listOf(a)),
            )
            for (b in builders) {
                val e = assertFailsWith<IllegalArgumentException>(email) { b.build(includeBcc = false) }
                if (email.isNotEmpty()) assertFalse(email in e.message.orEmpty())
            }
        }
    }

    @Test
    fun internationalAddressesAccepted() {
        val to = listOf(Address("Иван", "иван@пример.рф"), Address(null, "jörg@bücher.example"))
        assertEquals(to, addresses(builder(to = to).message(), "To"))
    }

    @Test
    fun lineEndsBecomeCrlf() {
        assertEquals("a\r\nb\r\nc\r\nd\r\n\r\ne\r\n", body(builder(text = "a\rb\r\nc\nd\n\ne").message()))
        assertEquals("\r\n", body(builder(text = "").message()))
        assertEquals("\r\n", body(builder(text = "\n").message()))
        assertEquals("x\r\n\r\n", body(builder(text = "x\r\n\r\n").message()))
    }

    @Test
    fun asciiBodyIs7bitUpTo998() {
        val ok = builder(text = "-- \n" + "a".repeat(998) + "\n.\n").message()
        assertEquals("7bit", headers(ok)["Content-Transfer-Encoding"])
        assertEquals("-- \r\n" + "a".repeat(998) + "\r\n.\r\n", body(ok))

        val long = builder(text = "a".repeat(999)).message()
        assertEquals("quoted-printable", headers(long)["Content-Transfer-Encoding"])
        assertEquals("a".repeat(999) + "\r\n", decode(body(long), "quoted-printable"))
    }

    @Test
    fun quotedPrintableExact() {
        val message = builder(text = "Čau světe = 1\t\n-- \nPetr\t\n.\nctrl\u0000\u007F\n").message()
        assertEquals("quoted-printable", headers(message)["Content-Transfer-Encoding"])
        assertEquals("text/plain; charset=utf-8", headers(message)["Content-Type"])
        assertEquals("=C4=8Cau sv=C4=9Bte =3D 1=09\r\n--=20\r\nPetr=09\r\n.\r\nctrl=00=7F\r\n", body(message))
    }

    @Test
    fun quotedPrintableSoftBreaks() {
        val body76 = body(builder(text = "ü\n" + "a".repeat(76) + "\n" + "b".repeat(80)).message())
        assertEquals("=C3=BC\r\n" + "a".repeat(76) + "\r\n" + "b".repeat(75) + "=\r\n" + "bbbbb\r\n", body76)

        // A trailing space that lands after a soft break is still encoded.
        val trailing = body(builder(text = "ü\n" + "c".repeat(75) + " ").message())
        assertEquals("=C3=BC\r\n" + "c".repeat(75) + "=\r\n=20\r\n", trailing)

        // An escape is never split by a soft break.
        val lines = body(builder(text = "é".repeat(40)).message()).removeSuffix("\r\n").split("\r\n")
        assertEquals(listOf(76, 76, 76, 15), lines.map { it.length })
        assertTrue(lines.dropLast(1).all { Regex("(=[0-9A-F]{2})+=").matches(it) })
    }

    @Test
    fun quotedPrintableRoundTrip() {
        val alphabet = listOf(
            "a", "Z", "0", " ", "  ", "\t", "=", "=3D", ".", "-- ", "é", "日", "🎉", "\n", "\r\n", "\r", "\u0000",
            "\u007F", "From ", "x".repeat(70),
        )
        val random = Random(2026)
        repeat(300) {
            val text = buildString { repeat(random.nextInt(0, 60)) { append(alphabet.random(random)) } }
            val message = builder(text = text).message()
            val encoding = headers(message)["Content-Transfer-Encoding"]!!
            val normalised = text.replace(Regex("\r\n|\r|\n"), "\r\n")
            val expected = if (normalised.endsWith("\r\n")) normalised else "$normalised\r\n"
            assertEquals(expected, decode(body(message), encoding), text)
            if (encoding == "quoted-printable") {
                for (line in body(message).split("\r\n")) {
                    assertTrue(line.length <= 76, line)
                    assertFalse(line.endsWith(" ") || line.endsWith("\t"), line)
                    assertTrue(line.all { it == '\t' || it in ' '..'~' }, line)
                }
            }
        }
    }

    @Test
    fun nonAsciiSubjectEncodedAndFolded() {
        val short = builder(subject = "Čau").message()
        assertEquals("Subject: =?UTF-8?B?xIxhdQ==?=", fieldLines(short, "Subject").single())

        val subject = "Re: Příliš žluťoučký kůň úpěl ďábelské ódy, 日本語 🎉 ".repeat(4).trim()
        val message = builder(subject = subject).message()
        assertHeaderLinesWithin78(message)
        val lines = fieldLines(message, "Subject")
        assertTrue(lines.size > 3)
        assertTrue(lines.all { line -> line.all { it in ' '..'~' } })
        assertEquals(subject, EncodedWords.decode(headers(message)["Subject"]!!))

        val cjk = "会議の議題について".repeat(120)
        val long = builder(subject = cjk).message()
        assertHeaderLinesWithin78(long)
        assertEquals(cjk, EncodedWords.decode(headers(long)["Subject"]!!))
    }

    @Test
    fun asciiSubjectFoldedAtSpaces() {
        val subject = (1..40).joinToString(" ") { "word$it" }
        val message = builder(subject = subject).message()
        assertHeaderLinesWithin78(message)
        assertTrue(fieldLines(message, "Subject").size > 2)
        assertEquals(subject, headers(message)["Subject"])
        assertEquals("Subject:", fieldLines(builder(subject = " \r\n").message(), "Subject").single())
    }

    @Test
    fun subjectInjectionAddsNoHeaders() {
        val decoded = EncodedWords.decode("=?UTF-8?Q?Invoice=0D=0ABcc:_spy@evil.example=0D=0A=0D=0A<b>x</b>?=")
        for (subject in listOf("Hi\r\nBcc: spy@evil.example\r\n\r\nbody", "Hi\nX-Evil: 1", "Hi\rTo: a@b", decoded)) {
            val message = builder(subject = "Re: $subject").message()
            assertEquals(PLAIN, headerNames(message))
            assertEquals("Re: " + HeaderSafety.clean(subject), headers(message)["Subject"])
            assertEquals("Hi Jan,\r\nsee you.\r\n", body(message))
        }
    }

    @Test
    fun displayNameInjectionAddsNoHeaders() {
        val eve = Address("Eve\r\nBcc: spy@evil.example\r\n\r\nbody", "eve@evil.example")
        val from = Address("Me\nX-Evil: 1", "petr@riseup.net")
        val message = builder(from = from, to = listOf(eve), cc = listOf(eve)).message()
        assertEquals(ALL - "Bcc" - "In-Reply-To" - "References", headerNames(message))
        assertEquals(listOf("eve@evil.example"), addresses(message, "To").map { it.email })
        assertEquals(listOf(Address("Eve Bcc: spy@evil.example body", "eve@evil.example")), addresses(message, "Cc"))
        assertEquals(listOf(Address("Me X-Evil: 1", "petr@riseup.net")), addresses(message, "From"))
    }

    @Test
    fun threadingInjectionAddsNoHeaders() {
        val message = builder(
            inReplyTo = "<p@x.example>\r\nBcc: spy@evil.example",
            references = listOf(
                "<a@x.example>\r\nX-Evil: 1",
                "<b@x.example>\n\n<c@x.example>",
                "\r\nBcc: <spy@evil.example>",
            ),
        ).message()
        assertEquals(ALL - "Cc" - "Bcc", headerNames(message))
        assertEquals("<p@x.example>", headers(message)["In-Reply-To"])
        assertEquals("<a@x.example> <b@x.example> <c@x.example> <spy@evil.example>", headers(message)["References"])
    }

    @Test
    fun threadingIdsAreValidated() {
        val tooLong = "<" + "y".repeat(2000) + "@z>"
        val message = builder(
            inReplyTo = "junk <p@x> <q@x>",
            references = listOf("<a@b>", "junk", "<a@b>", "<x>", tooLong, "<ü@x>", "<c@d> <e@f>", "<g\u0001h@i>"),
        ).message()
        assertEquals("<p@x>", headers(message)["In-Reply-To"])
        assertEquals("<a@b> <c@d> <e@f>", headers(message)["References"])
    }

    @Test
    fun referencesTrimmedToFirstAndLastTen() {
        val ids = (1..30).map { "<CAFx3=msg$it+abcdefghijklmnopqrstuvwxyz@mail.gmail.com>" }
        val message = builder(references = ids).message()
        assertHeaderLinesWithin78(message)
        assertEquals(listOf(ids[0]) + ids.takeLast(10), HeaderSafety.messageIds(headers(message)["References"]!!))
        assertEquals(11, fieldLines(message, "References").size)

        val eleven = ids.take(11)
        assertEquals(eleven, HeaderSafety.messageIds(headers(builder(references = eleven).message())["References"]!!))
    }

    @Test
    fun longRecipientListFoldsAtCommas() {
        val to = (1..120).map {
            when (it % 4) {
                0 -> Address("Person $it", "person$it@example.org")
                1 -> Address("Doe, John $it", "john.doe$it@example.org")
                2 -> Address("Jiří Novák $it", "jiri$it@seznam.cz")
                else -> Address(null, "plain$it@example.net")
            }
        }
        val message = builder(to = to, cc = to.take(7)).message()
        assertHeaderLinesWithin78(message)
        val lines = fieldLines(message, "To")
        assertTrue(lines.size > 30)
        assertTrue(lines.dropLast(1).all { it.endsWith(",") }, "folds only after a comma")
        assertTrue(lines.drop(1).all { it.startsWith(" ") && !it.startsWith("  ") })
        assertEquals(to, addresses(message, "To"))
        assertEquals(to.take(7), addresses(message, "Cc"))
    }

    @Test
    fun longDisplayNamesFoldInside() {
        val long = Address("Ž".repeat(120), "z@example.org")
        val quoted = Address("Smith, " + (1..30).joinToString(" ") { "Name$it" }, "s@example.org")
        val message = builder(from = long, to = listOf(quoted, long)).message()
        assertHeaderLinesWithin78(message)
        assertEquals(listOf(long), addresses(message, "From"))
        assertEquals(listOf(quoted, long), addresses(message, "To"))
    }

    @Test
    fun quotedDisplayNames() {
        val to = listOf(Address("Doe, John", "john@example.org"), Address("Say \"hi\" \\o/", "hi@example.org"))
        val message = builder(to = to).message()
        assertEquals(
            "To: \"Doe, John\" <john@example.org>, \"Say \\\"hi\\\" \\\\o/\" <hi@example.org>",
            fieldLines(message, "To").single(),
        )
        assertEquals(to, addresses(message, "To"))
    }

    @Test
    fun hostileLengthsStayUnderHardLimit() {
        val message = builder(
            from = Address("\"".repeat(3000), "petr@riseup.net"),
            to = listOf(Address("x".repeat(5000), "a@example.org"), Address("Ω".repeat(3000), "b@example.org")),
            subject = "Re: " + "s".repeat(5000) + " end",
            inReplyTo = "<" + "p".repeat(3000) + "@x>",
            references = listOf("<" + "r".repeat(3000) + "@x>", "<ok@x>"),
            attachments = listOf(file("n".repeat(3000) + ".txt"), file("名".repeat(1000) + ".txt")),
        ).message()
        assertEquals("Re: " + "s".repeat(400) + " end", headers(message)["Subject"])
        assertEquals("\"".repeat(400), addresses(message, "From").single().name)
        // Encoded words fold anywhere, so non-ASCII text is kept whole.
        assertEquals(listOf("x".repeat(400), "Ω".repeat(3000)), addresses(message, "To").map { it.name })
        assertNull(headers(message)["In-Reply-To"])
        assertEquals("<ok@x>", headers(message)["References"])
        val (_, ascii, cjk) = parts(message)
        assertEquals("n".repeat(3000) + ".txt", param(ascii, "Content-Disposition", "filename"))
        assertEquals("名".repeat(1000) + ".txt", param(cjk, "Content-Disposition", "filename"))
        assertPartHeaderLinesFolded(ascii)
        assertPartHeaderLinesFolded(cjk)
    }

    @Test
    fun multipartStructure() {
        val pdf = Random(1).nextBytes(5000)
        val message = builder(attachments = listOf(file("report.pdf", "application/pdf", pdf))).message()
        val contentType = fieldLines(message, "Content-Type").single()
        assertTrue(Regex("Content-Type: multipart/mixed; boundary=\"----[0-9A-Z]{30}\"").matches(contentType))
        assertEquals(PLAIN - "Content-Transfer-Encoding", headerNames(message))
        val (text, attachment) = parts(message)
        assertEquals(
            "Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 7bit\r\n\r\nHi Jan,\r\nsee you.",
            text,
        )
        assertEquals(
            listOf(
                "Content-Type: application/pdf; name=\"report.pdf\"",
                "Content-Disposition: attachment; filename=\"report.pdf\"",
                "Content-Transfer-Encoding: base64",
            ),
            headerLines(attachment),
        )
        assertContentEquals(pdf, decodedPart(attachment))
    }

    @Test
    fun deterministicExceptBoundary() {
        val plain = builder(subject = "Čau", text = "ü")
        assertContentEquals(plain.build(true), plain.build(true))

        val b = builder(attachments = listOf(file("a.bin", "application/octet-stream", byteArrayOf(1, 2, 3))))
        val first = b.message()
        val second = b.message()
        val boundary = Regex("----[0-9A-Z]{30}")
        assertNotEquals(boundary.find(first)!!.value, boundary.find(second)!!.value)
        assertEquals(first.replace(boundary, "B"), second.replace(boundary, "B"))
    }

    @Test
    fun attachmentRoundTrip() {
        val random = Random(7)
        val sizes = listOf(0, 1, 2, 3, 56, 57, 58, 76, 1000, 65_537)
        val files = sizes.map { file("file$it.bin", "application/octet-stream", random.nextBytes(it)) }
        val parts = parts(builder(text = "Příloha", attachments = files).message())
        assertEquals(files.size + 1, parts.size)
        assertEquals("quoted-printable", headers(parts[0])["Content-Transfer-Encoding"])
        assertEquals("Příloha\r\n", String(decodedPart(parts[0]), Charsets.UTF_8))
        files.forEachIndexed { i, file ->
            val part = parts[i + 1]
            assertEquals("base64", headers(part)["Content-Transfer-Encoding"])
            assertEquals("application/octet-stream", ContentType.parse(headers(part)["Content-Type"]!!).mimeType)
            for (line in body(part).split("\r\n")) assertTrue(line.length <= 76 && line.all { it in ' '..'~' }, line)
            assertContentEquals(file.bytes, decodedPart(part))
        }
    }

    @Test
    fun asciiFilenameQuotedAndEscaped() {
        val name = "say \"hi\" \\ bye.txt"
        val part = partOf(file(name))
        assertEquals(
            listOf(
                "Content-Type: text/plain; name=\"say \\\"hi\\\" \\\\ bye.txt\"",
                "Content-Disposition: attachment; filename=\"say \\\"hi\\\" \\\\ bye.txt\"",
                "Content-Transfer-Encoding: base64",
            ),
            headerLines(part),
        )
        assertEquals(name, param(part, "Content-Disposition", "filename"))
        assertEquals(name, param(part, "Content-Type", "name"))
    }

    @Test
    fun nonAsciiFilename() {
        val name = "Přehled výdajů.pdf"
        val part = partOf(file(name, "application/pdf"))
        assertEquals(
            listOf(
                "Content-Type: application/pdf; name=\"=?UTF-8?B?UMWZZWhsZWQgdsO9ZGFqxa8ucGRm?=\"",
                "Content-Disposition: attachment;",
                " filename*=utf-8''P%C5%99ehled%20v%C3%BDdaj%C5%AF.pdf",
                "Content-Transfer-Encoding: base64",
            ),
            headerLines(part),
        )
        assertEquals(name, param(part, "Content-Disposition", "filename"))
        assertEquals(name, param(part, "Content-Type", "name"))
    }

    @Test
    fun longFilenamesUseRfc2231Sections() {
        val names = listOf(
            "会議資料".repeat(20) + ".pdf",
            "🎉 party photo 🎉 ".repeat(6).trim() + ".png",
            "Quarterly report for the board meeting, final version (2), with appendix.pdf",
            "x".repeat(300) + ".txt",
            "a \"quoted\" \\ name ".repeat(8).trim(),
        )
        val parts = parts(builder(attachments = names.map { file(it, "application/pdf", ByteArray(3)) }).message())
        names.forEachIndexed { i, name ->
            val part = parts[i + 1]
            assertPartHeaderLinesFolded(part)
            val disposition = headers(part)["Content-Disposition"]!!
            assertTrue(disposition.startsWith("attachment; filename*0"), disposition)
            assertEquals(name, ContentType.parse(disposition).params["filename"])
            assertEquals(name, param(part, "Content-Type", "name"))
        }
    }

    @Test
    fun filenameInjectionAddsNoHeaders() {
        val names = listOf(
            "evil.txt\r\nContent-Type: text/html\r\n\r\n<script>",
            "evil\".txt; x=\"y",
            "ok.txt\nContent-Transfer-Encoding: 7bit",
            "ü.txt\r\nX-Evil: 1",
        )
        val parts = parts(builder(attachments = names.map { file(it, bytes = "data".toByteArray()) }).message())
        assertEquals(names.size + 1, parts.size)
        names.forEachIndexed { i, name ->
            val part = parts[i + 1]
            assertEquals(PART, headerNames(part))
            assertEquals(HeaderSafety.clean(name), param(part, "Content-Disposition", "filename"))
            assertEquals("attachment", ContentType.parse(headers(part)["Content-Disposition"]!!).type)
            assertEquals("base64", headers(part)["Content-Transfer-Encoding"])
            assertEquals("data", String(decodedPart(part)))
        }
    }

    @Test
    fun emptyFilenameFallsBack() {
        assertEquals("attachment", param(partOf(file(" \r\n")), "Content-Disposition", "filename"))
    }

    @Test
    fun mimeTypeValidated() {
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val cases = mapOf(
            "image/png" to "image/png",
            "Image/PNG" to "image/png",
            docx to docx,
            "image/svg+xml" to "image/svg+xml",
            "text/html\r\nX-Evil: 1" to "application/octet-stream",
            "text/plain; charset=utf-8" to "application/octet-stream",
            "foo" to "application/octet-stream",
            "a/b/c" to "application/octet-stream",
            "/png" to "application/octet-stream",
            "" to "application/octet-stream",
            "multipart/mixed" to "application/octet-stream",
            "message/rfc822" to "application/octet-stream",
            "imäge/png" to "application/octet-stream",
            // RFC 6838 §4.2: at most 127 characters each; longer would make an unfoldable line.
            "application/" + "x".repeat(127) to "application/" + "x".repeat(127),
            "application/" + "x".repeat(128) to "application/octet-stream",
            "x".repeat(128) + "/png" to "application/octet-stream",
            "application/" + "x".repeat(2000) to "application/octet-stream",
        )
        val parts = parts(builder(attachments = cases.keys.map { file(type = it) }).message())
        cases.values.forEachIndexed { i, expected ->
            val part = parts[i + 1]
            assertEquals(PART, headerNames(part))
            assertEquals("$expected; name=\"f\"", headers(part)["Content-Type"])
        }
    }

    @Test
    fun gmailReplyAllFixture() {
        // Header fields of a Gmail message as fetched from imap.gmail.com.
        val parent = HeaderBlock.parse(
            (
                "MIME-Version: 1.0\r\n" +
                    "References: <CAFx3=first+Zq@mail.gmail.com>\r\n" +
                    " <CAFx3=second-9x@mail.gmail.com>\r\n" +
                    "In-Reply-To: <CAFx3=second-9x@mail.gmail.com>\r\n" +
                    "From: =?UTF-8?Q?Jan_Nov=C3=A1k?= <jan.novak@gmail.com>\r\n" +
                    "Date: Fri, 16 Oct 2026 11:14:03 +0200\r\n" +
                    "Message-ID: <CAFx3=third_kQ+abc@mail.gmail.com>\r\n" +
                    "Subject: =?UTF-8?B?UmU6IMSMYXUsIGphayBzZSBtw6HFoT8=?=\r\n" +
                    "To: Petr <petr@riseup.net>\r\n" +
                    "Cc: \"Doe, John\" <john@example.org>, eva@example.org\r\n" +
                    "Content-Type: multipart/alternative; boundary=\"000000000000b0e5f5060d3c9a2d\"\r\n\r\n"
                ).toByteArray(),
        )
        val parentId = parent["Message-ID"]!!
        val others = Addresses.parseList(parent["To"]!!) + Addresses.parseList(parent["Cc"]!!)
        val message = builder(
            to = Addresses.parseList(parent["From"]!!),
            cc = others.filter { it.email != me.email },
            subject = EncodedWords.decode(parent["Subject"]!!),
            text = "On 2026-10-16 09:14 UTC, Jan Novák wrote:\n> Čau\n",
            inReplyTo = parentId,
            references = listOf(parent["References"]!!, parentId),
        ).message()
        val h = headers(message)
        assertEquals(listOf(jan), addresses(message, "To"))
        assertEquals(
            listOf(Address("Doe, John", "john@example.org"), Address(null, "eva@example.org")),
            addresses(message, "Cc"),
        )
        assertEquals("Re: Čau, jak se máš?", EncodedWords.decode(h["Subject"]!!))
        assertEquals("<CAFx3=third_kQ+abc@mail.gmail.com>", h["In-Reply-To"])
        assertEquals(
            "<CAFx3=first+Zq@mail.gmail.com> <CAFx3=second-9x@mail.gmail.com> <CAFx3=third_kQ+abc@mail.gmail.com>",
            h["References"],
        )
        assertEquals("On 2026-10-16 09:14 UTC, Jan Nov=C3=A1k wrote:\r\n> =C4=8Cau\r\n", body(message))
        assertHeaderLinesWithin78(message)
    }

    @Test
    fun dovecotListReplyFixture() {
        // A Riseup (Dovecot) list message with a long thread; Mailman folds References with tabs.
        val ids = (1..14).map { "<20261001.$it.abcdef@lists.riseup.net>" }
        val parent = HeaderBlock.parse(
            (
                "Return-Path: <list-bounces@lists.riseup.net>\r\n" +
                    "Delivered-To: petr@riseup.net\r\n" +
                    "Message-ID: <a1b2c3d4-0000-4000-8000-123456789abc@riseup.net>\r\n" +
                    "References: " + ids.joinToString("\r\n\t") + "\r\n" +
                    "From: Eva <eva@riseup.net>\r\n" +
                    "Subject: [crabgrass] Meeting notes\r\n\r\n"
                ).toByteArray(),
        )
        val parentId = parent["Message-ID"]!!
        val message = builder(
            to = Addresses.parseList(parent["From"]!!),
            subject = "Re: " + parent["Subject"],
            inReplyTo = parentId,
            references = listOf(parent["References"]!!, parentId),
        ).message()
        assertHeaderLinesWithin78(message)
        val expected = listOf(ids[0]) + (ids + parentId).takeLast(10)
        assertEquals(expected, HeaderSafety.messageIds(headers(message)["References"]!!))
        assertEquals(parentId, headers(message)["In-Reply-To"])
        assertEquals("Re: [crabgrass] Meeting notes", headers(message)["Subject"])
    }

    @Test
    fun hostileParentFixture() {
        // Encoded words that decode to CR/LF must not reach the reply's header structure.
        val parent = HeaderBlock.parse(
            (
                "From: =?UTF-8?Q?Eve=0D=0ABcc:_spy@evil.example?= <eve@evil.example>\r\n" +
                    "Subject: =?UTF-8?Q?Invoice=0D=0AContent-Type:_text/html=0D=0A=0D=0A<b>x</b>?=\r\n" +
                    "Message-ID: <m1@evil.example>\r\n" +
                    "References: <r1@evil.example> =?UTF-8?Q?=0D=0ABcc:_<spy@evil.example>?=\r\n\r\n"
                ).toByteArray(),
        )
        val fileName = EncodedWords.decode("=?UTF-8?Q?a.txt=0D=0AX-Evil:_1?=")
        val message = builder(
            to = Addresses.parseList(parent["From"]!!),
            subject = "Re: " + EncodedWords.decode(parent["Subject"]!!),
            inReplyTo = parent["Message-ID"],
            references = listOf(parent["References"]!!, parent["Message-ID"]!!),
            attachments = listOf(file(fileName)),
        ).message()
        assertEquals(ALL - "Cc" - "Bcc" - "Content-Transfer-Encoding", headerNames(message))
        assertEquals("Re: Invoice Content-Type: text/html <b>x</b>", headers(message)["Subject"])
        assertEquals(listOf("eve@evil.example"), addresses(message, "To").map { it.email })
        // The raw References value holds a well-formed token inside the encoded word; it stays a plain id.
        assertEquals("<r1@evil.example> <spy@evil.example> <m1@evil.example>", headers(message)["References"])
        assertEquals(PART, headerNames(parts(message)[1]))
    }

    private companion object {
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()

        /** Every top-level field in the required order. */
        val ALL = listOf(
            "Date", "From", "To", "Cc", "Bcc", "Subject", "Message-ID", "In-Reply-To", "References",
            "MIME-Version", "Content-Type", "Content-Transfer-Encoding",
        )

        /** A plain message to one recipient without threading fields. */
        val PLAIN = ALL - "Cc" - "Bcc" - "In-Reply-To" - "References"
        val PART = listOf("Content-Type", "Content-Disposition", "Content-Transfer-Encoding")
    }
}
