package app.plico.sync.fallback

import app.plico.data.DbFixture
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.push.policy.KeyValueStore
import app.plico.sync.SyncAccount
import app.plico.sync.SyncEnv
import app.plico.sync.SyncFakeServer
import app.plico.sync.SyncReport
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sync of a bare IMAP4rev1 server: no ESEARCH, CONDSTORE, LIST-STATUS, UIDPLUS, MOVE or IDLE (DESIGN §7.2, plan
 * §5.5). Needs the `:mail` fallbacks of stream A (plain `UID SEARCH`, SELECT without UIDNEXT): written in wave 1,
 * run from wave 2 on.
 */
class MailboxSyncFallbackDeviceTest : DbFixture() {
    private val accountId = otherAccount()
    private val server = SyncFakeServer(caps = "IMAP4rev1")
    private val inbox = server.box("INBOX")

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
            ),
        )
    }

    private fun folder(path: String): Folder = folders.byPath(accountId, FolderKind.MAILBOX, path)!!
    private fun inboxRow(): Folder = folders.byRole(accountId, FolderRole.INBOX, FolderKind.MAILBOX)!!

    private fun start(): Folder {
        sync.refreshFolders()
        sync.fastPath(SyncReport())
        return inboxRow()
    }

    private fun push(): SyncReport = SyncReport().also {
        sync.fastPath(it)
        sync.idleMailboxFlags(fullFlags = false, it)
    }

    /** No command may use an extension that the server does not have. */
    private fun assertPlain(commands: List<String>) {
        val extensions = listOf("RETURN (", "CHANGEDSINCE", "MODSEQ", "ENABLE", "UID EXPUNGE", "UID MOVE", "X-GM-")
        val used = commands.filter { c -> extensions.any { it in c } }
        assertTrue(used.isEmpty(), "extension commands: $used")
    }

    @Test
    fun firstSyncNewMailAndFlagsWithoutExtensions() {
        inbox.addAll(250)
        val f = start()
        assertEquals(200, messages.count(f.id))
        assertEquals(51, f.backfillUid)
        assertEquals(251, f.uidNext)
        assertTrue(server.commands.any { it.startsWith("FETCH 51:* (UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[") })

        inbox.add(subject = "New one")
        inbox.flag(240, "\\Seen")
        server.take()
        push()
        val sent = server.take()
        assertTrue(sent.any { it.startsWith("UID FETCH 251:* ") }, "new mail")
        assertTrue(sent.any { it.matches(Regex("""UID FETCH \d+:251 \(UID FLAGS\)""")) }, "flags of the newest rows")
        assertTrue(messages.byUid(f.id, 240)!!.seen)
        assertEquals(listOf("New one"), sync.claimNew().map { messages.get(it)!!.subject })
        assertPlain(server.commands + sent)
    }

    @Test
    fun theExpungeCheckIsOnePlainSearchPerWindow() {
        inbox.addAll(10)
        val f = start()
        inbox.expunge(listOf(3L, 4L, 5L))
        server.take()
        push()
        val sent = server.take()
        assertEquals(1, sent.count { it.startsWith("UID SEARCH") }, "the size of the list replaces COUNT")
        assertTrue("UID SEARCH UID 1:10" in sent, "$sent")
        assertEquals(listOf(1L, 2L, 6L, 7L, 8L, 9L, 10L), messages.uids(f.id))
        assertPlain(sent)
    }

    @Test
    fun backfillSearchesDownInWindowsOf50000Uids() {
        inbox.addAll(10)
        inbox.skipUids(120_000)
        inbox.addAll(250)
        val f = start()
        assertEquals(120_061, f.backfillUid)
        assertTrue(sync.backfillStep(SyncReport()))
        assertEquals(119_561, inboxRow().backfillUid)

        server.take()
        assertTrue(sync.backfillStep(SyncReport()))
        val searches = server.take().filter { it.startsWith("UID SEARCH") }
        assertEquals(
            listOf("UID SEARCH UID 69061:119060", "UID SEARCH UID 19061:69060", "UID SEARCH UID 1:19060"),
            searches,
            "each line lists at most 50,000 UIDs; the first window with a hit ends the search",
        )
        assertEquals(1, inboxRow().backfillUid)
        assertEquals(260, messages.count(f.id))
    }

    @Test
    fun aSelectWithoutUidNextKeepsTheStatusValue() {
        server.selectUidNext = false
        inbox.addAll(3)
        val work = server.box("Work")
        work.addAll(3)
        work.expunge(listOf(3L)) // UIDNEXT stays 4; the highest UID is 2.
        val f = start()
        assertEquals(4, f.uidNext, "estimate from FETCH * (UID): highest UID + 1")
        sync.fullSweep(SyncReport(), fullFlags = true)
        assertEquals(4, folder("Work").uidNext, "the STATUS UIDNEXT, not the estimate 3")

        server.take()
        sync.fullSweep(SyncReport(), fullFlags = false)
        val poll = server.take()
        assertEquals(listOf("STATUS \"Work\" (MESSAGES UIDNEXT UIDVALIDITY)"), poll, "unchanged: no SELECT at every poll")
    }

    @Test
    fun deletedRowsAreHidden() {
        inbox.addAll(3)
        inbox.flag(1, "\\Deleted")
        val f = start()
        assertEquals(1L, count("SELECT count(*) FROM message WHERE folder_id = ? AND deleted = 1", f.id))
        assertTrue(sync.claimNew().isEmpty())
    }

    private class MemoryNotes : KeyValueStore {
        private val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
