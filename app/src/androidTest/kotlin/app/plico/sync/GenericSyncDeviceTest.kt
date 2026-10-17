package app.plico.sync

import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.push.policy.KeyValueStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sync of a server that is neither Riseup nor Gmail and has no CONDSTORE, but has ESEARCH and LIST-STATUS
 * (DESIGN §7.2). Servers without ESEARCH are in
 * [app.plico.sync.fallback.MailboxSyncFallbackDeviceTest].
 */
class GenericSyncDeviceTest : OpFixture() {
    private val accountId = otherAccount()
    private val server = SyncFakeServer(caps = "IMAP4rev1 IDLE MOVE UIDPLUS ESEARCH LIST-STATUS")
    private val inbox = server.box("INBOX")
    private val movedIn = MovedIn()

    private val sync: SyncAccount by lazy {
        SyncAccount(
            SyncEnv(
                account = accounts.get(accountId)!!,
                client = server.client(),
                accounts = accounts,
                folders = folders,
                messages = messages,
                ops = ops,
                resources = context.resources,
                notes = MemoryNotes(),
                protectedIds = { ops.referencedMessageIds(accountId) },
                movedIn = movedIn,
            ),
        )
    }

    private fun folder(path: String): Folder = folders.byPath(accountId, FolderKind.MAILBOX, path)!!
    private fun row(folder: Folder, uid: Long) = messages.byUid(folder.id, uid)

    private fun start() {
        sync.refreshFolders()
        sync.fastPath(SyncReport())
        sync.fullSweep(SyncReport(), fullFlags = true)
    }

    private fun push(): SyncReport = SyncReport().also {
        sync.fastPath(it)
        sync.idleMailboxFlags(fullFlags = false, it)
    }

    @Test
    fun aCourierFolderListGetsItsRolesFromTheNames() {
        server.box("INBOX.Sent")
        server.box("INBOX.Trash")
        server.box("INBOX.Spam")
        server.box("INBOX.Archive")
        server.box("INBOX.Work.Drafts")
        server.box("INBOX.All", "\\All")
        sync.refreshFolders()
        assertEquals(FolderRole.INBOX, folder("INBOX").role)
        assertEquals(FolderRole.SENT, folder("INBOX.Sent").role)
        assertEquals(FolderRole.TRASH, folder("INBOX.Trash").role)
        assertEquals(FolderRole.JUNK, folder("INBOX.Spam").role)
        assertEquals(FolderRole.ARCHIVE, folder("INBOX.Archive").role)
        assertEquals(null, folder("INBOX.Work.Drafts").role, "not a direct child of the root")
        assertTrue(folder("INBOX.All").hidden, "a virtual folder would show every message twice")
        assertEquals("Sent", folder("INBOX.Sent").name)
        assertEquals(folder("INBOX.Archive"), FolderRules.archiveOf(accounts.get(accountId)!!, folders.byAccount(accountId)))

        server.take()
        sync.fullSweep(SyncReport())
        assertTrue(server.take().none { "INBOX.All" in it }, "a hidden mailbox does not sync")
    }

    @Test
    fun withoutCondstoreAPollSkipsUnchangedMailboxesUntilFlagsAreDue() {
        inbox.addAll(3)
        val work = server.box("Work")
        work.addAll(3)
        start()
        assertEquals(3, messages.count(folder("Work").id))

        server.take()
        sync.fullSweep(SyncReport(), fullFlags = false)
        val poll = server.take()
        assertEquals(listOf("STATUS \"Work\" (MESSAGES UIDNEXT UIDVALIDITY)"), poll, "no CONDSTORE: STATUS only")

        work.flag(1, "\\Seen")
        sync.fullSweep(SyncReport(), fullFlags = false)
        assertFalse(row(folder("Work"), 1)!!.seen, "a flag change waits for the next flag check")
        server.take()
        sync.fullSweep(SyncReport(), fullFlags = true)
        val due = server.take()
        assertTrue("SELECT \"Work\"" in due)
        assertTrue("UID FETCH 1:3 (UID FLAGS)" in due, "the full local range without CHANGEDSINCE")
        assertTrue(row(folder("Work"), 1)!!.seen)

        work.add(subject = "New in Work")
        server.take()
        sync.fullSweep(SyncReport(), fullFlags = false)
        val changed = server.take()
        assertTrue(changed.any { it.startsWith("UID FETCH 4:* (UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[") }, "new mail")
        assertEquals(4, messages.count(folder("Work").id))
    }

