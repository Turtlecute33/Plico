package app.plico.sync

import app.plico.data.FolderRole
import app.plico.data.MailView
import app.plico.data.NewAttachment
import app.plico.mail.imap.ImapProtocolException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bodies, attachments, server search and the reply/forward drafts that use them, on a scripted server. */
class BodiesFetchTest : OpFixture() {
    private val riseup = account()
    private val inbox = mailbox(riseup, "INBOX", FolderRole.INBOX)

    private fun literal(text: String) = "{${text.toByteArray().size}}\r\n$text"

    private val alternative = """(("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 12 1)""" +
        """("text" "html" ("charset" "utf-8") NIL NIL "7bit" 40 1) "alternative")"""

    @Test
    fun bodiesAreFetchedJoinedAndIndexed() {
        val id = insert(header(riseup, inbox, 7, date = clock)).single()
        val html = "<p>Fresh <b>pineapple</b> today</p>"
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID FETCH 7 (UID BODYSTRUCTURE)", "* 1 FETCH (UID 7 BODYSTRUCTURE $alternative)")
            .expect("UID FETCH 7 (UID BODY.PEEK[2])", "* 1 FETCH (UID 7 BODY[2] ${literal(html)})")
        assertEquals(1, Bodies.fetchWindow(session(riseup, imap), folders.get(inbox)!!, 25))
        imap.finish()
        val body = assertNotNull(messages.body(id))
        assertEquals(html, body.html)
        assertEquals("Fresh pineapple today", body.text)
        val message = messages.get(id)!!
        assertTrue(message.hasBody)
        assertEquals("Fresh pineapple today", message.snippet)
        val found = runBlocking { messages.observeList(MailView.Search("pineap", null), 10).first() }
        assertEquals(listOf(id), found.map { it.id })
        // Nothing is left in the window.
        assertEquals(0, Bodies.fetchWindow(session(riseup, FakeImap()), folders.get(inbox)!!, 25))
    }

    private val plain = """("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 2 1)"""

    @Test
    fun aRefusedBatchIsFetchedAloneAndTheBadRowIsSkipped() {
        val (good, _) = insert(header(riseup, inbox, 7, date = clock), header(riseup, inbox, 8, date = clock - 1_000))
        val skips = BodySkips()
        val refused = "NO Some messages could not be FETCHed (Failure)"
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID FETCH 7:8 (UID BODYSTRUCTURE)", "* 1 FETCH (UID 7 BODYSTRUCTURE $plain)", refused)
            .expect("UID FETCH 7 (UID BODYSTRUCTURE)", "* 1 FETCH (UID 7 BODYSTRUCTURE $plain)")
            .expect("UID FETCH 7 (UID BODY.PEEK[1])", "* 1 FETCH (UID 7 BODY[1] ${literal("Hi")})")
            .expect("UID FETCH 8 (UID BODYSTRUCTURE)", refused)
        assertEquals(1, Bodies.fetchWindow(session(riseup, imap), folders.get(inbox)!!, 25, skips))
        imap.finish()
        assertEquals("Hi", messages.body(good)?.text)
        // The connection stays open, and the refused row is not fetched again.
        assertEquals(0, Bodies.fetchWindow(session(riseup, FakeImap()), folders.get(inbox)!!, 25, skips))
    }

    @Test
    fun aStructureTooLongToReadIsFetchedAloneOnTheNextConnection() {
        val (good, _) = insert(header(riseup, inbox, 7, date = clock), header(riseup, inbox, 8, date = clock - 1_000))
        val skips = BodySkips()
        // More than the 1 MiB line limit of the reader, which then closes the connection.
        val huge = "(" + """("text" "plain" NIL NIL "${"d".repeat(1_000)}" "7bit" 2 1)""".repeat(1_100) + " \"mixed\")"
        val first = FakeImap()
            .select("INBOX")
            .expect("UID FETCH 7:8 (UID BODYSTRUCTURE)", "* 2 FETCH (UID 8 BODYSTRUCTURE $huge)")
        assertFailsWith<ImapProtocolException> { Bodies.fetchWindow(session(riseup, first), folders.get(inbox)!!, 25, skips) }
        val second = FakeImap()
            .select("INBOX")
            .expect("UID FETCH 7 (UID BODYSTRUCTURE)", "* 1 FETCH (UID 7 BODYSTRUCTURE $plain)")
            .expect("UID FETCH 7 (UID BODY.PEEK[1])", "* 1 FETCH (UID 7 BODY[1] ${literal("Hi")})")
            .expect("UID FETCH 8 (UID BODYSTRUCTURE)", "* 2 FETCH (UID 8 BODYSTRUCTURE $huge)")
        assertFailsWith<ImapProtocolException> { Bodies.fetchWindow(session(riseup, second), folders.get(inbox)!!, 25, skips) }
        second.finish()
        assertEquals("Hi", messages.body(good)?.text)
        // The third connection gets past the body step.
        assertEquals(0, Bodies.fetchWindow(session(riseup, FakeImap()), folders.get(inbox)!!, 25, skips))
    }

