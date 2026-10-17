package app.plico.sync.fallback

import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.mail.imap.UidSet
import app.plico.sync.FakeImap
import app.plico.sync.MailActions
import app.plico.sync.OpContext
import app.plico.sync.OpFixture
import app.plico.sync.Ops
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ops on servers without MOVE, UIDPLUS or with NAMESPACE (DESIGN §7.4), with the `:mail` fallbacks
 * (COPY + STORE + guarded EXPUNGE, `namespace()`; DESIGN §4.4).
 */
class OpsFallbackTest : OpFixture() {
    private val other = otherAccount()
    private val inbox = mailbox(other, "INBOX", FolderRole.INBOX).also { folders.setSyncState(it, uidValidity = 7) }

    private fun row(uid: Long, folder: Long = inbox) = insert(header(other, folder, uid)).single()

    private fun run(ctx: OpContext) = runBlocking { Ops.run(ctx) }

    private fun later() {
        clock += MailActions.UNDO_MS + 1_000
    }

    private val searchDeleted = Regex("UID SEARCH (CHARSET UTF-8 )?DELETED")

    @Test
    fun moveWithUidPlusIsCopyStoreAndUidExpunge() {
        val archive = mailbox(other, "Archive", FolderRole.ARCHIVE)
        val id = row(3)
        actions.archive(listOf(id))
        later()
        val imap = FakeImap("IMAP4rev1 IDLE UIDPLUS")
            .select("INBOX")
            .expect("UID COPY 3 \"Archive\"", "OK [COPYUID 9 3 15] Done")
            .expect("UID STORE 3 +FLAGS.SILENT (\\Deleted)")
            .expect("UID EXPUNGE 3")
        run(session(other, imap))
        imap.finish()
        assertEquals(archive, messages.get(id)!!.folderId)
        assertEquals(15, messages.get(id)!!.uid)
    }

    @Test
    fun aForeignDeletedMessageSurvivesAFallbackMove() {
        mailbox(other, "Archive", FolderRole.ARCHIVE)
        val id = row(3)
        actions.archive(listOf(id))
        later()
        val imap = FakeImap("IMAP4rev1")
            .select("INBOX")
            .expect("UID COPY 3 \"Archive\"", "OK Done")
            .expect("UID STORE 3 +FLAGS.SILENT (\\Deleted)")
            // Message 8 is struck through by another client (Outlook IMAP mode): a plain EXPUNGE would destroy it.
            .expect(searchDeleted, "* SEARCH 3 8")
        val ctx = session(other, imap)
        run(ctx)
        imap.finish() // no EXPUNGE
        assertNull(messages.get(id), "without COPYUID the Archive sync brings the copy")
        assertTrue(ctx.movedIn.take("Archive", "<m3@example.org>"))
        assertTrue(ops.byAccount(other).isEmpty())
    }

    @Test
    fun ownLeftoversOfASkippedExpungeDoNotBlockLaterOnes() {
        mailbox(other, "Archive", FolderRole.ARCHIVE)
        val first = row(3)
        val second = row(5)
        actions.archive(listOf(first))
        later()
        val skipped = FakeImap("IMAP4rev1")
            .select("INBOX")
            .expect("UID COPY 3 \"Archive\"", "OK Done")
            .expect("UID STORE 3 +FLAGS.SILENT (\\Deleted)")
            .expect(searchDeleted, "* SEARCH 3 8")
        run(session(other, skipped))
        skipped.finish() // no EXPUNGE: 8 is another client's
        assertEquals(UidSet.of(3), folders.ownDeleted(inbox), "Plico's own message stays \\Deleted on the server")

        // The other client undeleted 8. Plico's own 3 must not count as another client's mail.
        actions.archive(listOf(second))
        later()
        val expunged = FakeImap("IMAP4rev1")
            .select("INBOX")
            .expect("UID COPY 5 \"Archive\"", "OK Done")
            .expect("UID STORE 5 +FLAGS.SILENT (\\Deleted)")
            .expect(searchDeleted, "* SEARCH 3 5")
            .expect("EXPUNGE")
        run(session(other, expunged))
        expunged.finish()
        assertEquals(UidSet.EMPTY, folders.ownDeleted(inbox), "the plain EXPUNGE removed them all")

        // The record belongs to one UIDVALIDITY: a resync clears it.
        folders.setOwnDeleted(inbox, UidSet.of(9))
        folders.resync(inbox)
        assertEquals(UidSet.EMPTY, folders.ownDeleted(inbox))
    }

