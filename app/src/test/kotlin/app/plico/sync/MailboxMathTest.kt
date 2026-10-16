package app.plico.sync

import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.mail.imap.MailboxStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailboxMathTest {
    private val folder = Folder(
        id = 1, accountId = 1, path = "INBOX", name = "Inbox", role = FolderRole.INBOX, kind = FolderKind.MAILBOX,
        delimiter = ".", hidden = false, notify = false, guard = false, uidValidity = 7, uidNext = 120, modSeq = 900,
        backfillUid = 1, syncedAt = 0,
    )
    private val status = MailboxStatus(messages = 100, uidNext = 120, uidValidity = 7, highestModSeq = 900)

    @Test
    fun firstSyncTakesTheNewest200BySequence() {
        assertNull(MailboxMath.firstSyncStart(0))
        assertEquals(1, MailboxMath.firstSyncStart(1))
        assertEquals(1, MailboxMath.firstSyncStart(200))
        assertEquals(2, MailboxMath.firstSyncStart(201))
        assertEquals(4801, MailboxMath.firstSyncStart(5000))
    }

    @Test
    fun backfillAfterTheFirstSync() {
        assertEquals(1, MailboxMath.backfillAfterFirstSync(start = 1, lowestUid = 37), "the whole mailbox was fetched")
        assertEquals(4321, MailboxMath.backfillAfterFirstSync(start = 4801, lowestUid = 4321))
        assertEquals(1, MailboxMath.backfillAfterFirstSync(start = 4801, lowestUid = null))
    }

    @Test
    fun backfillRanges() {
        assertNull(MailboxMath.backfillRange(0), "never synced")
        assertNull(MailboxMath.backfillRange(1), "complete")
        assertEquals(1L..1L, MailboxMath.backfillRange(2))
        assertEquals(1L..499L, MailboxMath.backfillRange(500))
        assertEquals(1L..500L, MailboxMath.backfillRange(501))
        assertEquals(4500L..4999L, MailboxMath.backfillRange(5000))
        assertEquals(3001L..3500L, MailboxMath.backfillRangeBelow(3500))
        assertEquals(1L..20L, MailboxMath.backfillRangeBelow(20))
    }

    @Test
    fun cheapCheckSkipsOnlyWhenEverythingIsKnownAndUnchanged() {
        assertTrue(MailboxMath.unchanged(status, folder, knownMessages = 100))
        assertFalse(MailboxMath.unchanged(status.copy(uidNext = 121), folder, 100), "new mail")
        assertFalse(MailboxMath.unchanged(status.copy(messages = 99), folder, 100), "an expunge")
        assertFalse(MailboxMath.unchanged(status.copy(highestModSeq = 901), folder, 100), "a flag change")
        assertFalse(MailboxMath.unchanged(status.copy(highestModSeq = null), folder, 100), "NOMODSEQ: never skip (PS10)")
        assertFalse(MailboxMath.unchanged(status, folder, knownMessages = null), "count not known")
        assertFalse(MailboxMath.unchanged(status.copy(uidValidity = 8), folder, 100))
        assertFalse(MailboxMath.unchanged(status, folder.copy(backfillUid = 0), 100), "never synced")
    }

    @Test
    fun expungeCheck() {
        assertTrue(MailboxMath.canSkipExpunge(backfillUid = 1, exists = 300, localRows = 300))
        assertFalse(MailboxMath.canSkipExpunge(backfillUid = 1, exists = 299, localRows = 300))
        assertFalse(MailboxMath.canSkipExpunge(backfillUid = 50, exists = 300, localRows = 300), "backfill incomplete")
        val windows = MailboxMath.windows((1L..12_000L).toList())
        assertEquals(listOf(5_000, 5_000, 2_000), windows.map { it.size })
        assertEquals(10_001L, windows[2].first())
        assertTrue(MailboxMath.windows(emptyList()).isEmpty())
    }

    @Test
    fun massDeleteGuard() {
        assertFalse(MailboxMath.guardTrips(missing = 50, rows = 60), "50 rows is not more than 50")
        assertTrue(MailboxMath.guardTrips(missing = 51, rows = 100))
        assertFalse(MailboxMath.guardTrips(missing = 51, rows = 102), "exactly half is not more than half")
        assertFalse(MailboxMath.guardTrips(missing = 400, rows = 1000))
        assertTrue(MailboxMath.guarded(FolderRole.INBOX))
        assertTrue(MailboxMath.guarded(null))
        assertFalse(MailboxMath.guarded(FolderRole.TRASH))
        assertFalse(MailboxMath.guarded(FolderRole.JUNK))
    }
}