    @Test
    fun eachRunOfLargeBodiesIsSavedBeforeTheNextOneDownloads() {
        // PLICO-PERF-005: 4.5 MB of text in each message, so the two go in two runs of at most 8 MB.
        val (first, second) = insert(header(riseup, inbox, 7, date = clock), header(riseup, inbox, 8, date = clock - 1_000))
        val part = """("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 1500000 1)"""
        val large = "($part$part$part \"mixed\")"
        val items = "BODY.PEEK[1] BODY.PEEK[2] BODY.PEEK[3]"
        fun texts(uid: Int, n: Int) = "* $n FETCH (UID $uid BODY[1] ${literal("one")} BODY[2] ${literal("two")} BODY[3] ${literal("three")})"
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID FETCH 7:8 (UID BODYSTRUCTURE)", "* 1 FETCH (UID 7 BODYSTRUCTURE $large)", "* 2 FETCH (UID 8 BODYSTRUCTURE $large)")
            .expect("UID FETCH 7 (UID $items)", texts(7, 1))
            // More than the 1 MiB line limit of the reader, which then closes the connection.
            .expect("UID FETCH 8 (UID $items)", "* 2 FETCH (UID 8 X-JUNK ${"x".repeat(1_100_000)})")
        assertFailsWith<ImapProtocolException> { Bodies.fetchWindow(session(riseup, imap), folders.get(inbox)!!, 25) }
        imap.finish()
        assertTrue(messages.body(first)?.text.orEmpty().contains("three"), "the first run is saved before the second")
        assertNull(messages.body(second))
        val next = FakeImap()
            .select("INBOX")
            .expect("UID FETCH 8 (UID BODYSTRUCTURE)", "* 2 FETCH (UID 8 BODYSTRUCTURE $large)")
            .expect("UID FETCH 8 (UID $items)", texts(8, 2))
        assertEquals(1, Bodies.fetchWindow(session(riseup, next), folders.get(inbox)!!, 25))
        next.finish()
        assertTrue(messages.body(second)?.text.orEmpty().contains("three"))
    }

    @Test
    fun attachmentsDownloadInChunks() {
        val id = insert(header(riseup, inbox, 7)).single()
        val head = "a".repeat(1_048_576)
        messages.saveBody(id, null, "", "", "", listOf(NewAttachment("2", "notes.txt", "text/plain", head.length + 3L, "7bit", null, false)))
        val attachment = messages.attachments(id).single()
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID FETCH 7 (UID BODY.PEEK[2]<0.1048576>)", "* 1 FETCH (UID 7 BODY[2]<0> ${literal(head)})")
            .expect("UID FETCH 7 (UID BODY.PEEK[2]<1048576.1048576>)", "* 1 FETCH (UID 7 BODY[2]<1048576> ${literal("end")})")
        val progress = ArrayList<Float>()
        val file = Attachments.load(session(riseup, imap), attachment.id) { progress += it }
        imap.finish()
        assertEquals(head.length + 3L, file.length())
        assertTrue(file.readText().endsWith("aend"))
        assertEquals(1f, progress.last())
        // Cached now: no server needed.
        assertEquals(file, Attachments.load(session(riseup, FakeImap()), attachment.id) {})
    }

    @Test
    fun base64AttachmentsAreDecoded() {
        val id = insert(header(riseup, inbox, 7)).single()
        messages.saveBody(id, null, "", "", "", listOf(NewAttachment("1.2", "a.bin", "application/octet-stream", 8, "base64", null, false)))
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID FETCH 7 (UID BODY.PEEK[1.2]<0.1048576>)", "* 1 FETCH (UID 7 BODY[1.2]<0> ${literal("ZGF0YQ==")})")
        val file = Attachments.load(session(riseup, imap), messages.attachments(id).single().id) {}
        imap.finish()
        assertEquals("data", file.readText())
    }

