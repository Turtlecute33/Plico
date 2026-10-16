package app.plico.sync

import app.plico.data.FolderRole
import app.plico.data.NewDraft
import app.plico.data.OpKind
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftsTest : OpFixture() {
    private val riseup = account()

    private fun run(ctx: OpContext) = runBlocking { Ops.run(ctx) }

    private fun newDraft(accountId: Long = riseup, prefill: DraftPrefill = DraftPrefill()) =
        runBlocking { actions.newDraft(accountId, prefill) }

    private fun edit(id: Long, to: String = "Bob <bob@example.org>", bcc: String = "hidden@example.org") {
        val d = drafts.get(id)!!
        actions.saveDraft(id, d.fromEmail, to, "", bcc, "Plan", "Hello")
    }

    private fun search(messageId: String) = Regex(
        Regex.escape("UID SEARCH RETURN (ALL) HEADER \"Message-ID\" \"$messageId\""),
    )

    @Test
    fun leavingUploadsTheDraftAndDeletesOlderRevisions() {
        mailbox(riseup, "Drafts", FolderRole.DRAFTS)
        val id = newDraft()
        edit(id)
        actions.leaveDraft(id)
        assertEquals(listOf(OpKind.APPEND), ops.byAccount(riseup).map { it.kind })
        val messageId = drafts.get(id)!!.messageId

        val imap = FakeImap()
            .expect("APPEND \"Drafts\" (\\Draft \\Seen) {literal}", "OK [APPENDUID 7 12] Saved")
            .select("Drafts")
            .expect(search(messageId), "* ESEARCH UID ALL 4,12")
            .expect("UID STORE 4 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 4")
        run(session(riseup, imap))
        imap.finish()
        val stored = imap.literals.single().toString(Charsets.UTF_8)
        assertTrue("Bcc: hidden@example.org\r\n" in stored, stored)
        assertTrue("Message-ID: $messageId\r\n" in stored)
        assertTrue(drafts.get(id)!!.onServer)
        assertTrue(ops.byAccount(riseup).isEmpty())
    }

    @Test
    fun eachLeaveQueuesAnUploadUntilTheDraftIsSent() {
        val id = newDraft(prefill = DraftPrefill(to = "bob@example.org", subject = "Shared"))
        assertTrue(ops.byAccount(riseup).isEmpty(), "A new draft stays local until compose leaves it after an edit")
        actions.leaveDraft(id)
        actions.leaveDraft(id)
        assertEquals(2, ops.byAccount(riseup).size)
        assertTrue(actions.send(id))
        actions.leaveDraft(id)
        assertEquals(2, ops.byAccount(riseup).size, "A queued draft is not uploaded")
    }

    @Test
    fun anEmptyNewDraftIsDiscarded() {
        accounts.update(accounts.get(riseup)!!.copy(signature = "Me"))
        val id = newDraft()
        assertEquals("\n\n-- \nMe", drafts.get(id)!!.body)
        actions.leaveDraft(id)
        assertNull(drafts.get(id))
        assertTrue(ops.byAccount(riseup).isEmpty())
    }

    @Test
    fun aQueuedDraftIsNotUploaded() {
        val id = newDraft()
        edit(id)
        actions.leaveDraft(id)
        assertTrue(actions.send(id))
        run(session(riseup, FakeImap()))
        assertTrue(ops.byAccount(riseup).isEmpty())
    }

    @Test
    fun discardDeletesTheServerCopies() {
        mailbox(riseup, "Drafts", FolderRole.DRAFTS)
        val id = drafts.create(NewDraft(riseup, "user@riseup.net", messageId = "<old@riseup.net>"))
        drafts.setOnServer(id, true)
        actions.discardDraft(id)
        assertNull(drafts.get(id))
        assertEquals(listOf(riseup), host.kicks)
        val imap = FakeImap()
            .select("Drafts")
            .expect(search("<old@riseup.net>"), "* ESEARCH UID ALL 3")
            .expect("UID STORE 3 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 3")
        run(session(riseup, imap))
        imap.finish()
    }

    @Test
    fun gmailDeletesRevisionsThroughTrash() {
        val gmail = gmailAccount()
        mailbox(gmail, "[Gmail]/Drafts", FolderRole.DRAFTS)
        mailbox(gmail, "[Gmail]/Trash", FolderRole.TRASH)
        val id = drafts.create(NewDraft(gmail, "me@gmail.com", messageId = "<g@gmail.com>"))
        drafts.setOnServer(id, true)
        actions.discardDraft(id)
        val imap = FakeImap()
            .select("[Gmail]/Drafts")
            .expect(search("<g@gmail.com>"), "* ESEARCH UID ALL 3")
            .expect("UID MOVE 3 \"[Gmail]/Trash\"", "OK [COPYUID 5 3 40] Done")
            .select("[Gmail]/Trash")
            .expect("UID STORE 40 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 40")
        run(session(gmail, imap))
        imap.finish()
    }

    @Test
    fun aRefusedUploadIsReportedAndTheDraftStaysLocal() {
        val id = newDraft()
        edit(id)
        actions.leaveDraft(id)
        val imap = FakeImap()
            .expect("APPEND \"Drafts\" (\\Draft \\Seen) {literal}", "NO [OVERQUOTA] Quota exceeded")
        run(session(riseup, imap))
        imap.finish()
        assertTrue(ops.byAccount(riseup).isEmpty())
        assertFalse(drafts.get(id)!!.onServer)
    }

    @Test
    fun movingADraftToAnotherAccountDeletesTheOldCopy() {
        val gmail = gmailAccount()
        val id = drafts.create(NewDraft(riseup, "user@riseup.net", messageId = "<m@riseup.net>"))
        drafts.setOnServer(id, true)
        actions.saveDraft(id, "me@gmail.com", "bob@example.org", "", "", "Hi", "Text")
        val moved = drafts.get(id)!!
        assertEquals(gmail, moved.accountId)
        assertFalse(moved.onServer)
        assertEquals(listOf(OpKind.DELETE_DRAFT), ops.byAccount(riseup).map { it.kind })
    }
}
