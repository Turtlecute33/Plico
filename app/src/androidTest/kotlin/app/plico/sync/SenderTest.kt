package app.plico.sync

import app.plico.R
import app.plico.data.DraftState
import app.plico.data.FolderRole
import app.plico.data.NewDraft
import app.plico.data.OpKind
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenderTest : OpFixture() {
    private val riseup = account()
    private val inbox = mailbox(riseup, "INBOX", FolderRole.INBOX)

    private fun send(ctx: OpContext) = runBlocking { Sender.run(ctx) }

    private fun runOps(ctx: OpContext) = runBlocking { Ops.run(ctx) }

    private fun queued(
        accountId: Long = riseup,
        from: String = "user@riseup.net",
        to: String = "Bob <bob@example.org>",
        bcc: String = "hidden@example.org",
        source: Long? = null,
    ): Long {
        val id = drafts.create(NewDraft(accountId, from, toList = to, bccList = bcc, subject = "Plan", body = "Hello", sourceMessage = source))
        assertTrue(actions.send(id))
        return id
    }

    private fun smtp(from: String = "user@riseup.net") =
        FakeSmtp().ehlo().expect(Regex(Regex.escape("MAIL FROM:<$from> SIZE=") + "\\d+"), "250 2.1.0 ok")

    private fun search(messageId: String) =
        Regex(Regex.escape("UID SEARCH RETURN (ALL) HEADER \"Message-ID\" \"$messageId\""))

    @Test
    fun aSendRecordsItselfAndQueuesTheFollowUps() {
        mailbox(riseup, "Sent", FolderRole.SENT)
        mailbox(riseup, "Drafts", FolderRole.DRAFTS)
        val source = insert(header(riseup, inbox, 9)).single()
        val id = queued(source = source)
        drafts.setOnServer(id, true)
        val messageId = drafts.get(id)!!.messageId
        val server = smtp()
            .expect("RCPT TO:<bob@example.org>", "250 ok")
            .expect("RCPT TO:<hidden@example.org>", "250 ok")
            .expect("DATA", "354 go on")
            .expect(".", "250 2.0.0 queued")
            .expect("QUIT", "221 bye")
        send(session(riseup, FakeImap(), server))
        server.finish()

        val wire = server.messages.single()
        assertTrue("Message-ID: $messageId\r\n" in wire)
        assertFalse("Bcc:" in wire)
        assertEquals(DraftState.SENT, drafts.get(id)!!.state)
        assertTrue(store.files.outboxMessage(id).isFile)
        assertTrue(messages.get(source)!!.answered)
        assertEquals(listOf(OpKind.APPEND, OpKind.DELETE_DRAFT, OpKind.FLAGS), ops.byAccount(riseup).map { it.kind })
        assertEquals(listOf("bob@example.org"), store.contacts.suggest("bob").map { it.email })
        assertEquals(listOf("hidden@example.org"), store.contacts.suggest("hid").map { it.email })

        val imap = FakeImap()
            .expect("APPEND \"Sent\" (\\Seen) {literal}", "OK [APPENDUID 3 20] Saved")
            .select("Drafts")
            .expect(search(messageId), "* ESEARCH UID ALL 6")
            .expect("UID STORE 6 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 6")
            .select("INBOX")
            .expect("UID STORE 9 +FLAGS.SILENT (\\Answered)")
        runOps(session(riseup, imap))
        imap.finish()
        val copy = imap.literals.single().toString(Charsets.UTF_8)
        assertTrue("Bcc: hidden@example.org\r\n" in copy)
        assertTrue("Message-ID: $messageId\r\n" in copy)
        assertNull(drafts.get(id))
        assertFalse(File(context.filesDir, "outbox/$id").exists())
        assertTrue(ops.byAccount(riseup).isEmpty())
    }

    @Test
    fun gmailKeepsNoSentCopyAndTheDraftGoesAtOnce() {
        val gmail = gmailAccount()
        val id = queued(gmail, from = "me@gmail.com", bcc = "")
        val server = smtp("me@gmail.com")
            .expect("RCPT TO:<bob@example.org>", "250 ok")
            .expect("DATA", "354 go on")
            .expect(".", "250 2.0.0 OK")
            .expect("QUIT", "221 bye")
        send(session(gmail, FakeImap(), server))
        server.finish()
        assertNull(drafts.get(id))
        assertTrue(ops.byAccount(gmail).isEmpty())
    }

    @Test
    fun aRejectedRecipientFailsTheDraftWithTheAddress() {
        val id = queued()
        val server = smtp()
            .expect("RCPT TO:<bob@example.org>", "550 5.1.1 No such user")
            .expect("RCPT TO:<hidden@example.org>", "250 ok")
            .expect("RSET", "250 ok")
            .expect("QUIT", "221 bye")
        send(session(riseup, FakeImap(), server))
        server.finish()
        val draft = drafts.get(id)!!
        assertEquals(DraftState.FAILED, draft.state)
        assertTrue("bob@example.org" in draft.error.orEmpty(), draft.error)
    }

    @Test
    fun aPermanentRefusalFails() {
        val id = queued()
        val server = smtp()
            .expect("RCPT TO:<bob@example.org>", "250 ok")
            .expect("RCPT TO:<hidden@example.org>", "250 ok")
            .expect("DATA", "354 go on")
            .expect(".", "554 5.7.1 Message refused")
            .expect("QUIT", "221 bye")
        send(session(riseup, FakeImap(), server))
        server.finish()
        val draft = drafts.get(id)!!
        assertEquals(DraftState.FAILED, draft.state)
        assertTrue("Message refused" in draft.error.orEmpty(), draft.error)
    }

    @Test
    fun aTemporaryRefusalRetriesLater() {
        val id = queued()
        val server = FakeSmtp().ehlo()
            .expect(Regex("MAIL FROM:.*"), "451 4.3.0 Try again later")
            .expect("QUIT", "221 bye")
        send(session(riseup, FakeImap(), server))
        server.finish()
        val draft = drafts.get(id)!!
        assertEquals(DraftState.QUEUED, draft.state)
        assertEquals(1, draft.attempts)
        assertEquals(clock + 5_000, draft.sendAt)
    }

    @Test
    fun theSixthTemporaryFailureGivesUp() {
        val id = queued()
        repeat(OpErrors.MAX_SEND_ATTEMPTS) { attempt ->
            val server = FakeSmtp().ehlo()
                .expect(Regex("MAIL FROM:.*"), "421 4.7.0 Too busy")
                .expect("QUIT", "221 bye")
            send(session(riseup, FakeImap(), server))
            clock = drafts.get(id)!!.sendAt.coerceAtLeast(clock)
            if (attempt < OpErrors.MAX_SEND_ATTEMPTS - 1) assertEquals(DraftState.QUEUED, drafts.get(id)!!.state)
        }
        assertEquals(DraftState.FAILED, drafts.get(id)!!.state)
    }

    @Test
    fun noReplyAfterTheDotMayHaveBeenSent() {
        val id = queued()
        val server = smtp()
            .expect("RCPT TO:<bob@example.org>", "250 ok")
            .expect("RCPT TO:<hidden@example.org>", "250 ok")
            .expect("DATA", "354 go on")
            .expect(".", null)
        send(session(riseup, FakeImap(), server))
        server.finish()
        val draft = drafts.get(id)!!
        assertEquals(DraftState.FAILED, draft.state)
        assertEquals(context.getString(R.string.data_error_may_have_been_sent), draft.error)
    }

    @Test
    fun aMessageOverTheServerLimitIsNotSent() {
        val id = queued()
        val server = FakeSmtp().ehlo(size = 100).expect("QUIT", "221 bye")
        send(session(riseup, FakeImap(), server))
        server.finish()
        assertEquals(DraftState.FAILED, drafts.get(id)!!.state)
    }

    @Test
    fun interruptedSendsFailAtStart() {
        val id = drafts.create(NewDraft(riseup, "user@riseup.net", toList = "bob@example.org"))
        drafts.queue(id, 0)
        drafts.markSending(id)
        send(session(riseup, FakeImap()))
        val draft = drafts.get(id)!!
        assertEquals(DraftState.FAILED, draft.state)
        assertEquals(context.getString(R.string.data_error_may_have_been_sent), draft.error)
    }

    @Test
    fun invalidDraftsAreNotQueued() {
        val noRecipient = drafts.create(NewDraft(riseup, "user@riseup.net"))
        assertFalse(actions.send(noRecipient))
        val invalid = drafts.create(NewDraft(riseup, "user@riseup.net", toList = "\"Bob\" <bob@>"))
        assertFalse(actions.send(invalid))
        assertEquals(DraftState.EDITING, drafts.get(invalid)!!.state)
    }

    @Test
    fun undoSendReturnsTheDraftToEditing() {
        val id = queued()
        actions.cancelSend(id)
        assertEquals(DraftState.EDITING, drafts.get(id)!!.state)
        send(session(riseup, FakeImap()))
        assertEquals(DraftState.EDITING, drafts.get(id)!!.state)
    }
}