    @Test
    fun serverSearchFetchesTheMissingHits() {
        val known = insert(header(riseup, inbox, 3)).single()
        val headers = "From: Ann <ann@example.org>\r\nSubject: Lunch plans\r\nMessage-ID: <x@example.org>\r\n\r\n"
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID SEARCH RETURN (ALL) CHARSET UTF-8 TEXT {literal}", "* ESEARCH UID ALL 3,9")
            .expect(
                Regex(Regex.escape("UID FETCH 9 (UID FLAGS INTERNALDATE RFC822.SIZE MODSEQ BODY.PEEK[HEADER.FIELDS (") + ".*"),
                "* 1 FETCH (UID 9 FLAGS (\\Seen) INTERNALDATE \"16-Oct-2026 09:14:59 +0000\" RFC822.SIZE 99 MODSEQ (5) " +
                    "BODY[HEADER.FIELDS (FROM SUBJECT MESSAGE-ID)] ${literal(headers)})",
            )
            .expect("UID FETCH 9 (UID BODYSTRUCTURE)", "* 1 FETCH (UID 9 BODYSTRUCTURE (\"text\" \"plain\" NIL NIL NIL \"7bit\" 9 1))")
            .expect("UID FETCH 9 (UID BODY.PEEK[1])", "* 1 FETCH (UID 9 BODY[1] ${literal("Café bar")})")
        val ids = Search.server(session(riseup, imap), "Café", null)
        imap.finish()
        assertEquals("UID SEARCH RETURN (ALL) CHARSET UTF-8 TEXT {literal}", imap.commands[1])
        assertEquals("Café", imap.literals.single().toString(Charsets.UTF_8))
        val hit = messages.byUid(inbox, 9)!!
        assertEquals(listOf(hit.id, known), ids)
        assertEquals("Lunch plans", hit.subject)
        assertEquals(1_792_142_099_000, hit.date)
        assertTrue(hit.notified && hit.seen && hit.hasBody)
    }

    @Test
    fun replyQuotesTheBodyAndForwardCopiesTheAttachments() {
        val source = insert(
            header(riseup, inbox, 7).copy(
                fromName = "Ann", fromEmail = "ann@example.org", toList = "user@riseup.net, Bob <bob@example.org>",
                subject = "Re: Plan", messageId = "<p@example.org>", refs = "<r@example.org>", date = 1_792_142_099_000,
            ),
        ).single()
        messages.saveBody(
            source, null, "Hello\nthere", "Hello there", "Hello there",
            listOf(NewAttachment("2", "plan.pdf", "application/pdf", 8, "base64", null, false)),
        )
        val replyId = runBlocking { actions.reply(source, all = true) }
        val reply = drafts.get(replyId)!!
        assertEquals("user@riseup.net", reply.fromEmail)
        assertEquals("Ann <ann@example.org>", reply.toList)
        assertEquals("Bob <bob@example.org>", reply.ccList)
        assertEquals("Re: Plan", reply.subject)
        assertEquals("\n\nOn 2026-10-16 09:14 UTC, Ann wrote:\n> Hello\n> there", reply.body)
        assertEquals("<p@example.org>", reply.inReplyTo)
        assertEquals("<r@example.org> <p@example.org>", reply.refs)
        assertEquals(source, reply.sourceMessage)

        val imap = FakeImap()
            .select("INBOX")
            .expect("UID FETCH 7 (UID BODY.PEEK[2]<0.1048576>)", "* 1 FETCH (UID 7 BODY[2]<0> ${literal("ZGF0YQ==")})")
        session(riseup, imap)
        val forwardId = runBlocking { actions.forward(source) }
        imap.finish()
        val forward = drafts.get(forwardId)!!
        assertEquals("Fwd: Re: Plan", forward.subject)
        assertTrue(forward.body.endsWith("Subject: Re: Plan\nTo: user@riseup.net, Bob <bob@example.org>\n\nHello\nthere"), forward.body)
        val copy = forward.attachments.single()
        assertEquals("plan.pdf", copy.name)
        assertEquals("data", store.files.outboxDir(forwardId).resolve(copy.file).readText())
    }
}
