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
        for (flagsDue in listOf(true, false)) {
            assertTrue(MailboxMath.unchanged(status, folder, knownMessages = 100, flagsDue), "CONDSTORE: $flagsDue")
            assertFalse(MailboxMath.unchanged(status.copy(uidNext = 121), folder, 100, flagsDue), "new mail")
            assertFalse(MailboxMath.unchanged(status.copy(messages = 99), folder, 100, flagsDue), "an expunge")
            assertFalse(MailboxMath.unchanged(status.copy(highestModSeq = 901), folder, 100, flagsDue), "a flag change")
            assertFalse(MailboxMath.unchanged(status, folder, knownMessages = null, flagsDue), "count not known")
            assertFalse(MailboxMath.unchanged(status.copy(uidValidity = 8), folder, 100, flagsDue))
            assertFalse(MailboxMath.unchanged(status, folder.copy(backfillUid = 0), 100, flagsDue), "never synced")
        }
    }

    @Test
    fun withoutModSeqTheSkipWaitsOnlyForDueFlags() {
        val noModSeq = status.copy(highestModSeq = null)
        assertFalse(MailboxMath.unchanged(noModSeq, folder, 100, flagsDue = true), "refresh or 6-h sweep: flags (PS10)")
        assertTrue(MailboxMath.unchanged(noModSeq, folder, 100, flagsDue = false), "a poll skips the unchanged mailbox")
        assertFalse(MailboxMath.unchanged(noModSeq.copy(uidNext = 121), folder, 100, flagsDue = false), "new mail")
        assertFalse(MailboxMath.unchanged(noModSeq.copy(messages = 99), folder, 100, flagsDue = false), "an expunge")
        assertFalse(MailboxMath.unchanged(noModSeq, folder.copy(backfillUid = 0), 100, flagsDue = false), "never synced")
    }

    @Test
    fun searchWindowsGoDownFromBelow() {
        assertEquals(listOf(70_001L..120_000L, 20_001L..70_000L, 1L..20_000L), MailboxMath.searchWindows(120_001).toList())
        assertEquals(listOf(1L..50_000L), MailboxMath.searchWindows(50_001).toList())
        assertEquals(listOf(2L..50_001L, 1L..1L), MailboxMath.searchWindows(50_002).toList())
        assertEquals(listOf(1L..9L), MailboxMath.searchWindows(10).toList())
        assertEquals(listOf(1L..1L), MailboxMath.searchWindows(2).toList())
        assertTrue(MailboxMath.searchWindows(1).none())
        assertTrue(MailboxMath.searchWindows(0).none())
        val huge = MailboxMath.searchWindows(4_000_000_000L)
        assertEquals(3_999_950_000L..3_999_999_999L, huge.first(), "lazy: no list of 80,000 windows")
        assertTrue(MailboxMath.searchWindows(1_000_001).all { it.last - it.first < MailboxMath.SEARCH_WINDOW })
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
        // Without ESEARCH a window also stays within the span: old search hits far below the rest get their own.
        val sparse = listOf(120L, 130L) + (190_000L until 196_000L)
        assertEquals(listOf(5_000, 1_002), MailboxMath.windows(sparse).map { it.size })
        val spans = MailboxMath.windows(sparse, MailboxMath.SEARCH_WINDOW)
        assertEquals(listOf(2, 5_000, 1_000), spans.map { it.size })
        assertTrue(spans.all { it.last() - it.first() < MailboxMath.SEARCH_WINDOW })
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
