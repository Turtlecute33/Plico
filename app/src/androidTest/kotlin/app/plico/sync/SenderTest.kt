package app.plico.sync

import app.plico.R
import app.plico.account.ProviderTable
import app.plico.data.DraftState
import app.plico.data.FolderRole
import app.plico.data.NewDraft
import app.plico.data.OpKind
import app.plico.data.Provider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
        assertEquals(DraftState.UNCERTAIN, draft.state)
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
        val ctx = session(riseup, FakeImap())
        val failure = runBlocking {
            val first = async(start = CoroutineStart.UNDISPATCHED) { Ops.failures.first { it.draftId == id } }
            Sender.run(ctx)
            first.await()
        }
        val draft = drafts.get(id)!!
        assertEquals(DraftState.UNCERTAIN, draft.state)
        assertEquals(context.getString(R.string.data_error_may_have_been_sent), draft.error)
        assertEquals(riseup, failure.accountId, "the failure names the draft, for the \"Message not sent\" alert")
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
    fun aFromThatIsNoAddressOfTheAccountIsNotSent() {
        val id = queued(from = "someone@example.org")
        val ctx = session(riseup, FakeImap()) // no SMTP connection
        val failure = runBlocking {
            val first = async(start = CoroutineStart.UNDISPATCHED) { Ops.failures.first { it.draftId == id } }
            Sender.run(ctx)
            first.await()
        }
        val draft = drafts.get(id)!!
        assertEquals(DraftState.FAILED, draft.state)
        assertEquals(context.getString(R.string.ops_error_unknown_sender, "someone@example.org"), draft.error)
        assertEquals(draft.error, failure.text, "the failure names the draft, for a notification")
    }

    @Test
    fun anIdentityOfTheAccountSends() {
        accounts.addIdentity(riseup, "Alias@riseup.net", "Alias")
        val id = queued(from = "alias@riseup.net", bcc = "")
        val server = smtp("alias@riseup.net")
            .expect("RCPT TO:<bob@example.org>", "250 ok")
            .expect("DATA", "354 go on")
            .expect(".", "250 2.0.0 queued")
            .expect("QUIT", "221 bye")
        send(session(riseup, FakeImap(), server))
        server.finish()
        assertEquals(DraftState.SENT, drafts.get(id)!!.state)
    }

    @Test
    fun aSendWhoseDraftIsGoneLeavesNoFiles() {
        val id = queued()
        val ctx = session(riseup, FakeImap(), smtp().expect("RCPT TO:<bob@example.org>", "250 ok")
            .expect("RCPT TO:<hidden@example.org>", "250 ok")
            .expect("DATA", "354 go on")
            .expect(".", "250 2.0.0 queued")
            .expect("QUIT", "221 bye"))
        // The account is removed while the message goes out.
        ctx.beforeSmtp = { drafts.delete(id) }
        send(ctx)
        assertFalse(File(context.filesDir, "outbox/$id").exists(), "no Sent copy with the Bcc stays behind")
        assertTrue(ops.byAccount(riseup).isEmpty())
    }

    @Test
    fun sentDraftsThatADeadProcessLeftAreCleanedAtStart() {
        val done = drafts.create(NewDraft(riseup, "user@riseup.net", toList = "bob@example.org", messageId = "<done@riseup.net>"))
        val copying = drafts.create(NewDraft(riseup, "user@riseup.net", toList = "bob@example.org"))
        for (id in listOf(done, copying)) {
            drafts.queue(id, 0)
            drafts.markSending(id)
            drafts.markSent(id)
            store.files.newOutboxFile(id, "message.eml").writeText("x")
        }
        ops.insert(OpArg.Append(copying, FolderRole.SENT, "sent.eml").toNewOp(riseup, Long.MAX_VALUE))
        send(session(riseup, FakeImap()))
        assertNull(drafts.get(done), "its Message-ID would hide the sent message (protocol PS7)")
        assertFalse(File(context.filesDir, "outbox/$done").exists())
        assertEquals(DraftState.SENT, drafts.get(copying)!!.state, "the Sent copy still needs it")
    }

    // ---- Sent copy on other servers (DESIGN §7.5)

    /** A sent message from [accountId] (Bob only, no Bcc) through a fake SMTP server; returns the draft id. */
    private fun sendOne(accountId: Long, from: String): Long {
        val id = queued(accountId, from = from, bcc = "")
        val server = smtp(from)
            .expect("RCPT TO:<bob@example.org>", "250 ok")
            .expect("DATA", "354 go on")
            .expect(".", "250 2.0.0 queued")
            .expect("QUIT", "221 bye")
        send(session(accountId, FakeImap(), server))
        server.finish()
        return id
    }

    private val sentCheck = "UID FETCH 950:* (UID BODY.PEEK[HEADER.FIELDS (MESSAGE-ID)])"

    @Test
    fun otherServersAppendTheSentCopyAfterAMinuteWhenTheServerKeptNone() {
        val other = otherAccount()
        mailbox(other, "Sent", FolderRole.SENT)
        val id = sendOne(other, "me@example.org")
        val messageId = drafts.get(id)!!.messageId
        val append = ops.byAccount(other).single()
        assertEquals(OpKind.APPEND, append.kind)
        assertEquals(clock + Sender.SENT_COPY_DELAY_MS, append.runAt, "the server may save its own copy first")

        runOps(session(other, FakeImap())) // Not due yet: no command.
        clock += Sender.SENT_COPY_DELAY_MS
        val imap = FakeImap()
            .select("Sent")
            .expect(sentCheck, "* 1 FETCH (UID 999 BODY[HEADER.FIELDS (MESSAGE-ID)] \"Message-ID: <other@example.org>\")")
            .expect("APPEND \"Sent\" (\\Seen) {literal}", "OK [APPENDUID 7 1000] Saved")
        runOps(session(other, imap))
        imap.finish()
        assertTrue("Message-ID: $messageId\r\n" in imap.literals.single().toString(Charsets.UTF_8))
        assertTrue(ops.byAccount(other).isEmpty())
        assertNull(drafts.get(id))
    }

    @Test
    fun aCopyThatTheServerSavedIsNotAppendedAgain() {
        val other = otherAccount()
        mailbox(other, "Sent", FolderRole.SENT)
        val id = sendOne(other, "me@example.org")
        val messageId = drafts.get(id)!!.messageId
        clock += Sender.SENT_COPY_DELAY_MS
        val imap = FakeImap()
            .select("Sent")
            .expect(
                sentCheck,
                "* 1 FETCH (UID 998 BODY[HEADER.FIELDS (MESSAGE-ID)] \"Message-ID: <other@example.org>\")",
                "* 2 FETCH (UID 999 BODY[HEADER.FIELDS (MESSAGE-ID)] \"Message-ID: $messageId\")",
            )
        runOps(session(other, imap))
        imap.finish()
        assertTrue(imap.literals.isEmpty(), "no second copy")
        assertTrue(ops.byAccount(other).isEmpty())
        assertNull(drafts.get(id))
        assertFalse(File(context.filesDir, "outbox/$id").exists(), "the copy with the Bcc recipients is gone")
    }

    @Test
    fun aSentMailboxThatIsGoneIsCreatedForTheCopy() {
        val other = otherAccount()
        mailbox(other, "Sent", FolderRole.SENT)
        val id = sendOne(other, "me@example.org")
        clock += Sender.SENT_COPY_DELAY_MS
        val imap = FakeImap()
            .expect("SELECT \"Sent\"", "NO [NONEXISTENT] No such mailbox")
            .expect("APPEND \"Sent\" (\\Seen) {literal}", "NO [TRYCREATE] No such mailbox")
            .expect("CREATE \"Sent\"")
            .expect("APPEND \"Sent\" (\\Seen) {literal}", "OK Saved")
        runOps(session(other, imap))
        imap.finish()
        assertTrue(ops.byAccount(other).isEmpty())
        assertNull(drafts.get(id))
    }

    @Test
    fun yahooSavesItsOwnCopy() {
        val yahoo = account("me@yahoo.com", Provider.OTHER, ProviderTable.byDomain("yahoo.com")!!.servers!!)
        mailbox(yahoo, "Sent", FolderRole.SENT)
        val id = queued(yahoo, from = "me@yahoo.com", bcc = "hidden@example.org")
        val server = smtp("me@yahoo.com")
            .expect("RCPT TO:<bob@example.org>", "250 ok")
            .expect("RCPT TO:<hidden@example.org>", "250 ok")
            .expect("DATA", "354 go on")
            .expect(".", "250 2.0.0 queued")
            .expect("QUIT", "221 bye")
        send(session(yahoo, FakeImap(), server))
        server.finish()
        assertTrue(ops.byAccount(yahoo).isEmpty(), "no APPEND: the server keeps the copy")
        assertNull(drafts.get(id))
        assertFalse(File(context.filesDir, "outbox/$id").exists(), "no Bcc copy was written")
    }

    @Test
    fun microsoftKeepsNoClientCopy() {
        val outlook = account("me@outlook.com", Provider.MICROSOFT)
        mailbox(outlook, "Sent Items", FolderRole.SENT)
        val id = sendOne(outlook, "me@outlook.com")
        assertTrue(ops.byAccount(outlook).isEmpty())
        assertNull(drafts.get(id))
    }

    @Test
    fun riseupAppendsAtOnceWithoutACheck() {
        mailbox(riseup, "Sent", FolderRole.SENT)
        sendOne(riseup, "user@riseup.net")
        assertEquals(clock, ops.byAccount(riseup).single().runAt)
        val imap = FakeImap().expect("APPEND \"Sent\" (\\Seen) {literal}", "OK [APPENDUID 3 20] Saved")
        runOps(session(riseup, imap))
        imap.finish()
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
