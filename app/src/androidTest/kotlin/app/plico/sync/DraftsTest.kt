package app.plico.sync

import app.plico.R
import app.plico.data.FolderRole
import app.plico.data.NewDraft
import app.plico.data.OpKind
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun withoutAppendUidTheUidNextBeforeTheAppendIsKept() {
        val other = otherAccount()
        val box = mailbox(other, "Drafts", FolderRole.DRAFTS)
        val id = newDraft(other)
        edit(id)
        actions.leaveDraft(id)
        val messageId = drafts.get(id)!!.messageId
        // A revision that the server search does not find yet (its index is late), stored by the sync.
        val local = insert(header(other, box, 6, messageId = messageId).copy(draft = true)).single()

        val imap = FakeImap()
            .select("Drafts") // UIDNEXT 1000: the new revision gets 1000 or more.
            .expect("APPEND \"Drafts\" (\\Draft \\Seen) {literal}", "OK Saved")
            .expect(search(messageId), "* ESEARCH UID ALL 4,1000")
            .expect("UID STORE 4,6 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 4,6")
        run(session(other, imap))
        imap.finish()
        assertTrue(drafts.get(id)!!.onServer)
        assertTrue(ops.byAccount(other).isEmpty())
        assertNull(messages.get(local), "the old revision's row goes with it")
    }

    @Test
    fun riseupDoesNotSelectBeforeTheUpload() {
        mailbox(riseup, "Drafts", FolderRole.DRAFTS)
        val id = newDraft()
        edit(id)
        actions.leaveDraft(id)
        val imap = FakeImap().expect("APPEND \"Drafts\" (\\Draft \\Seen) {literal}", "OK Saved")
        run(session(riseup, imap))
        imap.finish()
        assertTrue(drafts.get(id)!!.onServer)
        assertTrue(ops.byAccount(riseup).isEmpty(), "without APPENDUID Riseup deletes no revision (as before)")
    }

    @Test
    fun oneUploadIsQueuedUntilTheDraftIsSent() {
        val id = newDraft(prefill = DraftPrefill(to = "bob@example.org", subject = "Shared"))
        assertTrue(ops.byAccount(riseup).isEmpty(), "A new draft stays local until compose leaves it after an edit")
        actions.leaveDraft(id)
        actions.leaveDraft(id)
        assertEquals(1, ops.byAccount(riseup).size, "the upload reads the draft when it runs: one is enough")
        assertTrue(actions.send(id))
        actions.leaveDraft(id)
        assertEquals(1, ops.byAccount(riseup).size, "A queued draft is not uploaded")
    }

    @Test
    fun twoUploadsInARowKeepTheNewestRevision() {
        mailbox(riseup, "Drafts", FolderRole.DRAFTS)
        val messageId = drafts.get(newDraft())!!.messageId
        // The deletions of two uploads (APPENDUID 12, then 13) run after both uploads.
        ops.insert(OpArg.DeleteDraft(messageId, keep = 12).toNewOp(riseup, 0))
        ops.insert(OpArg.DeleteDraft(messageId, keep = 13).toNewOp(riseup, 0))
        val imap = FakeImap()
            .select("Drafts")
            .expect(search(messageId), "* ESEARCH UID ALL 4,12,13")
            .expect("UID STORE 4 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 4")
            .expect(search(messageId), "* ESEARCH UID ALL 12,13")
            .expect("UID STORE 12 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 12")
        run(session(riseup, imap))
        imap.finish()
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
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        // Gmail does not sync Drafts: a revision is an All Mail row with \Draft. The Sent copy has none.
        val revision = insert(header(gmail, all, 8, messageId = "<g@gmail.com>").copy(draft = true)).single()
        val sentCopy = insert(header(gmail, all, 9, messageId = "<g@gmail.com>")).single()
        val id = drafts.create(NewDraft(gmail, "me@gmail.com", messageId = "<g@gmail.com>"))
        drafts.setOnServer(id, true)
        actions.discardDraft(id)
        assertTrue(messages.get(revision)!!.hidden, "the discarded draft does not show until the server copy is gone")
        assertFalse(messages.get(sentCopy)!!.hidden)
        val imap = FakeImap()
            .select("[Gmail]/Drafts")
            .expect(search("<g@gmail.com>"), "* ESEARCH UID ALL 3")
            .expect("UID MOVE 3 \"[Gmail]/Trash\"", "OK [COPYUID 5 3 40] Done")
            .select("[Gmail]/Trash")
            .expect("UID STORE 40 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 40")
        run(session(gmail, imap))
        imap.finish()
        assertNull(messages.get(revision))
        assertNotNull(messages.get(sentCopy))
    }

    @Test
    fun gmailWithoutADraftsMailboxKeepsTheDraftLocal() {
        val gmail = gmailAccount()
        val id = newDraft(gmail)
        edit(id)
        actions.leaveDraft(id)
        run(session(gmail, FakeImap())) // No APPEND to a made-up "Drafts" (it would become a label).
        assertTrue(ops.byAccount(gmail).isEmpty())
        assertFalse(drafts.get(id)!!.onServer)
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
        assertTrue(moved.messageId.endsWith("@gmail.com>"), "a Riseup Message-ID would link the two addresses")
        val delete = ops.byAccount(riseup).single()
        assertEquals(OpArg.DeleteDraft("<m@riseup.net>"), OpArg.decode(delete.kind, delete.arg))
    }

    @Test
    fun anIdentityOfAnotherDomainGetsItsOwnMessageId() {
        accounts.addIdentity(riseup, "me@example.org", "Me")
        accounts.addIdentity(riseup, "other@riseup.net", "Other")
        val id = drafts.create(NewDraft(riseup, "user@riseup.net", messageId = "<m@riseup.net>"))
        actions.saveDraft(id, "other@riseup.net", "bob@example.org", "", "", "Hi", "Text")
        assertEquals("<m@riseup.net>", drafts.get(id)!!.messageId, "the same domain keeps the Message-ID")
        actions.saveDraft(id, "me@example.org", "bob@example.org", "", "", "Hi", "Text")
        val draft = drafts.get(id)!!
        assertEquals(riseup, draft.accountId)
        assertTrue(draft.messageId.endsWith("@example.org>"), draft.messageId)
        assertTrue(ops.byAccount(riseup).isEmpty(), "never uploaded: nothing to delete")
        // The message that goes out has the new Message-ID.
        val built = DraftMime.builder(store, accounts.get(riseup)!!, draft, strict = true, date = 0).build(includeBcc = false)
        assertTrue("Message-ID: ${draft.messageId}\r\n" in built.toString(Charsets.UTF_8))
    }

    @Test
    fun aPhotoThatCannotBeCleanedIsNotAttached() {
        val id = newDraft()
        val raw = store.files.newOutboxFile(id, "camera.jpg")
        raw.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))
        // Sent as it is, it would keep its location and camera data (privacy PS17).
        val error = assertFailsWith<ActionError> { DraftAttachments.cleaned(store, id, raw, "image-1.jpg", ImageRules.Output.JPEG) }
        assertEquals(context.getString(R.string.ops_error_image_not_cleaned), error.message)
        assertEquals(listOf("camera.jpg"), store.files.outboxDir(id).list()?.toList())
    }
}
