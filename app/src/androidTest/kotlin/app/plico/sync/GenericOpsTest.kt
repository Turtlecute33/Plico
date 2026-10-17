package app.plico.sync

import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MailView
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ops on a server that is neither Riseup nor Gmail but has MOVE, UIDPLUS and ESEARCH (DESIGN §7.4). The
 * fallbacks without these extensions are in
 * [app.plico.sync.fallback.OpsFallbackTest].
 */
class GenericOpsTest : OpFixture() {
    private val other = otherAccount()

    private fun run(ctx: OpContext) = runBlocking { Ops.run(ctx) }

    private fun later() {
        clock += MailActions.UNDO_MS + 1_000
    }

    /** A Courier/cPanel layout without NAMESPACE: every mailbox below `INBOX.`. */
    private fun courier(): Long {
        val inbox = mailbox(other, "INBOX", FolderRole.INBOX, delimiter = ".")
        folders.setSyncState(inbox, uidValidity = 7)
        mailbox(other, "INBOX.Work", delimiter = ".")
        return inbox
    }

    @Test
    fun aBareNoCreatesTheMissingMailboxOnce() {
        val inbox = mailbox(other, "INBOX", FolderRole.INBOX)
        val id = insert(header(other, inbox, 3)).single()
        actions.archive(listOf(id))
        later()
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID MOVE 3 \"Archive\"", "NO Mailbox does not exist")
            .expect("CREATE \"Archive\"")
            .expect("UID MOVE 3 \"Archive\"", "OK [COPYUID 1 3 1] Done")
        run(session(other, imap))
        imap.finish()
        val archive = assertNotNull(folders.byPath(other, FolderKind.MAILBOX, "Archive"))
        assertEquals(FolderRole.ARCHIVE, archive.role)
        assertEquals(archive.id, messages.get(id)!!.folderId)
    }

    @Test
    fun aBareNoForAKnownMailboxCreatesNothing() {
        val inbox = mailbox(other, "INBOX", FolderRole.INBOX)
        val work = mailbox(other, "Work")
        val id = insert(header(other, inbox, 3)).single()
        actions.moveTo(listOf(id), work, MailView.InFolder(inbox))
        later()
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID MOVE 3 \"Work\"", "NO Not allowed")
            .expect("UID FETCH 3 (UID FLAGS)", "* 1 FETCH (UID 3 FLAGS ())")
        run(session(other, imap))
        imap.finish()
        assertTrue(ops.byAccount(other).isEmpty())
        assertEquals(inbox, messages.get(id)!!.folderId, "the refusal restores the row")
    }

    @Test
    fun defaultNamesGoBelowTheInboxRoot() {
        val inbox = courier()
        val id = insert(header(other, inbox, 3)).single()
        actions.delete(listOf(id))
        assertEquals(OpArg.Move("Trash", FolderRole.TRASH), ops.byAccount(other).single().let { OpArg.decode(it.kind, it.arg) })
        later()
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID MOVE 3 \"INBOX.Trash\"", "NO [TRYCREATE] No such mailbox")
            .expect("CREATE \"INBOX.Trash\"")
            .expect("UID MOVE 3 \"INBOX.Trash\"", "OK [COPYUID 1 3 1] Done")
        run(session(other, imap))
        imap.finish()
        val trash = assertNotNull(folders.byPath(other, FolderKind.MAILBOX, "INBOX.Trash"))
        assertEquals(FolderRole.TRASH, trash.role)
        assertEquals("Trash", trash.name)
        assertEquals(".", trash.delimiter)
        assertEquals(trash.id, messages.get(id)!!.folderId)
    }

    @Test
    fun aNewFolderGoesBelowTheInboxRoot() {
        courier()
        val imap = FakeImap().expect("CREATE \"INBOX.Projects\"")
        session(other, imap)
        val folder = runBlocking { actions.createFolder(other, "Projects") }
        imap.finish()
        assertEquals("INBOX.Projects", folder.path)
        assertEquals("Projects", folder.name)
        assertEquals(".", folder.delimiter)
        val again = FakeImap().expect("CREATE \"INBOX.Old\"")
        session(other, again)
        assertEquals("INBOX.Old", runBlocking { actions.createFolder(other, "INBOX.Old") }.path, "already below the root")
        again.finish()
    }

    @Test
    fun riseupAndGmailKeepTopLevelNames() {
        val riseup = account()
        val inbox = mailbox(riseup, "INBOX", FolderRole.INBOX, delimiter = ".")
        mailbox(riseup, "INBOX.Work", delimiter = ".")
        val id = insert(header(riseup, inbox, 3)).single()
        actions.delete(listOf(id))
        later()
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID MOVE 3 \"Trash\"", "NO [TRYCREATE] No such mailbox")
            .expect("CREATE \"Trash\"")
            .expect("UID MOVE 3 \"Trash\"", "OK [COPYUID 1 3 1] Done")
        run(session(riseup, imap))
        imap.finish()
        assertEquals(FolderRole.TRASH, assertNotNull(folders.byPath(riseup, FolderKind.MAILBOX, "Trash")).role)
    }

    @Test
    fun aMoveWithoutCopyUidRemembersTheMessageForTheTarget() {
        val inbox = mailbox(other, "INBOX", FolderRole.INBOX)
        val spam = mailbox(other, "Junk", FolderRole.JUNK)
        val id = insert(header(other, spam, 3, messageId = "<spam3@example.org>")).single()
        actions.spam(listOf(id), isSpam = false)
        later()
        val imap = FakeImap()
            .select("Junk")
            .expect("UID MOVE 3 \"INBOX\"", "OK Done")
        val ctx = session(other, imap)
        run(ctx)
        imap.finish()
        assertNull(messages.get(id), "without COPYUID the target's sync brings the message")
        assertTrue(ctx.movedIn.take("INBOX", "<spam3@example.org>"), "its new row must not notify")
        assertEquals(0, messages.count(inbox))
    }

    @Test
    fun emptyTrashAndDeleteForeverKeepTheirCommandsWithUidPlus() {
        val inbox = mailbox(other, "INBOX", FolderRole.INBOX)
        val trash = mailbox(other, "Trash", FolderRole.TRASH)
        val inInbox = insert(header(other, inbox, 4)).single()
        val inTrash = listOf(insert(header(other, trash, 2)).single(), insert(header(other, trash, 5)).single())
        actions.deleteForever(listOf(inInbox))
        actions.emptyFolder(trash)
        val imap = FakeImap()
            .select("INBOX")
            .expect("UID STORE 4 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 4")
            .select("Trash")
            .expect("UID STORE 1:5 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 1:5")
        run(session(other, imap))
        imap.finish()
        assertNull(messages.get(inInbox))
        assertTrue(inTrash.all { messages.get(it) == null })
    }
}