    @Test
    fun onlyOwnDeletedMessagesAreExpunged() {
        mailbox(other, "Archive", FolderRole.ARCHIVE)
        actions.archive(listOf(row(3)))
        later()
        val imap = FakeImap("IMAP4rev1")
            .select("INBOX")
            .expect("UID COPY 3 \"Archive\"", "OK Done")
            .expect("UID STORE 3 +FLAGS.SILENT (\\Deleted)")
            .expect(searchDeleted, "* SEARCH 3")
            .expect("EXPUNGE")
        run(session(other, imap))
        imap.finish()
    }

    @Test
    fun deleteForeverOutsideTrashIsGuarded() {
        val id = row(4)
        actions.deleteForever(listOf(id))
        val imap = FakeImap("IMAP4rev1")
            .select("INBOX")
            .expect("UID STORE 4 +FLAGS.SILENT (\\Deleted)")
            .expect(searchDeleted, "* SEARCH 4 9")
        run(session(other, imap))
        imap.finish()
        assertNull(messages.get(id))
    }

    @Test
    fun emptyTrashIsAPlainExpunge() {
        val trash = mailbox(other, "Trash", FolderRole.TRASH)
        val ids = listOf(row(2, trash), row(5, trash))
        actions.emptyFolder(trash)
        val imap = FakeImap("IMAP4rev1")
            .select("Trash")
            .expect("UID STORE 1:5 +FLAGS.SILENT (\\Deleted)")
            .expect("EXPUNGE")
        run(session(other, imap))
        imap.finish()
        assertTrue(ids.all { messages.get(it) == null })
    }

    @Test
    fun defaultNamesGoBelowTheNamespacePrefix() {
        mailbox(other, "Work") // top level: the INBOX rule alone would give no prefix
        val id = row(3)
        actions.delete(listOf(id))
        later()
        val imap = FakeImap("IMAP4rev1 IDLE MOVE UIDPLUS ESEARCH NAMESPACE")
            .expect("NAMESPACE", "* NAMESPACE ((\"INBOX.\" \".\")) NIL NIL")
            .select("INBOX")
            .expect("UID MOVE 3 \"INBOX.Trash\"", "NO [TRYCREATE] No such mailbox")
            .expect("CREATE \"INBOX.Trash\"")
            .expect("UID MOVE 3 \"INBOX.Trash\"", "OK [COPYUID 1 3 1] Done")
        run(session(other, imap))
        imap.finish()
        val trash = assertNotNull(folders.byPath(other, FolderKind.MAILBOX, "INBOX.Trash"))
        assertEquals(".", trash.delimiter, "the namespace delimiter")
        assertEquals("Trash", trash.name)
        assertEquals(FolderRole.TRASH, trash.role)
    }

    @Test
    fun aNewFolderGoesBelowTheNamespacePrefix() {
        val imap = FakeImap("IMAP4rev1 IDLE MOVE UIDPLUS ESEARCH NAMESPACE")
            .expect("NAMESPACE", "* NAMESPACE ((\"INBOX.\" \".\")) NIL NIL")
            .expect("CREATE \"INBOX.Projects\"")
        session(other, imap)
        val folder = runBlocking { actions.createFolder(other, "Projects") }
        imap.finish()
        assertEquals("INBOX.Projects", folder.path)
        assertEquals(".", folder.delimiter)
    }
}
