package app.plico.sync

import app.plico.data.Folder
import app.plico.data.FolderRole
import app.plico.mail.imap.MailboxStatus

/** The arithmetic of mailbox sync (DESIGN.md §7.2); pure so that the limits are unit-tested. */
internal object MailboxMath {
    /** Headers of a first sync (simplicity EM3). */
    const val FIRST_SYNC = 200

    /** UIDs per backfill step. */
    const val BACKFILL_STEP = 500

    /** Local rows per expunge-check window (protocol PS4). */
    const val EXPUNGE_WINDOW = 5_000

    /** Newest local rows whose flags a push event refetches without CONDSTORE. */
    const val PUSH_FLAG_ROWS = 200

    /** The mass-delete guard trips above this many rows (and above half of the mailbox). */
    const val GUARD_MIN_ROWS = 50

    /**
     * UIDs per `UID SEARCH` on a server without ESEARCH: each `* SEARCH` line then lists at most this many UIDs,
     * far below the 1 MiB line limit of the reader.
     */
    const val SEARCH_WINDOW = 50_000L

    /** First sequence number of a first sync, `max(1, EXISTS − 199)`; null for an empty mailbox. */
    fun firstSyncStart(exists: Long): Long? = if (exists <= 0) null else maxOf(1, exists - FIRST_SYNC + 1)

    /**
     * `backfill_uid` after a first sync from sequence [start]: the lowest UID fetched, or 1 (complete) when the
     * fetch began at the first message.
     */
    fun backfillAfterFirstSync(start: Long, lowestUid: Long?): Long =
        if (start <= 1 || lowestUid == null) 1 else lowestUid

    /** The next backfill UID range below [backfillUid]; null when the backfill is complete or never started. */
    fun backfillRange(backfillUid: Long): LongRange? =
        if (backfillUid <= 1) null else maxOf(1, backfillUid - BACKFILL_STEP)..(backfillUid - 1)

    /** The range that ends at the highest older UID [max] (found by `UID SEARCH RETURN (MAX)`). */
    fun backfillRangeBelow(max: Long): LongRange = maxOf(1, max - BACKFILL_STEP + 1)..max

    /**
     * Step 2: skip the mailbox when UIDNEXT, MESSAGES and UIDVALIDITY are unchanged, and the flags are unchanged too:
     * a known HIGHESTMODSEQ that is the same (PS10), or, without one (no CONDSTORE), only while no flag check is due
     * ([flagsDue]: user refresh, folder open, the 6-h sweep). Else every poll would fetch the flags of every folder.
     */
    fun unchanged(status: MailboxStatus, folder: Folder, knownMessages: Long?, flagsDue: Boolean): Boolean =
        folder.backfillUid != 0L &&
            status.uidValidity == folder.uidValidity &&
            status.uidNext == folder.uidNext &&
            knownMessages == status.messages &&
            if (status.highestModSeq != null) status.highestModSeq == folder.modSeq else !flagsDue

    /**
     * The UID windows below [below] for a server without ESEARCH, highest first: `below-50000 .. below-1`, and so
     * on down to UID 1. Empty when [below] is 1 or less.
     */
    fun searchWindows(below: Long): Sequence<LongRange> =
        generateSequence(below - 1) { top -> (top - SEARCH_WINDOW).takeIf { it >= 1 } }
            .takeWhile { it >= 1 }
            .map { top -> maxOf(1, top - SEARCH_WINDOW + 1)..top }

    /** Step 6: no expunge can have happened when every server message is local. */
    fun canSkipExpunge(backfillUid: Long, exists: Long, localRows: Int): Boolean =
        backfillUid == 1L && exists == localRows.toLong()

    /** Step 6: windows of local UIDs (ascending) for the COUNT comparison. */
    fun windows(localUids: List<Long>): List<List<Long>> = localUids.chunked(EXPUNGE_WINDOW)

    /** The guard applies to every mailbox except Trash and Spam, which empty themselves (protocol PS9). */
    fun guarded(role: FolderRole?): Boolean = role != FolderRole.TRASH && role != FolderRole.JUNK

    /** True when deleting [missing] of [rows] local rows in one pass needs the user's confirmation. */
    fun guardTrips(missing: Int, rows: Int): Boolean = missing > GUARD_MIN_ROWS && missing * 2L > rows
}
