package app.plico.sync

import app.plico.push.policy.KeepalivePolicy.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncPlanTest {
    private val hour = 60 * 60_000L
    private val now = 100 * hour

    private fun plan(
        trigger: SyncTrigger,
        gmail: Boolean = false,
        lastListAt: Long? = now - hour,
        lastSweepAt: Long? = now - hour,
        lastFlagsAt: Long? = lastSweepAt,
        relist: Boolean = false,
        unmetered: Boolean = false,
        visible: Boolean = false,
        at: Long = now,
    ) = SyncPlan.of(trigger, gmail, at, lastListAt, lastSweepAt, lastFlagsAt, relist, unmetered, visible)

    @Test
    fun pushEventsTouchOnlyTheIdleMailboxAndNotifyFolders() {
        val p = plan(SyncTrigger(changed = true))
        assertTrue(p.fastPath)
        assertTrue(p.notifySweep)
        assertFalse(p.fullSweep)
        assertFalse(p.fullFlags)
        assertFalse(p.listFolders)
        assertFalse(p.allMailStatus)
        assertFalse(plan(SyncTrigger(changed = true), gmail = true).notifySweep, "Gmail: All Mail covers labels")
    }

    @Test
    fun fullSweepOnUserRefreshPollAndEverySixHours() {
        assertTrue(plan(SyncTrigger(userRefresh = true)).fullSweep)
        assertTrue(plan(SyncTrigger(userRefresh = true)).fullFlags, "the user opened it: full flag range")
        assertTrue(plan(SyncTrigger(poll = true)).fullSweep)
        assertFalse(plan(SyncTrigger(poll = true)).fullFlags)
        assertTrue(plan(SyncTrigger(), lastSweepAt = now - 6 * hour).fullSweep)
        assertTrue(plan(SyncTrigger(), lastSweepAt = null).fullSweep, "first cycle of the process")
        assertFalse(plan(SyncTrigger(), lastSweepAt = now - 6 * hour + 1).fullSweep)
        val sweep = plan(SyncTrigger(poll = true))
        assertTrue(sweep.fastPath)
        assertFalse(sweep.notifySweep, "the full sweep covers the notify folders")
    }

    @Test
    fun flagsAreDueOnUserRefreshAndTheSixHourSweepOnly() {
        // Without CONDSTORE the STATUS sweep skips unchanged mailboxes unless flags are due (DESIGN §7.2 step 2).
        assertTrue(plan(SyncTrigger(userRefresh = true)).fullFlags)
        assertTrue(plan(SyncTrigger(poll = true), lastSweepAt = now - 6 * hour).fullFlags)
        assertTrue(plan(SyncTrigger(poll = true), lastSweepAt = null).fullFlags)
        assertFalse(plan(SyncTrigger(poll = true)).fullFlags, "a 15-min poll")
        assertFalse(plan(SyncTrigger(changed = true)).fullFlags)
        assertFalse(plan(SyncTrigger(connected = true)).fullFlags)
    }

    @Test
    fun appVisibleSweepsWithoutTheFullFlagCheck() {
        // PLICO-PERF-002: a return to the app is no pull to refresh; without CONDSTORE it must not fetch all flags.
        val shown = plan(SyncTrigger(appShown = true))
        assertTrue(shown.fullSweep)
        assertTrue(shown.fastPath)
        assertFalse(shown.fullFlags)
        assertTrue(plan(SyncTrigger(appShown = true), lastSweepAt = now - hour, lastFlagsAt = now - 6 * hour).fullFlags)
        assertTrue(plan(SyncTrigger(appShown = true), lastFlagsAt = null).fullFlags, "first check of the process")
        assertTrue(plan(SyncTrigger(appShown = true, userRefresh = true)).fullFlags, "a pull to refresh still checks")
    }

    @Test
    fun pollModeChecksFlagsEverySixHours() {
        // Every poll is a sweep, so the 6 h count from the last flag check (DESIGN §7.2 step 2), as AccountSession does.
        var lastSweepAt: Long? = null
        var lastFlagsAt: Long? = null
        val checks = ArrayList<Long>()
        val poll = 15 * 60_000L
        for (time in (0 until 13 * 4).map { now + it * poll }) {
            val p = plan(SyncTrigger(poll = true), lastSweepAt = lastSweepAt, lastFlagsAt = lastFlagsAt, at = time)
            assertTrue(p.fullSweep)
            lastSweepAt = time
            if (p.fullFlags) {
                lastFlagsAt = time
                checks += time - now
            }
        }
        assertEquals(listOf(0L, 6 * hour, 12 * hour), checks, "the first poll, then every 6 h")
        // A flag check that is due waits for a sweep: an IDLE event does not get the full flag range.
        assertFalse(plan(SyncTrigger(changed = true), lastSweepAt = now - hour, lastFlagsAt = now - 7 * hour).fullFlags)
        assertTrue(plan(SyncTrigger(poll = true), lastSweepAt = now - hour, lastFlagsAt = now - 7 * hour).fullFlags)
    }

    @Test
    fun refreshWakesSweepNotifyFoldersOrAllMail() {
        for (check in listOf(Action.REFRESH, Action.EARLY_REFRESH, Action.RE_IDLE)) {
            val riseup = plan(SyncTrigger(check = check))
            assertTrue(riseup.notifySweep, "$check")
            assertFalse(riseup.fastPath)
            val gmail = plan(SyncTrigger(check = check), gmail = true)
            assertTrue(gmail.allMailStatus, "$check")
            assertFalse(gmail.notifySweep)
        }
        assertFalse(plan(SyncTrigger(check = Action.DEAD_PEER_CHECK)).notifySweep, "a liveness check only")
        assertFalse(plan(SyncTrigger(check = Action.REFRESH, changed = true), gmail = true).allMailStatus, "fast path did it")
    }

    @Test
    fun aNewConnectionRunsTheFastPath() {
        val p = plan(SyncTrigger(connected = true))
        assertTrue(p.fastPath)
        assertFalse(p.fullSweep)
        assertFalse(plan(SyncTrigger()).fastPath, "queued work alone")
    }

    @Test
    fun folderListOnFirstConnectEverySixHoursAndUnknownLabels() {
        assertTrue(plan(SyncTrigger(), lastListAt = null).listFolders)
        assertTrue(plan(SyncTrigger(), lastListAt = now - 6 * hour).listFolders)
        assertTrue(plan(SyncTrigger(), relist = true).listFolders)
        assertFalse(plan(SyncTrigger(userRefresh = true)).listFolders)
    }

    @Test
    fun backfillOnlyOnUnmeteredOrVisible() {
        assertFalse(plan(SyncTrigger()).backfill)
        assertTrue(plan(SyncTrigger(), unmetered = true).backfill)
        assertTrue(plan(SyncTrigger(), visible = true).backfill)
        assertEquals(plan(SyncTrigger(), unmetered = true).copy(backfill = false), plan(SyncTrigger()))
    }
}
