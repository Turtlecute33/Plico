package app.plico.mail.imap

import app.plico.mail.imap.FakeStream.Companion.literal
import app.plico.mail.sasl.Sasl
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImapClientTest {

    private val fake = FakeStream()
    private val client = ImapClient(ImapConnection(fake))

    private fun dovecot() {
        fake.server("* OK [CAPABILITY $RISEUP_PRE] Dovecot ready.")
        client.conn.greeting()
        fake.client("A1 AUTHENTICATE PLAIN ${Sasl.plain("alice@riseup.net", "pw")}")
            .server("A1 OK [CAPABILITY $RISEUP_POST] Logged in")
        client.authenticatePlain("alice@riseup.net", "pw")
    }

    private fun gmail() {
        fake.server("* OK Gimap ready for requests from 1.2.3.4 abc")
        client.conn.greeting()
        fake.client("A1 AUTHENTICATE XOAUTH2 ${Sasl.xoauth2("bob@gmail.com", "ya29.t")}")
            .server("* CAPABILITY $GMAIL_POST", "A1 OK bob@gmail.com authenticated (Success)")
        client.authenticateXoauth2("bob@gmail.com", "ya29.t")
    }

    private fun epoch(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun dovecotLoginWithSaslIr() {
        dovecot()
        assertTrue("QRESYNC" in client.caps)
        assertFalse(client.isGmail)
        client.requireCapabilities() // capabilities came with the tagged OK
        fake.finish()
    }

    @Test
    fun gmailLoginWithXoauth2() {
        gmail()
        assertTrue(client.isGmail)
        assertTrue("LITERAL-" in client.caps)
        client.requireCapabilities() // untagged CAPABILITY before the OK
        fake.finish()
    }

    @Test
    fun capabilityIsAskedWhenLoginDidNotSendIt() {
        fake.server("* OK [CAPABILITY $RISEUP_PRE] Dovecot ready.")
        client.conn.greeting()
        fake.client("A1 AUTHENTICATE PLAIN ${Sasl.plain("alice", "pw")}").server("A1 OK Logged in")
        client.authenticatePlain("alice", "pw")
        fake.client("A2 CAPABILITY").server("* CAPABILITY $RISEUP_POST", "A2 OK Capability completed.")
        client.requireCapabilities()
        client.requireCapabilities() // once is enough
        fake.finish()
    }

    @Test
    fun missingCapabilities() {
        fake.server("* OK ready")
        client.conn.greeting()
        fake.client("A1 AUTHENTICATE PLAIN ${Sasl.plain("a", "b")}")
            .server("A1 OK [CAPABILITY IMAP4rev1 IDLE ESEARCH] Logged in")
        client.authenticatePlain("a", "b")
        val e = assertFailsWith<ImapMissingCapabilityException> { client.requireCapabilities() }
        assertEquals(setOf("MOVE", "UIDPLUS"), e.missing)
        fake.finish()
    }

    @Test
    fun xoauth2FailureChallengeGetsAnEmptyLine() {
        fake.server("* OK Gimap ready for requests from 1.2.3.4 abc")
        client.conn.greeting()
        fake.client("A1 AUTHENTICATE XOAUTH2 ${Sasl.xoauth2("bob@gmail.com", "expired")}")
            .server("+ eyJzdGF0dXMiOiI0MDAiLCJzY2hlbWVzIjoiQmVhcmVyIiwic2NvcGUiOiJodHRwczovL21haWwuZ29vZ2xlLmNvbS8ifQ==")
            .client("")
            .server("A1 NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)")
        val e = assertFailsWith<ImapNoException> { client.authenticateXoauth2("bob@gmail.com", "expired") }
        assertEquals("AUTHENTICATIONFAILED", e.code?.name)
        fake.finish()
    }

    @Test
    fun plainFailure() {
        fake.server("* OK [CAPABILITY $RISEUP_PRE] Dovecot ready.")
        client.conn.greeting()
        fake.client("A1 AUTHENTICATE PLAIN ${Sasl.plain("alice", "bad")}")
            .server("A1 NO [AUTHENTICATIONFAILED] Authentication failed.")
        assertFailsWith<ImapNoException> { client.authenticatePlain("alice", "bad") }
        fake.finish()
    }

    @Test
    fun enable() {
        dovecot()
        fake.client("A2 ENABLE CONDSTORE").server("* ENABLED CONDSTORE", "A2 OK Enabled.")
        assertEquals(setOf("CONDSTORE"), client.enable("CONDSTORE"))
        assertEquals(emptySet(), client.enable())
        fake.finish()
    }

    @Test
    fun compressOnlyWhenAdvertised() {
        dovecot()
        fake.client("A2 COMPRESS DEFLATE").server("A2 OK Begin compression.")
        client.compress()
        assertEquals(1, fake.compressions)
        fake.finish()

        val plain = FakeStream().server("* OK [CAPABILITY IMAP4rev1] hi")
        ImapClient(ImapConnection(plain)).apply { conn.greeting() }.compress()
        assertEquals(0, plain.compressions)
        plain.finish()
    }

    @Test
    fun gmailList() {
        gmail()
        fake.client("A2 LIST \"\" \"*\" RETURN (SPECIAL-USE)").server(
            "* LIST (\\HasNoChildren) \"/\" \"INBOX\"",
            "* LIST (\\HasChildren \\Noselect) \"/\" \"[Gmail]\"",
            "* LIST (\\All \\HasNoChildren) \"/\" \"[Gmail]/All Mail\"",
            "* LIST (\\Drafts \\HasNoChildren) \"/\" \"[Gmail]/Bozze\"",
            "* LIST (\\HasNoChildren \\Sent) \"/\" \"[Gmail]/Posta inviata\"",
            "* LIST (\\HasNoChildren \\Trash) \"/\" \"[Gmail]/Cestino\"",
            "* LIST (\\HasNoChildren) \"/\" \"Caf&AOk-\"",
            "* LIST (\\HasNoChildren) \"/\" ${literal("Work \"x\"")}",
            "A2 OK Success",
        )
        val boxes = client.list()
        assertEquals(8, boxes.size)
        assertEquals(MailboxInfo("INBOX", "/", setOf("\\HasNoChildren")), boxes[0])
        assertEquals(MailboxInfo("[Gmail]/All Mail", "/", setOf("\\All", "\\HasNoChildren")), boxes[2])
        assertEquals(setOf("\\HasNoChildren", "\\Sent"), boxes[4].attributes)
        assertEquals("Café", boxes[6].name)
        assertEquals("Work \"x\"", boxes[7].name)
        fake.finish()
    }

    @Test
    fun dovecotList() {
        fake.server("* OK [CAPABILITY IMAP4rev1] Dovecot ready.")
        client.conn.greeting()
        fake.client("A1 LIST \"\" \"*\"").server(
            "* LIST (\\HasNoChildren \\UnMarked \\Junk) \".\" Spam",
            "* LIST (\\HasNoChildren) \".\" \"Entw&APw-rfe\"",
            "* LIST (\\Noinferiors) NIL inbox",
            "A1 OK List completed (0.001 + 0.000 secs).",
        )
        assertEquals(
            listOf(
                MailboxInfo("Spam", ".", setOf("\\HasNoChildren", "\\UnMarked", "\\Junk")),
                MailboxInfo("Entwürfe", ".", setOf("\\HasNoChildren")),
                MailboxInfo("INBOX", null, setOf("\\Noinferiors")),
            ),
            client.list(),
        )
        fake.finish()
    }

    @Test
    fun nonExistentParentIsNoselect() {
        dovecot()
        // Dovecot answers an extended LIST with \NonExistent alone for a missing parent of a listed child.
        fake.client("A2 LIST \"\" \"*\" RETURN (SPECIAL-USE)").server(
            "* LIST (\\NonExistent \\HasChildren) \".\" riseup",
            "* LIST (\\HasNoChildren) \".\" riseup.Lists",
            "* LIST (\\NoSelect \\HasChildren) \".\" Old",
            "A2 OK List completed (0.001 + 0.000 secs).",
        )
        assertEquals(
            listOf(
                MailboxInfo("riseup", ".", setOf("\\NonExistent", "\\HasChildren", "\\Noselect")),
                MailboxInfo("riseup.Lists", ".", setOf("\\HasNoChildren")),
                MailboxInfo("Old", ".", setOf("\\NoSelect", "\\HasChildren", "\\Noselect")),
            ),
            client.list(),
        )
        fake.finish()
    }

    @Test
    fun status() {
        dovecot()
        fake.client("A2 STATUS \"Entw&APw-rfe\" (MESSAGES UIDNEXT UIDVALIDITY HIGHESTMODSEQ)")
            .server("* STATUS \"Entw&APw-rfe\" (MESSAGES 3 UIDNEXT 9 UIDVALIDITY 1 HIGHESTMODSEQ 7)")
            .server("A2 OK Status completed.")
        assertEquals(MailboxStatus(3, 9, 1, 7), client.status("Entwürfe"))
        fake.client("A3 STATUS \"Trash\" (MESSAGES UIDNEXT UIDVALIDITY HIGHESTMODSEQ)")
            .server("* STATUS Trash (MESSAGES 0 UIDNEXT 1 UIDVALIDITY 5 HIGHESTMODSEQ 0)", "A3 OK Status completed.")
        assertEquals(MailboxStatus(0, 1, 5, null), client.status("Trash"))
        fake.finish()
    }

    @Test
    fun listStatusReadsEveryMailboxInOneRoundTrip() {
        dovecot()
        fake.client("A2 LIST \"\" \"*\" RETURN (STATUS (MESSAGES UIDNEXT UIDVALIDITY HIGHESTMODSEQ))").server(
            "* LIST (\\HasNoChildren) \".\" INBOX",
            "* STATUS INBOX (MESSAGES 12 UIDNEXT 40 UIDVALIDITY 3 HIGHESTMODSEQ 99)",
            "* LIST (\\HasNoChildren \\Junk) \".\" Spam",
            "* STATUS Spam (MESSAGES 0 UIDNEXT 1 UIDVALIDITY 5 HIGHESTMODSEQ 0)",
            "* LIST (\\HasNoChildren) \".\" \"Gr&APw-n\"",
            "* STATUS \"Gr&APw-n\" (MESSAGES 1 UIDNEXT 2 UIDVALIDITY 6 HIGHESTMODSEQ 4)",
            "* STATUS Broken (UIDNEXT 2)",
            "A2 OK List completed",
        )
        assertEquals(
            mapOf(
                "INBOX" to MailboxStatus(12, 40, 3, 99),
                "Spam" to MailboxStatus(0, 1, 5, null),
                "Grün" to MailboxStatus(1, 2, 6, 4),
            ),
            client.listStatus(),
            "names decoded from modified UTF-7; incomplete entries left out",
        )
        fake.finish()
    }

    @Test
    fun statusWithoutCondstore() {
        fake.server("* OK [CAPABILITY IMAP4rev1] ready")
        client.conn.greeting()
        fake.client("A1 STATUS \"INBOX\" (MESSAGES UIDNEXT UIDVALIDITY)")
            .server("* STATUS \"INBOX\" (MESSAGES 3 UIDNEXT 9 UIDVALIDITY 1)", "A1 OK done")
        assertEquals(MailboxStatus(3, 9, 1, null), client.status("INBOX"))
        fake.client("A2 STATUS \"X\" (MESSAGES UIDNEXT UIDVALIDITY)").server("* STATUS X (MESSAGES 3)", "A2 OK done")
        assertFailsWith<ImapProtocolException> { client.status("X") }
        fake.finish()
    }

    @Test
    fun gmailSelect() {
        gmail()
        fake.client("A2 SELECT \"[Gmail]/All Mail\"").server(
            "* FLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen \$NotPhishing \$Phishing)",
            "* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen \$NotPhishing \$Phishing \\*)] Flags permitted.",
            "* OK [UIDVALIDITY 11] UIDs valid.",
            "* 38505 EXISTS",
            "* 0 RECENT",
            "* OK [UIDNEXT 123045] Predicted next UID.",
            "* OK [HIGHESTMODSEQ 715194045007]",
            "A2 OK [READ-WRITE] [Gmail]/All Mail selected. (Success)",
        )
        assertEquals(SelectResult(38505, 11, 123045, 715194045007, false), client.select("[Gmail]/All Mail"))
        assertEquals("[Gmail]/All Mail", client.selected)
        fake.finish()
    }

    @Test
    fun dovecotExamineWithoutModseq() {
        dovecot()
        fake.client("A2 EXAMINE \"Archive\"").server(
            "* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \$Forwarded \$Junk \$NonJunk)",
            "* OK [PERMANENTFLAGS ()] Read-only mailbox.",
            "* 12 EXISTS",
            "* 0 RECENT",
            "* OK [UNSEEN 3] First unseen.",
            "* OK [UIDVALIDITY 1667312345] UIDs valid",
            "* OK [UIDNEXT 245] Predicted next UID",
            "* OK [NOMODSEQ] No permanent modsequences",
            "A2 OK [READ-ONLY] Examine completed (0.001 + 0.000 secs).",
        )
        assertEquals(SelectResult(12, 1667312345, 245, null, true), client.examine("Archive"))
        assertEquals("Archive", client.selected)
        fake.finish()
    }

    @Test
    fun failedSelectClearsSelected() {
        dovecot()
        fake.client("A2 SELECT \"INBOX\"")
            .server("* 1 EXISTS", "* OK [UIDVALIDITY 1] x", "* OK [UIDNEXT 2] x", "A2 OK done")
        client.select("INBOX")
        fake.client("A3 SELECT \"Gone\"").server("A3 NO [NONEXISTENT] Mailbox doesn't exist: Gone")
        val e = assertFailsWith<ImapNoException> { client.select("Gone") }
        assertEquals("NONEXISTENT", e.code?.name)
        assertNull(client.selected)
        fake.finish()
    }

    @Test
    fun uidSearchWithEsearch() {
        dovecot()
        fake.client("A2 UID SEARCH RETURN (ALL) UID 1:*")
            .server("* ESEARCH (TAG \"A2\") UID ALL 1:3,5", "A2 OK Search completed (0.001 + 0.000 secs).")
        assertEquals(UidSet.parse("1:3,5"), client.uidSearch(Command.raw("UID", "1:*")))
        fake.client("A3 UID SEARCH RETURN (ALL) UNSEEN").server("* ESEARCH (TAG \"A3\") UID", "A3 OK Search completed.")
        assertEquals(UidSet.EMPTY, client.uidSearch(Command.raw("UNSEEN")))
        fake.finish()
    }

    @Test
    fun uidSearchFallsBackToPlainSearch() {
        dovecot()
        fake.client("A2 UID SEARCH RETURN (ALL) FLAGGED").server("* SEARCH 7 2 5 6", "A2 OK done")
        assertEquals(UidSet.parse("2,5:7"), client.uidSearch(Command.raw("FLAGGED")))
        fake.client("A3 UID SEARCH RETURN (COUNT) FLAGGED").server("* SEARCH", "A3 OK done")
        assertEquals(0, client.uidSearchCount(Command.raw("FLAGGED")))
        fake.finish()
    }

    @Test
    fun nonAsciiSearchOnGmailUsesCharsetAndLiteralMinus() {
        gmail()
        fake.client("A2 UID SEARCH RETURN (ALL) CHARSET UTF-8 X-GM-RAW {9+}\r\nEntwürfe")
            .server("* ESEARCH (TAG \"A2\") UID ALL 4,9:10", "A2 OK SEARCH completed (Success)")
        assertEquals(UidSet.of(4, 9, 10), client.uidSearch(Command.build { raw("X-GM-RAW"); string("Entwürfe") }))
        fake.finish()
    }

    @Test
    fun uidSearchCount() {
        gmail()
        fake.client("A2 UID SEARCH RETURN (COUNT) X-GM-RAW \"in:inbox\"")
            .server("* ESEARCH (TAG \"A2\") UID COUNT 12", "A2 OK SEARCH completed (Success)")
        assertEquals(12, client.uidSearchCount(Command.build { raw("X-GM-RAW"); string("in:inbox") }))
        fake.finish()
    }

    @Test
    fun uidSearchMax() {
        dovecot()
        fake.client("A2 UID SEARCH RETURN (MAX) UID 1:499")
            .server("* ESEARCH (TAG \"A2\") UID MAX 488", "A2 OK Search completed (0.001 + 0.000 secs).")
        assertEquals(488, client.uidSearchMax(Command.raw("UID", "1:499")))
        fake.client("A3 UID SEARCH RETURN (MAX) UID 1:9").server("* ESEARCH (TAG \"A3\") UID", "A3 OK Search completed.")
        assertNull(client.uidSearchMax(Command.raw("UID", "1:9")))
        fake.client("A4 UID SEARCH RETURN (MAX) FLAGGED").server("* SEARCH 3 9 4", "A4 OK done")
        assertEquals(9, client.uidSearchMax(Command.raw("FLAGGED")))
        fake.client("A5 UID SEARCH RETURN (MAX) ALL").server("* ESEARCH (TAG \"A5\") UID MAX x", "A5 OK done")
        assertFailsWith<ImapProtocolException> { client.uidSearchMax(Command.raw("ALL")) }
    }

    @Test
    fun malformedSearchResultIsAProtocolError() {
        dovecot()
        fake.client("A2 UID SEARCH RETURN (ALL) ALL").server("* ESEARCH (TAG \"A2\") UID ALL 1:x", "A2 OK done")
        assertFailsWith<ImapProtocolException> { client.uidSearch(Command.raw("ALL")) }
        // The tagged OK is still unread: the connection is closed, not reused.
        assertTrue(fake.closed)
        assertFailsWith<java.io.IOException> { client.noop() }
    }

    @Test
    fun gmailFetch() {
        gmail()
        val header = "From: Alice <alice@example.com>\r\nSubject: Hi\r\n\r\n"
        val asked = "UID FLAGS MODSEQ INTERNALDATE RFC822.SIZE X-GM-MSGID X-GM-LABELS BODY.PEEK[HEADER.FIELDS (FROM SUBJECT)]"
        fake.client("A2 UID FETCH 34 ($asked)")
            .server(
                "* 12 FETCH (UID 34 FLAGS (\\Seen \$Junk) MODSEQ (12345) INTERNALDATE \"16-Oct-2026 09:14:03 +0200\" " +
                    "RFC822.SIZE 4200 X-GM-MSGID 1278455344230334865 " +
                    "X-GM-LABELS (\"\\\\Inbox\" \"\\\\Important\" Work \"Caf&AOk-\") " +
                    "BODY[HEADER.FIELDS (FROM SUBJECT)] ${literal(header)})",
                "A2 OK Success",
            )
        val items = ArrayList<FetchItem>()
        client.uidFetch(UidSet.of(34), asked) { items += it }
        val item = items.single()
        assertEquals(12, item.seq)
        assertEquals(34, item.uid)
        assertEquals(setOf("\\Seen", "\$Junk"), item.flags)
        assertEquals(12345, item.modSeq)
        assertEquals(epoch("2026-10-16T09:14:03+02:00"), item.internalDate)
        assertEquals(4200, item.size)
        assertEquals(1278455344230334865, item.gmMsgId)
        assertEquals(listOf("\\Inbox", "\\Important", "Work", "Café"), item.gmLabels)
        assertNull(item.bodyStructure)
        assertEquals(setOf("HEADER.FIELDS (FROM SUBJECT)"), item.sections.keys)
        assertContentEquals(header.toByteArray(), item.sections["HEADER.FIELDS (FROM SUBJECT)"])
        fake.finish()
    }

    @Test
    fun fetchBySequenceAddsUidAndSkipsUnsolicited() {
        dovecot()
        fake.client("A2 FETCH 1:3 (UID FLAGS INTERNALDATE)").server(
            "* 1 FETCH (UID 10 FLAGS () INTERNALDATE \" 6-Oct-2026 23:59:59 -0700\")",
            "* 2 FETCH (FLAGS (\\Seen))",
            "* 3 FETCH (FLAGS (\\Answered) UID 12 INTERNALDATE \"6-Oct-2026 00:00:00 +0000\")",
            "* 4 FETCH (UID 13 INTERNALDATE \"31-Feb-2026 00:00:00 +0000\")",
            "A2 OK Fetch completed (0.001 + 0.000 secs).",
        )
        val items = ArrayList<FetchItem>()
        client.fetch("1:3", "FLAGS INTERNALDATE") { items += it }
        assertEquals(listOf(10L, 12L, 13L), items.map { it.uid })
        assertEquals(emptySet(), items[0].flags)
        assertEquals(epoch("2026-10-07T06:59:59Z"), items[0].internalDate)
        assertEquals(epoch("2026-10-06T00:00:00Z"), items[1].internalDate)
        assertNull(items[2].internalDate)
        assertNull(items[0].modSeq)
        assertNull(items[0].gmLabels)
        fake.finish()
    }

    @Test
    fun partialSectionsAndBodyStructure() {
        dovecot()
        val structure = "(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"7BIT\" 3 1 NIL NIL NIL NIL)"
        fake.client("A2 UID FETCH 5 (UID BODYSTRUCTURE BODY.PEEK[1]<0.1048576> BODY.PEEK[])").server(
            "* 1 FETCH (UID 5 BODYSTRUCTURE $structure BODY[1]<0> {3}\r\nabc BODY[] {5}\r\nhello BODY[2] NIL)",
            "A2 OK Fetch completed.",
        )
        var item: FetchItem? = null
        client.uidFetch(UidSet.of(5), "BODYSTRUCTURE BODY.PEEK[1]<0.1048576> BODY.PEEK[]") { item = it }
        val got = item!!
        assertEquals(setOf("1<0>", ""), got.sections.keys)
        assertContentEquals("abc".toByteArray(), got.sections["1<0>"])
        assertContentEquals("hello".toByteArray(), got.sections[""])
        assertEquals(structure, got.bodyStructure.toString())
        fake.finish()
    }

    @Test
    fun uidFetchChangedSinceAndChunks() {
        dovecot()
        fake.client("A2 UID FETCH 1:* (UID FLAGS) (CHANGEDSINCE 12345)")
            .server("* 1 FETCH (UID 4 MODSEQ (12350) FLAGS (\\Seen))", "A2 OK Fetch completed.")
        val uids = ArrayList<Long>()
        client.uidFetch(UidSet.range(1), "FLAGS", changedSince = 12345) { uids += it.uid }
        assertEquals(listOf(4L), uids)

        val sparse = UidSet.of((1L..1001L step 2).toList())
        val chunks = sparse.chunks()
        fake.client("A3 UID FETCH ${chunks[0]} (UID)").server("A3 OK done")
        fake.client("A4 UID FETCH ${chunks[1]} (UID)").server("A4 OK done")
        client.uidFetch(sparse, "UID") {}
        client.uidFetch(UidSet.EMPTY, "FLAGS") {}
        fake.finish()
    }

    @Test
    fun storeFlags() {
        dovecot()
        fake.client("A2 UID STORE 3,5 +FLAGS.SILENT (\\Seen \$Junk)").server("A2 OK Store completed.")
        fake.client("A3 UID STORE 7:9 -FLAGS.SILENT (\\Flagged)").server("A3 OK Store completed.")
        client.uidStoreFlags(UidSet.of(3, 5), true, listOf("\\Seen", "\$Junk"))
        client.uidStoreFlags(UidSet.range(7, 9), false, listOf("\\Flagged"))
        client.uidStoreFlags(UidSet.of(1), true, emptyList())
        client.uidStoreFlags(UidSet.EMPTY, true, listOf("\\Seen"))
        for (bad in listOf("\\Seen) UID EXPUNGE", "a\\b", "", "\\Seen\r\n")) {
            assertFailsWith<IllegalArgumentException> { client.uidStoreFlags(UidSet.of(1), true, listOf(bad)) }
        }
        fake.finish()
    }

    @Test
    fun storeGmailLabels() {
        gmail()
        fake.client("A2 UID STORE 7 -X-GM-LABELS.SILENT (\\Inbox \"Caf&AOk-\" \"Work\" {6+}\r\nMy \"x\")")
            .server("A2 OK Success")
        fake.client("A3 UID STORE 7 +X-GM-LABELS.SILENT (\\Important)").server("A3 OK Success")
        client.uidStoreLabels(UidSet.of(7), false, listOf("\\Inbox", "Café", "Work", "My \"x\""))
        client.uidStoreLabels(UidSet.of(7), true, listOf("\\Important"))
        fake.finish()
    }

    @Test
    fun moveWithUntaggedCopyUid() {
        gmail()
        fake.client("A2 UID MOVE 304,319:320 \"[Gmail]/Trash\"").server(
            "* OK [COPYUID 38505 304,319:320 3956:3958] (Success)",
            "* 4 EXPUNGE",
            "* 4 EXPUNGE",
            "* 4 EXPUNGE",
            "A2 OK Success",
        )
        assertEquals(
            mapOf(304L to 3956L, 319L to 3957L, 320L to 3958L),
            client.uidMove(UidSet.parse("304,319:320"), "[Gmail]/Trash"),
        )
        fake.finish()
    }

    @Test
    fun moveWithTaggedCopyUid() {
        dovecot()
        fake.client("A2 UID MOVE 3 \"Archive\"")
            .server("* 2 EXPUNGE", "A2 OK [COPYUID 1667312345 3 17] Move completed.")
        assertEquals(mapOf(3L to 17L), client.uidMove(UidSet.of(3), "Archive"))
        fake.client("A3 UID MOVE 4 \"Archive\"").server("A3 OK Move completed.")
        assertEquals(emptyMap(), client.uidMove(UidSet.of(4), "Archive"))
        // A mapping that does not fit the request is ignored.
        fake.client("A4 UID MOVE 5 \"Archive\"").server("A4 OK [COPYUID 1 1:9 20:28] Move completed.")
        assertEquals(emptyMap(), client.uidMove(UidSet.of(5), "Archive"))
        fake.finish()
    }

    @Test
    fun copyUidPairsByWrittenOrder() {
        dovecot()
        fake.client("A2 UID MOVE 3,5 \"Archive\"").server("A2 OK [COPYUID 7 5,3 10:11] Move completed.")
        assertEquals(mapOf(5L to 10L, 3L to 11L), client.uidMove(UidSet.of(3, 5), "Archive"))
        // A range means the same in either direction (RFC 4315).
        fake.client("A3 UID MOVE 2:4 \"Archive\"").server("A3 OK [COPYUID 7 4:2 20,22,21] Move completed.")
        assertEquals(mapOf(2L to 20L, 3L to 22L, 4L to 21L), client.uidMove(UidSet.range(2, 4), "Archive"))
        // A UID twice, a source UID that was not asked for, or `*`: no mapping.
        for ((i, code) in listOf("3,3 10:11", "3,4 10:11", "3,5 10:*", "3,5 10,10").withIndex()) {
            val tag = "A${i + 4}"
            fake.client("$tag UID MOVE 3,5 \"Archive\"").server("$tag OK [COPYUID 7 $code] Move completed.")
            assertEquals(emptyMap(), client.uidMove(UidSet.of(3, 5), "Archive"), code)
        }
        fake.client("A8 UID MOVE 3 \"Archive\"").server("A8 OK [COPYUID 7 3:x 10] Move completed.")
        assertFailsWith<ImapProtocolException> { client.uidMove(UidSet.of(3), "Archive") }
        fake.finish()
    }

    @Test
    fun expunge() {
        dovecot()
        fake.client("A2 UID EXPUNGE 4:6").server("* 3 EXPUNGE", "A2 OK Expunge completed.")
        client.uidExpunge(UidSet.range(4, 6))
        client.uidExpunge(UidSet.EMPTY)
        fake.finish()
    }

    @Test
    fun appendWithLiteralPlus() {
        dovecot()
        val message = "From: a@riseup.net\r\nSubject: x\r\n\r\nbody\r\n"
        fake.client("A2 APPEND \"Sent\" (\\Seen) {${message.length}+}\r\n$message")
            .server("A2 OK [APPENDUID 1667312345 246] Append completed (0.004 + 0.000 + 0.003 secs).")
        assertEquals(246, client.append("Sent", listOf("\\Seen"), message.toByteArray()))
        fake.client("A3 APPEND \"Sent\" {4+}\r\nabcd").server("A3 OK Append completed.")
        assertNull(client.append("Sent", emptyList(), "abcd".toByteArray()))
        fake.finish()
    }

    @Test
    fun gmailAppendWithSynchronizingLiteral() {
        gmail()
        val message = "x".repeat(5000)
        fake.client("A2 APPEND \"[Gmail]/Drafts\" (\\Draft \\Seen) {5000}")
            .server("+ go ahead")
            .client(message)
            .server("A2 OK [APPENDUID 11 99] (Success)")
        assertEquals(99, client.append("[Gmail]/Drafts", listOf("\\Draft", "\\Seen"), message.toByteArray()))
        fake.finish()
    }

    @Test
    fun gmailAppendRefusedBeforeTheLiteral() {
        gmail()
        fake.client("A2 APPEND \"[Gmail]/Drafts\" (\\Draft \\Seen) {5000}")
            .server("A2 NO [TOOBIG] Message too large (Failure)")
        val e = assertFailsWith<ImapNoException> {
            client.append("[Gmail]/Drafts", listOf("\\Draft", "\\Seen"), ByteArray(5000))
        }
        assertEquals("TOOBIG", e.code?.name)
        fake.client("A3 NOOP").server("A3 OK Success")
        client.noop()
        fake.finish()
    }

    @Test
    fun create() {
        dovecot()
        fake.client("A2 CREATE \"Archive\"").server("A2 NO [ALREADYEXISTS] Mailbox already exists")
        client.create("Archive")
        fake.client("A3 CREATE \"Entw&APw-rfe\"").server("A3 OK Create completed.")
        client.create("Entwürfe")
        fake.client("A4 CREATE \"Spam\"").server("A4 NO [OVERQUOTA] Quota exceeded")
        assertFailsWith<ImapNoException> { client.create("Spam") }
        fake.finish()
    }

    @Test
    fun quota() {
        gmail()
        fake.client("A2 GETQUOTAROOT \"INBOX\"")
            .server("* QUOTAROOT INBOX \"\"", "* QUOTA \"\" (STORAGE 10 512)", "A2 OK Success")
        assertEquals(Quota(10, 512), client.quota())
        fake.client("A3 GETQUOTAROOT \"INBOX\"").server("* QUOTAROOT INBOX", "A3 OK Success")
        assertNull(client.quota())
        fake.finish()
    }

    @Test
    fun quotaNeedsTheCapability() {
        fake.server("* OK [CAPABILITY IMAP4rev1] ready")
        client.conn.greeting()
        assertNull(client.quota())
        fake.finish()
    }

    @Test
    fun noopAndBye() {
        gmail()
        fake.client("A2 NOOP").server("* 3 EXISTS", "A2 OK Success")
        client.noop()
        fake.client("A3 NOOP").server("* BYE Session expired, please login again.")
        assertFailsWith<ImapByeException> { client.noop() }
        fake.finish()
    }

    @Test
    fun idleAndStop() {
        dovecot()
        fake.client("A2 IDLE").server("+ idling", "* 5 EXISTS").client("DONE").server("A2 OK Idle completed.")
        val events = ArrayList<IdleEvent>()
        client.idle(object : IdleHooks {}) { events += it; client.stopIdle() }
        assertEquals(listOf<IdleEvent>(IdleEvent.Changed), events)
        fake.finish()
    }

    @Test
    fun logoutClosesTheStream() {
        dovecot()
        fake.client("A2 LOGOUT").server("* BYE Logging out", "A2 OK Logout completed (0.001 + 0.000 secs).")
        client.logout()
        assertTrue(fake.closed)
        assertNull(client.selected)
        fake.finish()
    }

    @Test
    fun logoutClosesEvenWhenTheServerHangsUp() {
        dovecot()
        fake.client("A2 LOGOUT").server("* BYE Logging out")
        assertFailsWith<java.io.EOFException> { client.logout() }
        assertTrue(fake.closed)
    }

    private companion object {
        const val RISEUP_PRE = "IMAP4rev1 SASL-IR LOGIN-REFERRALS ID ENABLE IDLE LITERAL+ AUTH=PLAIN AUTH=LOGIN"
        const val RISEUP_POST = "IMAP4rev1 SASL-IR LOGIN-REFERRALS ID ENABLE IDLE SORT SORT=DISPLAY THREAD=REFERENCES " +
            "THREAD=REFS THREAD=ORDEREDSUBJECT MULTIAPPEND URL-PARTIAL CATENATE UNSELECT CHILDREN NAMESPACE UIDPLUS " +
            "LIST-EXTENDED I18NLEVEL=1 CONDSTORE QRESYNC ESEARCH ESORT SEARCHRES WITHIN CONTEXT=SEARCH LIST-STATUS " +
            "BINARY MOVE SNIPPET=FUZZY LITERAL+ NOTIFY SPECIAL-USE COMPRESS=DEFLATE QUOTA"
        const val GMAIL_POST = "IMAP4rev1 UNSELECT IDLE NAMESPACE QUOTA ID XLIST CHILDREN X-GM-EXT-1 UIDPLUS " +
            "COMPRESS=DEFLATE ENABLE MOVE CONDSTORE ESEARCH UTF8=ACCEPT LIST-EXTENDED LIST-STATUS LITERAL- " +
            "SPECIAL-USE APPENDLIMIT=35651584"
    }
}
