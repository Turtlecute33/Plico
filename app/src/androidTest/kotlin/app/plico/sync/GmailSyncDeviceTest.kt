package app.plico.sync

import app.plico.R
import app.plico.account.MailError
import app.plico.data.AccountState
import app.plico.data.DbFixture
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MailView
import app.plico.data.Provider
import app.plico.push.policy.KeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Gmail sync (DESIGN.md §7.3) against [SyncFakeServer] with a real database. */
class GmailSyncDeviceTest : DbFixture() {
    private val accountId = account("me@gmail.com", Provider.GMAIL)
    private val server = SyncFakeServer(caps = "IMAP4rev1 IDLE MOVE UIDPLUS ESEARCH CONDSTORE X-GM-EXT-1", delimiter = "/")
    private val inbox = server.box("INBOX")
    private val all: SyncFakeServer.Box
    private val notes = MemoryNotes()

    init {
        server.box("[Gmail]", "\\Noselect")
        all = server.box("[Gmail]/All Mail", "\\All")
        server.box("[Gmail]/Drafts", "\\Drafts")
        server.box("[Gmail]/Sent Mail", "\\Sent")
        server.box("[Gmail]/Spam", "\\Junk")
        server.box("[Gmail]/Trash", "\\Trash")
        server.box("Work")
    }

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
                notes = notes,
                protectedIds = { ops.referencedMessageIds(accountId) },
            ),
        )
    }

    /** Gmail delivers to All Mail; a message with `\Inbox` also shows in INBOX (its own UIDs). */
    private fun deliver(subject: String, labels: List<String> = listOf("\\Inbox"), category: String = "primary") =
        all.add(subject = subject, labels = labels, category = category).also {
            if ("\\Inbox" in labels) inbox.add(subject = subject, gmMsgId = it.gmMsgId)
        }

    private fun view(path: String): Folder = folders.byPath(accountId, FolderKind.LABEL, path)!!
    private fun allMail(): Folder = folders.byRole(accountId, FolderRole.ALL, FolderKind.MAILBOX)!!

    private fun subjects(folder: Folder): Set<String> = runBlocking {
        messages.observeList(MailView.InFolder(folder.id), 100).first().map { it.subject }.toSet()
    }

    private fun start(): SyncReport {
        sync.refreshFolders()
        return SyncReport().also(sync::fastPath)
    }

    @Test
    fun allMailIsTheSourceAndLabelsAreViews() {
        deliver("A", listOf("\\Inbox", "Work"))
        deliver("B", listOf("\\Sent"))
        deliver("C", listOf("\\Inbox", "Hidden label"))
        val report = start()

        assertEquals(setOf("A", "B", "C"), subjects(allMail()))
        assertEquals(setOf("A", "C"), subjects(view("\\Inbox")))
        assertEquals(setOf("A"), subjects(view("Work")))
        assertEquals(setOf("B"), subjects(view("\\Sent")))
        val hidden = view("Hidden label")
        assertTrue(hidden.hidden, "a label that LIST does not show gets a hidden view (protocol PS6)")
        assertEquals(setOf("C"), subjects(hidden))
        assertTrue(report.relist)
        assertEquals(2, report.newInbox)
        assertTrue(server.commands.any { it.endsWith(" X-GM-MSGID X-GM-LABELS)") && it.startsWith("FETCH 1:* ") })
        val drafts = folders.byRole(accountId, FolderRole.DRAFTS, FolderKind.MAILBOX)!!
        assertTrue(drafts.hidden, "[Gmail]/Drafts is stored for APPEND, not synced")
        assertTrue(folders.byAccount(accountId).none { it.path == "INBOX" || it.path == "[Gmail]/Sent Mail" })
        assertEquals(AccountState.OK, accounts.get(accountId)!!.state)
    }

    @Test
    fun theFastPathNotifiesOnlyEnabledCategories() {
        start()
        deliver("Personal")
        deliver("Social", category = "social")
        deliver("Archived", listOf("Work"))
        server.take()
        val report = SyncReport()
        sync.fastPath(report)
        assertEquals(2, report.newInbox)
        assertEquals(listOf("Personal"), sync.claimNew().map { messages.get(it)!!.subject })
        assertTrue(server.take().any { it.startsWith("UID SEARCH RETURN (ALL) UID ") && it.endsWith(" X-GM-RAW \"category:primary\"") })

        accounts.update(accounts.get(accountId)!!.copy(gmailCategories = GmailSync.CATEGORIES.toSet()))
        deliver("Promo", category = "promotions")
        sync.fastPath(SyncReport())
        assertEquals(listOf("Promo"), sync.claimNew().map { messages.get(it)!!.subject })
        assertTrue(server.take().none { "X-GM-RAW" in it }, "all five enabled: no search")
    }

    @Test
    fun theUidNextCheckRunsTheFastPathForMailAfterTheFetch() {
        deliver("Old")
        start()
        assertFalse(sync.missedMail(SyncReport()), "no baseline yet")
        assertEquals(inbox.uidNext, view("\\Inbox").uidNext)

        deliver("First")
        val report = SyncReport()
        sync.fastPath(report)
        assertEquals(listOf("First"), sync.claimNew().map { messages.get(it)!!.subject })
        deliver("Second") // arrives after the All Mail fetch, before SELECT INBOX
        assertTrue(sync.missedMail(report))
        assertEquals(listOf("Second"), sync.claimNew().map { messages.get(it)!!.subject })
        assertEquals(inbox.uidNext, view("\\Inbox").uidNext)
        assertFalse(sync.missedMail(SyncReport()), "INBOX is selected now")
    }

    @Test
    fun labelChangesComeWithChangedSince() {
        val a = deliver("A")
        start()
        all.setLabels(a.uid, "Work") // archived in Gmail on the web
        val report = SyncReport()
        sync.fastPath(report)
        sync.idleMailboxFlags(fullFlags = false, report)
        assertTrue(subjects(view("\\Inbox")).isEmpty())
        assertEquals(setOf("A"), subjects(view("Work")))
        assertTrue(report.changed)
    }

    @Test
    fun aLabelShownForTheFirstTimeIsFilledOnce() {
        val a = deliver("A")
        start()
        server.box("Projects").add(subject = "A", gmMsgId = a.gmMsgId)
        server.take()
        sync.refreshFolders()
        val sent = server.take()
        assertTrue("EXAMINE \"Projects\"" in sent)
        assertTrue("UID FETCH 1:* (UID X-GM-MSGID)" in sent)
        assertEquals(setOf("A"), subjects(view("Projects")))

        sync.refreshFolders()
        assertTrue(server.take().none { it.startsWith("EXAMINE") }, "once")
    }

    @Test
    fun trashAndSpamSyncWithoutLabels() {
        server.boxes.getValue("[Gmail]/Trash").add(subject = "Gone", labels = listOf("Work"))
        start()
        sync.fullSweep(SyncReport())
        val trash = folders.byRole(accountId, FolderRole.TRASH, FolderKind.MAILBOX)!!
        assertEquals(setOf("Gone"), subjects(trash))
        assertTrue(subjects(view("Work")).isEmpty())
    }

    @Test
    fun hiddenAllMailStopsSyncAndKeepsRows() {
        deliver("A")
        start()
        server.boxes.remove("[Gmail]/All Mail")
        val error = assertFailsWith<MailError.Setup> { sync.refreshFolders() }
        val account = accounts.get(accountId)!!
        assertEquals(AccountState.SETUP_PROBLEM, account.state)
        assertEquals(context.getString(R.string.account_gmail_all_mail_hidden), account.stateText)
        assertEquals(account.stateText, error.message)
        assertEquals(1, messages.count(allMail().id), "local rows stay")
    }

    @Test
    fun hiddenTrashIsANoteAndSyncContinues() {
        server.boxes.remove("[Gmail]/Trash")
        start()
        val account = accounts.get(accountId)!!
        assertEquals(AccountState.SETUP_PROBLEM, account.state)
        assertEquals(context.getString(R.string.account_note_gmail_trash_hidden), account.stateText)
        assertFailsWith<MailError.Setup> { sync.ensureFolder(FolderRole.TRASH) }

        server.box("[Gmail]/Trash", "\\Trash")
        sync.refreshFolders()
        assertEquals(AccountState.OK, accounts.get(accountId)!!.state)
    }

    @Test
    fun allMailAtAFolderSizeLimitGivesOneNote() {
        all.addAll(1_000)
        start()
        assertEquals(AccountState.SETUP_PROBLEM, accounts.get(accountId)!!.state)
        assertEquals(context.getString(R.string.sync_gmail_size_limit), accounts.get(accountId)!!.stateText)
        accounts.setState(accountId, AccountState.OK, null)
        sync.fastPath(SyncReport())
        assertEquals(AccountState.OK, accounts.get(accountId)!!.state, "only once")
    }

    @Test
    fun refreshWakesCheckAllMailWithStatus() {
        deliver("A")
        start()
        server.take()
        sync.checkAllMail(SyncReport())
        assertEquals(listOf("STATUS \"[Gmail]/All Mail\" (MESSAGES UIDNEXT UIDVALIDITY HIGHESTMODSEQ)"), server.take())

        all.add(subject = "Skipped the inbox", labels = listOf("Work"))
        sync.checkAllMail(SyncReport())
        assertEquals(setOf("A", "Skipped the inbox"), subjects(allMail()))
    }

    private class MemoryNotes : KeyValueStore {
        private val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
