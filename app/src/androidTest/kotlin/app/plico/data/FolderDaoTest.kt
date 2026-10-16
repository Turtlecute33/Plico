package app.plico.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderDaoTest : DbFixture() {
    private val acc = account()

    @Test
    fun upsertByAccountKindPathKeepsSyncState() {
        val id = mailbox(acc, "INBOX", FolderRole.INBOX)
        folders.setSyncState(id, uidValidity = 7, uidNext = 100, modSeq = 55, backfillUid = 1, syncedAt = 9)
        folders.setSyncState(id, uidNext = 120)

        val again = folders.upsert(NewFolder(acc, FolderKind.MAILBOX, "INBOX", "Inbox", FolderRole.INBOX, "."))
        assertEquals(id, again)
        val f = folders.get(id)!!
        assertEquals("Inbox", f.name)
        assertEquals(".", f.delimiter)
        assertEquals(listOf(7L, 120L, 55L, 1L, 9L), listOf(f.uidValidity, f.uidNext, f.modSeq, f.backfillUid, f.syncedAt))

        // Same path as a label view is another row.
        val labelId = label(acc, "INBOX")
        assertTrue(labelId != id)
        assertEquals(id, folders.byPath(acc, FolderKind.MAILBOX, "INBOX")?.id)
        assertEquals(id, folders.byRole(acc, FolderRole.INBOX, FolderKind.MAILBOX)?.id)
        assertNull(folders.byRole(acc, FolderRole.INBOX, FolderKind.LABEL))
        assertEquals(2, folders.byAccount(acc).size)
    }

    @Test
    fun ensureLabelCreatesHiddenRowOnce() {
        val id = folders.ensureLabel(acc, "Receipts", "Receipts")
        assertEquals(id, folders.ensureLabel(acc, "Receipts", "Receipts"))
        assertTrue(folders.get(id)!!.hidden)

        // LIST shows it later: visible, same id.
        assertEquals(id, label(acc, "Receipts"))
        assertFalse(folders.get(id)!!.hidden)
    }

    @Test
    fun flagsAndVisibleFolders() = runBlocking {
        val inbox = mailbox(acc, "INBOX", FolderRole.INBOX)
        val drafts = folders.upsert(NewFolder(acc, FolderKind.MAILBOX, "Drafts", "Drafts", FolderRole.DRAFTS, "/", hidden = true))
        folders.setNotify(inbox, true)
        folders.setGuard(inbox, true)
        val f = folders.get(inbox)!!
        assertTrue(f.notify && f.guard)
        assertEquals(listOf(inbox), withTimeout(5_000) { folders.observeVisible(acc).first() }.map { it.id })
        folders.setHidden(drafts, false)
        assertEquals(setOf(inbox, drafts), withTimeout(5_000) { folders.observeVisible().first() }.map { it.id }.toSet())
        assertEquals(inbox, withTimeout(5_000) { folders.observe(inbox).first() }?.id)
    }

    @Test
    fun resyncDropsRowsAndResetsState() {
        val inbox = mailbox(acc, "INBOX", FolderRole.INBOX)
        val other = mailbox(acc, "Other")
        insert(header(acc, inbox, 1), header(acc, inbox, 2), header(acc, other, 1))
        folders.setSyncState(inbox, uidValidity = 3, uidNext = 3, modSeq = 8, backfillUid = 1)
        folders.setGuard(inbox, true)

        folders.resync(inbox)
        val f = folders.get(inbox)!!
        assertEquals(listOf(0L, 0L, 0L, 0L), listOf(f.uidValidity, f.uidNext, f.modSeq, f.backfillUid))
        assertFalse(f.guard)
        assertEquals(0, messages.count(inbox))
        assertEquals(1, messages.count(other))
        assertEquals(1, count("SELECT count(*) FROM message_fts"))
    }

    @Test
    fun deleteCascadesMessages() {
        val inbox = mailbox(acc, "INBOX", FolderRole.INBOX)
        insert(header(acc, inbox, 1))
        folders.delete(inbox)
        assertNull(folders.get(inbox))
        assertEquals(0, count("SELECT count(*) FROM message"))
    }
}
