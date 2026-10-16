package app.plico.sync

import app.plico.data.DbFixture
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.NewOp
import app.plico.data.OpKind
import app.plico.mail.imap.ImapNoException
import app.plico.push.policy.KeyValueStore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Riseup-style mailbox sync (DESIGN.md §7.2) against [SyncFakeServer] with a real database. */
class MailboxSyncDeviceTest : DbFixture() {
    private val accountId = account()
    private val server = SyncFakeServer()
    private val inbox = server.box("INBOX")
    private var serverNewMail = 0

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
                onNewMail = { serverNewMail++ },
            ),
        )
    }

    private fun folder(role: FolderRole): Folder = folders.byRole(accountId, role, FolderKind.MAILBOX)!!
    private fun folder(path: String): Folder = folders.byPath(accountId, FolderKind.MAILBOX, path)!!
    private fun row(folder: Folder, uid: Long) = messages.byUid(folder.id, uid)

    /** The first part of a cycle: folder list, then the fast path of INBOX. */
    private fun start(): Folder {
        sync.refreshFolders()
        sync.fastPath(SyncReport())
        return folder(FolderRole.INBOX)
    }

    /** A push event: steps 4, 5 and 6 of INBOX. */
    private fun push(): SyncReport = SyncReport().also {
        sync.fastPath(it)
        sync.idleMailboxFlags(fullFlags = false, it)
    }

    @Test
    fun firstSyncFetchesTheNewest200BySequenceAndNeverNotifies() {
        inbox.addAll(250)
        val f = start()
        assertEquals(200, messages.count(f.id))
        assertEquals(51, f.backfillUid, "the lowest UID fetched")
        assertEquals(251, f.uidNext)
        assertEquals(1, f.uidValidity)
        assertEquals(inbox.modSeq, f.modSeq)
        assertTrue(server.commands.any { it.startsWith("FETCH 51:* (UID FLAGS INTERNALDATE RFC822.SIZE MODSEQ BODY.PEEK[HEADER.FIELDS (DATE FROM") })
        assertEquals("Old 249", row(f, 250)!!.subject)
        assertEquals("ann@example.org", row(f, 250)!!.fromEmail)
        assertTrue(sync.claimNew().isEmpty(), "a first sync has notified = 1")

        assertTrue(sync.backfillStep(SyncReport()))
        assertEquals(250, messages.count(f.id))
        assertEquals(1, folder(FolderRole.INBOX).backfillUid, "complete")
        assertFalse(sync.backfillStep(SyncReport()))
        assertTrue(sync.claimNew().isEmpty(), "backfill never notifies")
    }

    @Test
    fun backfillJumpsOverSparseUids() {
        inbox.addAll(10)
        inbox.skipUids(1_000)
        inbox.addAll(250)
        val f = start()
        assertEquals(1_061, f.backfillUid)

        assertTrue(sync.backfillStep(SyncReport()))
        assertEquals(561, folder(FolderRole.INBOX).backfillUid)
        assertEquals(250, messages.count(f.id))

        server.take()
        assertTrue(sync.backfillStep(SyncReport()))
        assertTrue("UID SEARCH RETURN (MAX) UID 1:60" in server.take(), "the empty range continues at the next older UID")
        assertEquals(1, folder(FolderRole.INBOX).backfillUid)
        assertEquals(260, messages.count(f.id))
    }

    @Test
    fun newMailNotifiesOnlyUnseenRecentRows() {
        inbox.addAll(5)
        start()
        inbox.add(subject = "New one")
        inbox.add(subject = "Read elsewhere", flags = setOf("\\Seen"))
        inbox.add(subject = "Late", date = System.currentTimeMillis() - 8 * DAY)
        val report = push()
        val f = folder(FolderRole.INBOX)
        assertEquals(setOf(f.id), report.newRows)
        assertEquals(listOf("New one"), sync.claimNew().map { messages.get(it)!!.subject })
        assertTrue(sync.claimNew().isEmpty(), "claimed once")
        assertEquals(9, f.uidNext)
    }

    @Test
    fun uidRangeEndIsNotNewMail() {
        inbox.addAll(5)
        val before = start()
        // A message came and went: UIDNEXT moved, and `UID 6:*` returns the last message (UID 5) again.
        inbox.add()
        inbox.expunge(listOf(6L))
        val id = row(before, 5)!!.id
        server.take()
        push()
        assertTrue(server.take().any { it.startsWith("UID FETCH 6:* ") })
        assertEquals(5, messages.count(before.id))
        assertEquals(id, row(before, 5)!!.id)
        assertEquals(7, folder(FolderRole.INBOX).uidNext)
        assertTrue(sync.claimNew().isEmpty())
    }

    @Test
    fun flagsComeWithChangedSinceAndSkipRowsOfPendingOps() {
        inbox.addAll(3)
        val f = start()
        inbox.flag(1, "\\Seen")
        inbox.flag(2, "\\Seen")
        inbox.flag(3, "\\Flagged")
        val held = row(f, 2)!!.id
        ops.insert(NewOp(accountId, OpKind.FLAGS, listOf(held)))
        server.take()
        val report = push()
        assertTrue(server.take().any { it == "UID FETCH 1:* (UID FLAGS) (CHANGEDSINCE ${f.modSeq})" })
        assertTrue(row(f, 1)!!.seen)
        assertFalse(row(f, 2)!!.seen, "a pending op keeps the local state (protocol PS2)")
        assertTrue(row(f, 3)!!.flagged)
        assertTrue(report.changed)
        assertEquals(inbox.modSeq, folder(FolderRole.INBOX).modSeq)

        server.take()
        push()
        assertTrue(server.take().none { "CHANGEDSINCE" in it }, "nothing changed: no flags FETCH")
    }

    @Test
    fun expungesAreFoundByWindowCounts() {
        inbox.addAll(10)
        val f = start()
        server.take()
        push()
        assertTrue(server.take().none { "SEARCH" in it }, "EXISTS equals the local count: no search")

        inbox.expunge(listOf(3L, 4L, 5L))
        val report = push()
        val sent = server.take()
        assertTrue("UID SEARCH RETURN (COUNT) UID 1:10" in sent)
        assertTrue("UID SEARCH RETURN (ALL) UID 1:10" in sent)
        assertEquals(listOf(1L, 2L, 6L, 7L, 8L, 9L, 10L), messages.uids(f.id))
        assertTrue(report.changed)
    }

    @Test
    fun massDeleteFreezesTheFolderButNotTrash() {
        inbox.addAll(120)
        val trash = server.box("Trash", "\\Trash")
        trash.addAll(120)
        val f = start()
        sync.fullSweep(SyncReport())
        inbox.expunge((1L..100L).toList())
        trash.expunge((1L..100L).toList())

        push()
        assertTrue(folder(FolderRole.INBOX).guard)
        assertEquals(120, messages.count(f.id), "rows kept until the user resyncs")
        val report = SyncReport()
        sync.fullSweep(report)
        assertEquals(20, messages.count(folder(FolderRole.TRASH).id), "Trash empties itself (protocol PS9)")
        assertFalse(folder(FolderRole.TRASH).guard)

        server.take()
        push()
        assertTrue(server.take().none { "INBOX" in it }, "a guarded folder does not sync")
    }

    @Test
    fun newUidValidityDropsOpsAndRows() {
        inbox.addAll(10)
        val f = start()
        val held = row(f, 4)!!.id
        messages.setHidden(listOf(held), true)
        ops.insert(NewOp(accountId, OpKind.MOVE, listOf(held), runAt = Long.MAX_VALUE))
        inbox.uidValidity = 2
        inbox.msgs.clear()
        inbox.uidNext = 1
        inbox.addAll(4)

        val report = push()
        assertTrue(ops.byAccount(accountId).isEmpty(), "ops on stale UIDs are dropped (protocol PS11)")
        val after = folder(FolderRole.INBOX)
        assertEquals(2, after.uidValidity)
        assertEquals(listOf(1L, 2L, 3L, 4L), messages.uids(after.id))
        assertTrue(report.changed)
        assertTrue(sync.claimNew().isEmpty(), "a resync is a first sync")
    }

    @Test
    fun newUidValidityOfABigFolderGoesThroughTheGuard() {
        inbox.addAll(60)
        val f = start()
        inbox.uidValidity = 9
        push()
        assertTrue(folder(FolderRole.INBOX).guard)
        assertEquals(60, messages.count(f.id))
        folders.resync(f.id)
        push()
        assertEquals(9, folder(FolderRole.INBOX).uidValidity)
        assertEquals(60, messages.count(f.id))
    }

    @Test
    fun notifyFoldersAreSweptAndNotify() {
        val lists = server.box("riseup.Lists")
        lists.addAll(2)
        start()
        sync.fullSweep(SyncReport())
        folders.setNotify(folder("riseup.Lists").id, true)
        lists.add(subject = "Filtered")
        server.take()
        sync.notifySweep(SyncReport())
        assertTrue("STATUS \"riseup.Lists\" (MESSAGES UIDNEXT UIDVALIDITY HIGHESTMODSEQ)" in server.take())
        assertEquals(listOf("Filtered"), sync.claimNew().map { messages.get(it)!!.subject })
    }

    @Test
    fun fullSweepSkipsUnchangedFoldersAndReadsTheQuota() {
        server.box("Sent", "\\Sent").addAll(3)
        server.box("Archive").addAll(2)
        start()
        sync.fullSweep(SyncReport())
        assertEquals(3, messages.count(folder(FolderRole.SENT).id))
        assertEquals(FolderRole.ARCHIVE, folder("Archive").role)
        assertEquals(512L * 1024, accounts.get(accountId)!!.quotaUsed)
        assertEquals(1_048_576L * 1024, accounts.get(accountId)!!.quotaLimit)

        server.take()
        sync.fullSweep(SyncReport())
        val sent = server.take()
        assertEquals(1, sent.count { it.startsWith("LIST ") }, "one LIST-STATUS for every folder")
        assertTrue(sent.none { it.startsWith("SELECT") }, "nothing changed")
    }

    @Test
    fun theUidNextCheckCatchesMailBeforeIdle() {
        server.box("Sent", "\\Sent")
        inbox.addAll(2)
        start()
        assertFalse(sync.missedMail(SyncReport()), "INBOX still selected: the server reports new mail itself")

        sync.fullSweep(SyncReport())
        sync.syncFolder(folder(FolderRole.SENT), SyncReport())
        inbox.add(subject = "Just in time")
        val report = SyncReport()
        assertTrue(sync.missedMail(report))
        assertEquals(listOf("Just in time"), sync.claimNew().map { messages.get(it)!!.subject })
        assertFalse(sync.missedMail(SyncReport()))
    }

    @Test
    fun unsolicitedFlagUpdatesAreNoNewRows() {
        inbox.addAll(250)
        // A flag change of an old message during the first-sync fetch (DESIGN §4.3).
        server.onCommand = { if (it.startsWith("FETCH 51:* ")) server.announce("* 1 FETCH (UID 1 FLAGS (\\Seen))") }
        val f = start()
        assertEquals(51, f.backfillUid, "the lowest UID of the header rows")
        assertEquals(200, messages.count(f.id))
        assertNull(row(f, 1))
    }

    @Test
    fun aFailedExpungeCheckIsNotSkippedLater() {
        val work = server.box("Work")
        work.addAll(3)
        start()
        sync.fullSweep(SyncReport())
        work.expunge(listOf(2L))
        server.refuse = { it.startsWith("UID SEARCH RETURN (COUNT)") }
        assertFailsWith<ImapNoException> { sync.fullSweep(SyncReport()) }
        server.refuse = null
        sync.fullSweep(SyncReport())
        assertNull(row(folder("Work"), 2), "the next sweep finds the expunge")
        assertEquals(2, messages.count(folder("Work").id))
    }

    @Test
    fun existsOutsideASelectIsNewMail() {
        inbox.addAll(2)
        start()
        assertEquals(0, serverNewMail, "EXISTS of a SELECT does not count")
        server.announce("* 3 EXISTS")
        sync.fullSweep(SyncReport()) // any command: the quota
        assertEquals(1, serverNewMail)
    }

    private class MemoryNotes : KeyValueStore {
        private val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
