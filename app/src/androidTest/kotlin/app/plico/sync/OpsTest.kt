package app.plico.sync

import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MailView
import app.plico.data.NewAttachment
import app.plico.data.NewOp
import app.plico.mail.imap.ImapByeException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpsTest : OpFixture() {
    private val riseup = account()
    private val inbox = mailbox(riseup, "INBOX", FolderRole.INBOX).also { folders.setSyncState(it, uidValidity = 7) }

    private fun row(uid: Long = 3, folder: Long = inbox, account: Long = riseup) = insert(header(account, folder, uid)).single()

    private fun run(ctx: OpContext) = runBlocking { Ops.run(ctx) }

    /** After the undo window. */
    private fun later() {
        clock += MailActions.undoMs + 1_000
    }

    @Test
    fun archiveMovesTheRowInPlaceAfterTheUndoWindow() {
        val archive = mailbox(riseup, "Archive", FolderRole.ARCHIVE)
        val id = row()
        messages.saveBody(id, null, "text", "text", "text", listOf(NewAttachment("2", "a.pdf", "application/pdf", 9, "base64", null, false)))
        val undo = assertNotNull(actions.archive(listOf(id)))
        assertTrue(messages.get(id)!!.hidden)
        assertEquals(listOf(riseup), host.kicks)
        assertEquals(listOf(riseup), host.refreshes)
        assertEquals(clock + MailActions.undoMs, Ops.nextRunAt(store, riseup))

        val imap = FakeImap()
        val ctx = session(riseup, imap)
        run(ctx) // Not due yet.
        later()
        imap.select("INBOX").expect("UID MOVE 3 \"Archive\"", "OK [COPYUID 9 3 15] Done")
        run(ctx)
        imap.finish()
        val moved = messages.get(id)!!
        assertEquals(archive, moved.folderId)
        assertEquals(15, moved.uid)
        assertFalse(moved.hidden)
        assertEquals("text", messages.body(id)?.text)
        assertEquals(1, messages.attachments(id).size)
        assertNull(ops.get(undo.opIds.single()))
        assertNull(Ops.nextRunAt(store, riseup))
    }

    @Test
    fun undoRestoresTheRowBeforeTheOpRuns() {
        val id = row()
        val undo = assertNotNull(actions.delete(listOf(id)))
        assertEquals(setOf(id), ops.referencedMessageIds(riseup))
        assertTrue(actions.undo(undo))
        assertFalse(messages.get(id)!!.hidden)
        assertTrue(ops.byAccount(riseup).isEmpty())
    }

    @Test
    fun anUndoAfterTheWindowIsTooLate() {
        val id = row()
        val undo = assertNotNull(actions.delete(listOf(id)))
        clock += MailActions.undoMs
        // The loop may run the MOVE now: the undo must not show the row that the MOVE takes away.
        assertFalse(actions.undo(undo))
        assertTrue(messages.get(id)!!.hidden)
        assertEquals(undo.opIds, ops.byAccount(riseup).map { it.id })
    }

    @Test
    fun emptyTrashWaitsForARestoreOutOfIt() {
        val trash = mailbox(riseup, "Trash", FolderRole.TRASH)
        val kept = row(2, trash)
        val restored = row(5, trash)
        actions.moveTo(listOf(restored), inbox, MailView.InFolder(trash))
        actions.emptyFolder(trash)
        run(session(riseup, FakeImap())) // Nothing is due: the expunge waits for the restore.
        later()
        val imap = FakeImap()
            .select("Trash")
            .expect("UID MOVE 5 \"INBOX\"", "OK [COPYUID 7 5 30] Done")
            .expect("UID STORE 1:5 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 1:5")
        run(session(riseup, imap))
        imap.finish()
        assertEquals(inbox, messages.get(restored)!!.folderId)
        assertNull(messages.get(kept))
    }

    @Test
    fun emptyTrashWaitsWhileTheRestoreRetries() {
        val trash = mailbox(riseup, "Trash", FolderRole.TRASH)
        row(2, trash)
        val restored = row(5, trash)
        actions.moveTo(listOf(restored), inbox, MailView.InFolder(trash))
        actions.emptyFolder(trash)
        later()
        val imap = FakeImap()
            .select("Trash")
            .expect("UID MOVE 5 \"INBOX\"", "NO [UNAVAILABLE] Try later")
        run(session(riseup, imap))
        imap.finish()
        assertNotNull(messages.get(restored), "the expunge did not run before the restore")
        assertEquals(2, ops.byAccount(riseup).size)
        assertEquals(clock + 5_000, Ops.nextRunAt(store, riseup), "the restore's retry; the expunge waits for it")
    }

    @Test
    fun resyncDropsTheChangesThatWaitForTheFolder() {
        val id = row()
        actions.markSeen(listOf(id), true)
        folders.setGuard(inbox, true)
        actions.resyncFolder(inbox)
        assertTrue(ops.byAccount(riseup).isEmpty())
        assertNull(messages.get(id))
        assertEquals(listOf(riseup), host.syncs, "the folder syncs again at once")
    }

    @Test
    fun missingArchiveIsCreatedOnFirstUse() {
        val id = row()
        actions.archive(listOf(id))
        later()
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID MOVE 3 \"Archive\"", "NO [TRYCREATE] Mailbox does not exist")
            .expect("CREATE \"Archive\"")
            .expect("UID MOVE 3 \"Archive\"", "OK [COPYUID 1 3 1] Done")
        run(session(riseup, imap))
        imap.finish()
        val archive = assertNotNull(folders.byPath(riseup, FolderKind.MAILBOX, "Archive"))
        assertEquals(FolderRole.ARCHIVE, archive.role)
        assertEquals(archive.id, messages.get(id)!!.folderId)
    }

    @Test
    fun changedUidValidityDropsTheOp() {
        val id = row()
        actions.delete(listOf(id))
        later()
        val imap = FakeImap().select("INBOX", uidValidity = 8)
        run(session(riseup, imap))
        imap.finish()
        assertTrue(ops.byAccount(riseup).isEmpty())
        assertFalse(messages.get(id)!!.hidden)
    }

    @Test
    fun aRefusalRefetchesTheTruth() {
        val id = row()
        actions.markSeen(listOf(id), true)
        assertTrue(messages.get(id)!!.seen)
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID STORE 3 +FLAGS.SILENT (\\Seen)", "NO [CANNOT] Read-only")
            .expect("UID FETCH 3 (UID FLAGS)", "* 1 FETCH (UID 3 FLAGS (\\Flagged))")
        run(session(riseup, imap))
        imap.finish()
        val restored = messages.get(id)!!
        assertFalse(restored.seen)
        assertTrue(restored.flagged)
        assertTrue(ops.byAccount(riseup).isEmpty())
    }

    @Test
    fun aRefusedMoveOfAGoneMessageDeletesTheRow() {
        val id = row()
        actions.spam(listOf(id), true)
        later()
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID MOVE 3 \"Spam\"", "NO [OVERQUOTA] Quota exceeded")
            .expect("UID FETCH 3 (UID FLAGS)")
        run(session(riseup, imap))
        imap.finish()
        assertNull(messages.get(id))
    }

    @Test
    fun transientRefusalsBackOff() {
        val id = row()
        actions.star(listOf(id), true)
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID STORE 3 +FLAGS.SILENT (\\Flagged)", "NO [UNAVAILABLE] Try later")
        run(session(riseup, imap))
        imap.finish()
        val op = ops.byAccount(riseup).single()
        assertEquals(1, op.attempts)
        assertEquals(clock + 5_000, op.runAt)
        assertTrue(messages.get(id)!!.flagged)
    }

    @Test
    fun aLostConnectionEndsTheCycleAndKeepsTheOp() {
        actions.markSeen(listOf(row()), true)
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID STORE 3 +FLAGS.SILENT (\\Seen)", "* BYE Shutting down")
        assertFailsWith<ImapByeException> { run(session(riseup, imap)) }
        assertEquals(1, ops.byAccount(riseup).single().attempts)
    }

    @Test
    fun opsThatFailedForADayAreDropped() {
        val id = row()
        actions.markSeen(listOf(id), true)
        // A day offline is not a day of failures: the first failure backs off.
        clock += OpErrors.GIVE_UP_MS + 60_000
        val once = FakeImap()
            .select("INBOX")
            .expect("UID STORE 3 +FLAGS.SILENT (\\Seen)", "NO [UNAVAILABLE] Try later")
        run(session(riseup, once))
        once.finish()
        val op = ops.byAccount(riseup).single()
        assertEquals(1, op.attempts)

        ops.retryLater(op.id, 28, clock, null)
        val last = FakeImap()
            .select("INBOX")
            .expect("UID STORE 3 +FLAGS.SILENT (\\Seen)", "NO [UNAVAILABLE] Try later")
            .expect("UID FETCH 3 (UID FLAGS)", "* 1 FETCH (UID 3 FLAGS ())")
        run(session(riseup, last))
        last.finish()
        assertTrue(ops.byAccount(riseup).isEmpty())
        assertFalse(messages.get(id)!!.seen)
    }

    @Test
    fun guardedFoldersWait() {
        val id = row()
        actions.markSeen(listOf(id), true)
        folders.setGuard(inbox, true)
        run(session(riseup, FakeImap()))
        assertEquals(1, ops.byAccount(riseup).size)
        assertNull(Ops.nextRunAt(store, riseup))
    }

    @Test
    fun emptyTrashExpungesUpToTheNewestStoredUid() {
        val trash = mailbox(riseup, "Trash", FolderRole.TRASH)
        val ids = listOf(row(2, trash), row(5, trash))
        actions.emptyFolder(trash)
        assertTrue(ids.all { messages.get(it)!!.hidden })
        val imap = FakeImap()
            .select("Trash")
            .expect("UID STORE 1:5 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 1:5")
        run(session(riseup, imap))
        imap.finish()
        assertTrue(ids.all { messages.get(it) == null })
    }

    @Test
    fun gmailArchiveRemovesTheInboxLabel() {
        val gmail = gmailAccount()
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val inboxLabel = label(gmail, "\\Inbox", FolderRole.INBOX)
        val work = label(gmail, "Work")
        val id = insert(header(gmail, all, 5, labels = setOf(inboxLabel))).single()

        val undo = assertNotNull(actions.archive(listOf(id)))
        assertEquals(1L, count("SELECT count(*) FROM message_folder WHERE message_id = ?", id))
        actions.undo(undo)
        assertEquals(1L, count("SELECT count(*) FROM message_folder WHERE message_id = ? AND folder_id = ?", id, inboxLabel))

        actions.moveTo(listOf(id), work, MailView.Unified)
        later()
        val imap = FakeImap()
            .select("[Gmail]/All Mail")
            .expect("UID STORE 5 +X-GM-LABELS.SILENT (\"Work\")")
            .expect("UID STORE 5 -X-GM-LABELS.SILENT (\\Inbox)")
        run(session(gmail, imap))
        imap.finish()
        assertEquals(0L, count("SELECT count(*) FROM message_folder WHERE message_id = ? AND folder_id = ?", id, inboxLabel))
        assertEquals(1L, count("SELECT count(*) FROM message_folder WHERE message_id = ? AND folder_id = ?", id, work))
    }

    @Test
    fun gmailDeleteMovesToTrashAndDeletesForeverOnlyThere() {
        val gmail = gmailAccount()
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val trash = mailbox(gmail, "[Gmail]/Trash", FolderRole.TRASH)
        val id = insert(header(gmail, all, 5)).single()

        actions.deleteForever(listOf(id)) // Not in Trash: refused locally.
        assertTrue(ops.byAccount(gmail).isEmpty())

        actions.delete(listOf(id))
        later()
        val imap = FakeImap()
            .select("[Gmail]/All Mail")
            .expect("UID MOVE 5 \"[Gmail]/Trash\"", "OK [COPYUID 3 5 40] Done")
        run(session(gmail, imap))
        imap.finish()
        assertEquals(trash, messages.get(id)!!.folderId)

        actions.deleteForever(listOf(id))
        val again = FakeImap()
            .select("[Gmail]/Trash")
            .expect("UID STORE 40 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 40")
        run(session(gmail, again))
        again.finish()
        assertNull(messages.get(id))
    }

    @Test
    fun gmailNotSpamKeepsTheRowWithItsNewAllMailUid() {
        val gmail = gmailAccount()
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val spam = mailbox(gmail, "[Gmail]/Spam", FolderRole.JUNK)
        val inboxLabel = label(gmail, "\\Inbox", FolderRole.INBOX)
        val id = insert(header(gmail, spam, 5).copy(gmMsgId = 77)).single()
        messages.saveBody(id, null, "text", "text", "text", emptyList())

        actions.spam(listOf(id), isSpam = false)
        later()
        val imap = FakeImap()
            .select("[Gmail]/Spam")
            .expect("UID MOVE 5 \"INBOX\"", "OK [COPYUID 3 5 9] Done")
            .select("[Gmail]/All Mail")
            .expect("UID SEARCH RETURN (ALL) X-GM-MSGID 77", "* ESEARCH UID ALL 120")
        run(session(gmail, imap))
        imap.finish()
        val row = messages.get(id)!!
        assertEquals(all, row.folderId)
        assertEquals(120, row.uid)
        assertFalse(row.hidden)
        assertTrue(row.notified, "the next All Mail fetch finds the row: no new-mail notification")
        assertEquals("text", messages.body(id)?.text)
        assertEquals(1L, count("SELECT count(*) FROM message_folder WHERE message_id = ? AND folder_id = ?", id, inboxLabel))
    }

    @Test
    fun gmailCreatesALabelNeverAMailbox() {
        val gmail = gmailAccount()
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val trash = mailbox(gmail, "[Gmail]/Trash", FolderRole.TRASH)
        val work = label(gmail, "Work")
        val id = insert(header(gmail, trash, 40).copy(gmMsgId = 78)).single()

        actions.moveTo(listOf(id), work, MailView.InFolder(trash))
        later()
        val imap = FakeImap()
            .select("[Gmail]/Trash")
            .expect("UID MOVE 40 \"Work\"", "NO [TRYCREATE] No such label")
            .expect("CREATE \"Work\"")
            .expect("UID MOVE 40 \"Work\"", "OK [COPYUID 3 40 9] Done")
            .select("[Gmail]/All Mail")
            .expect("UID SEARCH RETURN (ALL) X-GM-MSGID 78", "* ESEARCH UID ALL 121")
        run(session(gmail, imap))
        imap.finish()
        assertNull(folders.byPath(gmail, FolderKind.MAILBOX, "Work"), "a kind-0 row would sync the label as a mailbox")
        assertEquals(work, folders.byPath(gmail, FolderKind.LABEL, "Work")!!.id)
        assertEquals(all, messages.get(id)!!.folderId)
        assertEquals(1L, count("SELECT count(*) FROM message_folder WHERE message_id = ? AND folder_id = ?", id, work))
    }

    @Test
    fun gmailRefusalInTrashRefetchesNoLabels() {
        val gmail = gmailAccount()
        val trash = mailbox(gmail, "[Gmail]/Trash", FolderRole.TRASH)
        val work = label(gmail, "Work")
        val id = insert(header(gmail, trash, 40)).single()
        actions.markSeen(listOf(id), true)
        val imap = FakeImap()
            .select("[Gmail]/Trash")
            .expect("UID STORE 40 +FLAGS.SILENT (\\Seen)", "NO [CANNOT] Read-only")
            .expect("UID FETCH 40 (UID FLAGS)", "* 1 FETCH (UID 40 FLAGS ())")
        run(session(gmail, imap))
        imap.finish()
        assertFalse(messages.get(id)!!.seen)
        assertEquals(0L, count("SELECT count(*) FROM message_folder WHERE message_id = ? AND folder_id = ?", id, work))
    }

    @Test
    fun gmailNeverMovesOutOfAllMailExceptToTrashOrSpam() {
        val gmail = gmailAccount()
        val all = mailbox(gmail, "[Gmail]/All Mail", FolderRole.ALL)
        val id = insert(header(gmail, all, 5)).single()
        val move = OpArg.Move("Work", null)
        ops.insert(NewOp(gmail, move.kind, listOf(id), OpArg.encode(move)))
        val imap = FakeImap()
            .select("[Gmail]/All Mail")
            .expect("UID FETCH 5 (UID FLAGS X-GM-LABELS)", "* 1 FETCH (UID 5 FLAGS () X-GM-LABELS (\\Inbox))")
        run(session(gmail, imap))
        imap.finish()
        assertTrue(ops.byAccount(gmail).isEmpty())
        assertNotNull(messages.get(id))
    }
}