    @Test
    fun pushFlagsWithoutCondstoreFetchTheNewestRows() {
        inbox.addAll(3)
        start()
        inbox.flag(2, "\\Flagged")
        server.take()
        push()
        val sent = server.take()
        assertTrue("UID FETCH 1:3 (UID FLAGS)" in sent)
        assertTrue(sent.none { "CHANGEDSINCE" in it || "MODSEQ" in it || "ENABLE" in it })
        assertTrue(row(folder("INBOX"), 2)!!.flagged)
    }

    @Test
    fun aMessageMovedInWithoutCopyUidDoesNotNotify() {
        inbox.addAll(2)
        val junk = server.box("Junk", "\\Junk")
        val spam = junk.add(subject = "Not spam after all")
        start()
        val junkRow = row(folder("Junk"), spam.uid)!!
        actions.spam(listOf(junkRow.id), isSpam = false)
        clock += MailActions.UNDO_MS + 1_000

        val imap = FakeImap()
            .select("Junk", uidValidity = 1)
            .expect("UID MOVE ${spam.uid} \"INBOX\"", "OK Done") // no COPYUID
        runBlocking { Ops.run(session(accountId, imap, movedIn = movedIn)) }
        imap.finish()
        // The server moved it: a new UID in INBOX, and real new mail after it.
        junk.expunge(listOf(spam.uid))
        inbox.add(subject = spam.header.substringAfter("Subject: ").substringBefore("\r\n"), gmMsgId = spam.gmMsgId)
        inbox.add(subject = "Fresh")

        push()
        assertEquals(listOf("Fresh"), sync.claimNew().map { messages.get(it)!!.subject }, "\"Not spam\" is no new mail")
        assertEquals(4, messages.count(folder("INBOX").id))
    }

    @Test
    fun aMovedMessageIsSyncedBeforeTheNextConnection() {
        inbox.addAll(2)
        val junk = server.box("Junk", "\\Junk")
        val spam = junk.add(subject = "Not spam after all")
        start()
        actions.spam(listOf(row(folder("Junk"), spam.uid)!!.id), isSpam = false)
        clock += MailActions.UNDO_MS + 1_000
        val imap = FakeImap()
            .select("Junk", uidValidity = 1)
            .expect("UID MOVE ${spam.uid} \"INBOX\"", "OK Done") // no COPYUID
        runBlocking { Ops.run(session(accountId, imap, movedIn = movedIn)) }
        imap.finish()
        junk.expunge(listOf(spam.uid))
        inbox.add(subject = spam.header.substringAfter("Subject: ").substringBefore("\r\n"), gmMsgId = spam.gmMsgId)

        // POLL mode: the cycle syncs the target after the ops, before the next connection forgets the move.
        assertTrue(sync.movedTargets(SyncReport()))
        assertTrue(movedIn.paths().isEmpty())
        assertEquals(3, messages.count(folder("INBOX").id), "the moved message shows at once")
        movedIn.clear() // the next connection
        inbox.add(subject = "Fresh")
        push()
        assertEquals(listOf("Fresh"), sync.claimNew().map { messages.get(it)!!.subject }, "\"Not spam\" is no new mail")
        assertFalse(sync.movedTargets(SyncReport()), "nothing to do")
    }

    private class MemoryNotes : KeyValueStore {
        private val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
