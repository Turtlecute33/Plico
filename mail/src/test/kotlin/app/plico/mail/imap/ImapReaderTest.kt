package app.plico.mail.imap

import java.io.ByteArrayInputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ImapReaderTest {

    private fun reader(vararg lines: String, maxLiteral: Int = 1024, maxLine: Int = 4096) =
        ImapReader(ByteArrayInputStream(lines.joinToString("") { "$it\r\n" }.toByteArray()), maxLiteral, maxLine)

    private fun parse(line: String) = reader(line).read()
    private fun untagged(line: String) = assertIs<Untagged>(parse(line))

    private fun atom(text: String) = ImapValue.Atom(text)
    private fun str(text: String) = ImapValue.Str(text.toByteArray())
    private fun list(vararg items: ImapValue) = ImapValue.ListV(items.toList())
    private fun atoms(vararg texts: String) = texts.map(::atom)
    private fun atomList(vararg texts: String) = ImapValue.ListV(texts.map(::atom))

    @Test
    fun gmailGreeting() {
        assertEquals(
            Untagged(null, "OK", emptyList(), null, "Gimap ready for requests from 1.2.3.4 abc"),
            parse("* OK Gimap ready for requests from 1.2.3.4 abc"),
        )
    }

    @Test
    fun dovecotGreetingWithCapabilityCode() {
        val r = untagged(
            "* OK [CAPABILITY IMAP4rev1 SASL-IR LOGIN-REFERRALS ID ENABLE IDLE LITERAL+ AUTH=PLAIN AUTH=LOGIN] Dovecot ready.",
        )
        assertEquals("OK", r.keyword)
        assertEquals(
            RespCode(
                "CAPABILITY",
                atoms("IMAP4rev1", "SASL-IR", "LOGIN-REFERRALS", "ID", "ENABLE", "IDLE", "LITERAL+", "AUTH=PLAIN", "AUTH=LOGIN"),
            ),
            r.code,
        )
        assertEquals("Dovecot ready.", r.text)
        assertEquals(emptyList(), r.values)
    }

    @Test
    fun listWithQuotedNames() {
        val r = untagged("""* LIST (\HasChildren \Noselect) "/" "[Gmail]"""")
        assertEquals("LIST", r.keyword)
        assertEquals(listOf(list(atom("\\HasChildren"), atom("\\Noselect")), str("/"), str("[Gmail]")), r.values)
        assertNull(r.code)
        assertNull(r.text)
    }

    @Test
    fun listWithLiteralName() {
        val r = untagged("""* LIST (\HasNoChildren) "/" {11}""" + "\r\nWork \"2026\"")
        assertEquals(listOf(list(atom("\\HasNoChildren")), str("/"), str("Work \"2026\"")), r.values)
    }

    @Test
    fun listWithNilDelimiterAndAtomName() {
        assertEquals(
            listOf(atomList("\\Noinferiors"), ImapValue.Nil, atom("INBOX")),
            untagged("* LIST (\\Noinferiors) NIL INBOX").values,
        )
        assertEquals(
            listOf(list(atom("\\HasNoChildren"), atom("\\UnMarked"), atom("\\Junk")), str("."), atom("Spam")),
            untagged("* LIST (\\HasNoChildren \\UnMarked \\Junk) \".\" Spam").values,
        )
    }

    @Test
    fun unquotedNameWithBracketsIsOneAtom() {
        assertEquals(atom("[Gmail]/Sent"), untagged("* LIST () \"/\" [Gmail]/Sent").values[2])
        assertEquals(
            listOf(atom("X-GM-LABELS"), list(atom("[Old"), atom("Work"))),
            assertIs<ImapValue.ListV>(untagged("* 1 FETCH (X-GM-LABELS ([Old Work))").values.single()).items,
        )
    }

    @Test
    fun bracketInOtherAtomsIsPlainText() {
        // Dovecot sends a name of ATOM-CHARs unquoted; '[' is one.
        assertEquals(
            listOf(atom("Archive[2023"), atomList("MESSAGES", "3", "UIDNEXT", "9", "UIDVALIDITY", "1")),
            untagged("* STATUS Archive[2023 (MESSAGES 3 UIDNEXT 9 UIDVALIDITY 1)").values,
        )
        val fetch = untagged("* 1 FETCH (X-GM-LABELS (Q1[draft Work) FLAGS (foo[bar) UID 5)")
        assertEquals(
            listOf(atom("X-GM-LABELS"), atomList("Q1[draft", "Work"), atom("FLAGS"), atomList("foo[bar"), atom("UID"), atom("5")),
            assertIs<ImapValue.ListV>(fetch.values.single()).items,
        )
        assertEquals(atom("a[b"), untagged("* LIST () \".\" a[b").values[2])
    }

    @Test
    fun sectionKeysOfEveryFetchAttribute() {
        val r = untagged("* 2 FETCH (body[1 (x)] {1}\r\na BINARY[2]<0> {1}\r\nb BINARY.SIZE[3 4] 42 UID 7)")
        assertEquals(
            listOf(
                atom("body[1 (x)]"), str("a"), atom("BINARY[2]<0>"), str("b"), atom("BINARY.SIZE[3 4]"), atom("42"),
                atom("UID"), atom("7"),
            ),
            assertIs<ImapValue.ListV>(r.values.single()).items,
        )
    }

    @Test
    fun unterminatedSectionStopsAtTheLineEnd() {
        val input = ByteArrayInputStream("* 1 FETCH (BODY[HEADER (FROM\r\nA1 OK done\r\n".toByteArray())
        assertFailsWith<ImapProtocolException> { ImapReader(input).read() }
        assertEquals("\nA1 OK done\r\n".length, input.available())
    }

    @Test
    fun keywordsAreUpperCased() {
        val r = untagged("* list (\\Marked) \".\" inbox")
        assertEquals("LIST", r.keyword)
        assertEquals(atom("inbox"), r.values[2])
        val t = assertIs<Tagged>(parse("a1 ok done"))
        assertEquals("a1", t.tag)
        assertEquals("OK", t.status)
    }

    @Test
    fun esearch() {
        assertEquals(
            listOf(list(atom("TAG"), str("A5")), atom("UID"), atom("ALL"), atom("1:3,5")),
            untagged("* ESEARCH (TAG \"A5\") UID ALL 1:3,5").values,
        )
        assertEquals(
            listOf(list(atom("TAG"), str("A6")), atom("UID"), atom("COUNT"), atom("12")),
            untagged("* ESEARCH (TAG \"A6\") UID COUNT 12").values,
        )
    }

    @Test
    fun status() {
        val r = untagged("* STATUS \"INBOX\" (MESSAGES 3 UIDNEXT 9 UIDVALIDITY 1 HIGHESTMODSEQ 7)")
        assertEquals("STATUS", r.keyword)
        assertEquals(
            listOf(str("INBOX"), atomList("MESSAGES", "3", "UIDNEXT", "9", "UIDVALIDITY", "1", "HIGHESTMODSEQ", "7")),
            r.values,
        )
    }

    @Test
    fun gmailFetchWithLabelsAndSection() {
        val header = "From: Alice <alice@example.com>\r\nSubject: Hi\r\n\r\n"
        val r = untagged(
            "* 12 FETCH (UID 34 FLAGS (\\Seen \$Junk) MODSEQ (12345) INTERNALDATE \"16-Oct-2026 09:14:03 +0200\" " +
                "RFC822.SIZE 4200 X-GM-MSGID 1278455344230334865 " +
                "X-GM-LABELS (\"\\\\Inbox\" \"\\\\Important\" Work \"Caf&AOk-\") " +
                "BODY[HEADER.FIELDS (FROM SUBJECT)] {${header.length}}\r\n$header)",
        )
        assertEquals(12L, r.number)
        assertEquals("FETCH", r.keyword)
        val items = assertIs<ImapValue.ListV>(r.values.single()).items
        assertEquals(
            listOf(
                atom("UID"), atom("34"),
                atom("FLAGS"), list(atom("\\Seen"), atom("\$Junk")),
                atom("MODSEQ"), list(atom("12345")),
                atom("INTERNALDATE"), str("16-Oct-2026 09:14:03 +0200"),
                atom("RFC822.SIZE"), atom("4200"),
                atom("X-GM-MSGID"), atom("1278455344230334865"),
                atom("X-GM-LABELS"), list(str("\\Inbox"), str("\\Important"), atom("Work"), str("Caf&AOk-")),
                atom("BODY[HEADER.FIELDS (FROM SUBJECT)]"), str(header),
            ),
            items,
        )
    }

    @Test
    fun partialSectionAndBodyStructure() {
        val r = untagged(
            "* 3 FETCH (BODY[1.2]<0> {3}\r\nabc BODY[] \"\" " +
                "BODYSTRUCTURE (\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"7BIT\" 12 1 NIL NIL NIL NIL))",
        )
        val items = assertIs<ImapValue.ListV>(r.values.single()).items
        assertEquals(atom("BODY[1.2]<0>"), items[0])
        assertEquals(str("abc"), items[1])
        assertEquals(atom("BODY[]"), items[2])
        assertEquals(str(""), items[3])
        assertEquals(
            list(
                str("TEXT"), str("PLAIN"), list(str("CHARSET"), str("utf-8")), ImapValue.Nil, ImapValue.Nil,
                str("7BIT"), atom("12"), atom("1"), ImapValue.Nil, ImapValue.Nil, ImapValue.Nil, ImapValue.Nil,
            ),
            items[5],
        )
    }

    @Test
    fun responseCodes() {
        val modseq = untagged("* OK [HIGHESTMODSEQ 715194045007]")
        assertEquals(RespCode("HIGHESTMODSEQ", atoms("715194045007")), modseq.code)
        assertEquals("", modseq.text)

        val copy = untagged("* OK [COPYUID 38505 304,319:320 3956:3958] Moved")
        assertEquals(RespCode("COPYUID", atoms("38505", "304,319:320", "3956:3958")), copy.code)
        assertEquals("Moved", copy.text)

        val flags = untagged("* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Draft \\Seen \$Phishing \\*)] Flags permitted.")
        assertEquals(
            RespCode("PERMANENTFLAGS", listOf(atomList("\\Answered", "\\Flagged", "\\Draft", "\\Seen", "\$Phishing", "\\*"))),
            flags.code,
        )

        val url = "https://accounts.google.com/signin/continue?sarp=1&scc=1"
        val alert = untagged("* NO [WEBALERT $url] Web login required.")
        assertEquals(RespCode("WEBALERT", atoms(url)), alert.code)
        assertEquals("Web login required.", alert.text)
    }

    @Test
    fun taggedWithCodeAndText() {
        assertEquals(
            Tagged("A3", "OK", RespCode("APPENDUID", atoms("38505", "3955")), "APPEND completed"),
            parse("A3 OK [APPENDUID 38505 3955] APPEND completed"),
        )
        assertEquals(
            Tagged("A4", "OK", RespCode("READ-WRITE", emptyList()), "[Gmail]/All Mail selected. (Success)"),
            parse("A4 OK [READ-WRITE] [Gmail]/All Mail selected. (Success)"),
        )
        assertEquals(
            Tagged("A1", "NO", RespCode("AUTHENTICATIONFAILED", emptyList()), "Invalid credentials (Failure)"),
            parse("A1 NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)"),
        )
        assertEquals(Tagged("A9", "OK", null, ""), parse("A9 OK"))
    }

    @Test
    fun quota() {
        assertEquals(listOf(atom("INBOX"), str("")), untagged("* QUOTAROOT INBOX \"\"").values)
        assertEquals(listOf(str(""), atomList("STORAGE", "10", "512")), untagged("* QUOTA \"\" (STORAGE 10 512)").values)
    }

    @Test
    fun byeAndNumbers() {
        assertEquals(Untagged(null, "BYE", emptyList(), null, "Logging out"), parse("* BYE Logging out"))
        assertEquals(Untagged(23, "EXISTS", emptyList(), null, null), parse("* 23 EXISTS"))
        assertEquals(Untagged(0, "RECENT", emptyList(), null, null), parse("* 0 RECENT"))
        assertEquals(Untagged(4294967296, "EXPUNGE", emptyList(), null, null), parse("* 4294967296 EXPUNGE"))
        assertEquals(Untagged(null, "SEARCH", emptyList(), null, null), parse("* SEARCH "))
        assertEquals(atoms("CONDSTORE"), untagged("* ENABLED CONDSTORE").values)
        assertEquals(listOf(ImapValue.Nil), untagged("* ID nil").values)
    }

    @Test
    fun continuations() {
        assertEquals(Continuation("idling"), parse("+ idling"))
        assertEquals(Continuation("eyJzdGF0dXMiOiI0MDAifQ=="), parse("+ eyJzdGF0dXMiOiI0MDAifQ=="))
        assertEquals(Continuation(""), parse("+"))
        assertEquals(Continuation(""), parse("+ "))
    }

    @Test
    fun quotedEscapesAndUtf8() {
        val r = untagged("* LIST () \"/\" \"a\\\"b\\\\c Ü\"")
        assertEquals("a\"b\\c Ü", r.values[2].textOrNull())
    }

    @Test
    fun nonSynchronizingLiteralAndEightBitBytes() {
        val bytes = byteArrayOf(0x41, 0xff.toByte(), 0x0d, 0x0a, 0x00)
        val input = "* 1 FETCH (BODY[] {5+}\r\n".toByteArray() + bytes + ")\r\n".toByteArray()
        val r = assertIs<Untagged>(ImapReader(ByteArrayInputStream(input)).read())
        val value = assertIs<ImapValue.Str>(assertIs<ImapValue.ListV>(r.values.single()).items[1])
        assertContentEquals(bytes, value.bytes)
    }

    @Test
    fun emptyAndNestedLists() {
        assertEquals(
            listOf(list(atom("X-GM-LABELS"), list(), atom("UID"), atom("5"))),
            untagged("* 1 FETCH (X-GM-LABELS () UID 5)").values,
        )
        assertEquals(listOf(list(list(list(atom("a"))))), untagged("* X (((a)))").values)
    }

    @Test
    fun bareLineFeedIsAccepted() {
        val reader = ImapReader(ByteArrayInputStream("* 3 EXISTS\nA1 OK done\n".toByteArray()))
        assertEquals(Untagged(3, "EXISTS", emptyList(), null, null), reader.read())
        assertEquals(Tagged("A1", "OK", null, "done"), reader.read())
    }

    @Test
    fun readsResponsesInOrder() {
        val r = reader("* 2 EXISTS", "+ go ahead", "A1 OK done")
        assertIs<Untagged>(r.read())
        assertIs<Continuation>(r.read())
        assertIs<Tagged>(r.read())
        assertFailsWith<EOFException> { r.read() }
    }

    @Test
    fun neverReadsPastTheEndOfAResponse() {
        val compressed = byteArrayOf(0x78, 0x9c.toByte(), 0x01, 0x02, 0x03)
        val input = ByteArrayInputStream("A2 OK DEFLATE active\r\n".toByteArray() + compressed)
        assertEquals(Tagged("A2", "OK", null, "DEFLATE active"), ImapReader(input).read())
        assertEquals(compressed.size, input.available())

        val values = ByteArrayInputStream("* 1 FETCH (UID 5)\r\nX".toByteArray())
        ImapReader(values).read()
        assertEquals(1, values.available())
    }

    @Test
    fun limits() {
        assertFailsWith<ImapProtocolException> { reader("* 1 FETCH (BODY[] {1025}", maxLiteral = 1024).read() }
        assertFailsWith<ImapProtocolException> { reader("* OK " + "x".repeat(100), maxLine = 50).read() }
        // Literal bytes do not count against the line limit.
        val big = "y".repeat(100)
        assertEquals(str(big), assertIs<Untagged>(reader("* X {100}\r\n$big", maxLine = 50).read()).values.single())
    }

    @Test
    fun nestingLimit() {
        val deepest = "(".repeat(256) + "a" + ")".repeat(256)
        var value = untagged("* X $deepest").values.single()
        repeat(256) { value = assertIs<ImapValue.ListV>(value).items.single() }
        assertEquals(atom("a"), value)
        assertFailsWith<ImapProtocolException> { parse("* X ($deepest)") }
        assertFailsWith<ImapProtocolException> { parse("* OK [X ($deepest)] text") }
        // No StackOverflowError, which is not an IOException.
        val hostile = "* 1 FETCH (BODYSTRUCTURE " + "(".repeat(500_000) + "\r\n"
        assertFailsWith<ImapProtocolException> { ImapReader(ByteArrayInputStream(hostile.toByteArray())).read() }
        // The count starts again with each response.
        val twice = reader("* X $deepest", "* Y $deepest")
        twice.read()
        twice.read()
    }

    @Test
    fun literalBytesPerResponseLimit() {
        fun limited(vararg lines: String) = ImapReader(
            ByteArrayInputStream(lines.joinToString("") { "$it\r\n" }.toByteArray()),
            maxLiteral = 1000,
            maxResponse = 2000,
        )
        val k = "k".repeat(1000)
        val two = "* 1 FETCH (BODY[1] {1000}\r\n$k BODY[2] {1000}\r\n$k)"
        // Each response may use the whole budget.
        val ok = limited(two, two)
        assertEquals(4, (assertIs<Untagged>(ok.read()).values.single() as ImapValue.ListV).items.size)
        assertIs<Untagged>(ok.read())
        val three = "* 1 FETCH (BODY[1] {1000}\r\n$k BODY[2] {1000}\r\n$k BODY[3] {1}\r\nk)"
        assertFailsWith<ImapProtocolException> { limited(three).read() }
    }

    @Test
    fun endOfStream() {
        assertFailsWith<EOFException> { ImapReader(ByteArrayInputStream(ByteArray(0))).read() }
        assertFailsWith<EOFException> { ImapReader(ByteArrayInputStream("* OK no end".toByteArray())).read() }
        assertFailsWith<EOFException> { ImapReader(ByteArrayInputStream("* X {10}\r\nabc".toByteArray())).read() }
    }

    @Test
    fun malformedResponses() {
        assertFailsWith<ImapProtocolException> { parse("* LIST (\\Noselect") }
        assertFailsWith<ImapProtocolException> { parse("* LIST () \"/\" \"open") }
        assertFailsWith<ImapProtocolException> { parse("* OK [ALERT") }
        assertFailsWith<ImapProtocolException> { parse("* X {abc}") }
        assertFailsWith<ImapProtocolException> { parse("*X") }
        assertFailsWith<ImapProtocolException> { parse("") }
        assertFailsWith<ImapProtocolException> { parse("* 99999999999999999999 EXISTS") }
    }
}
