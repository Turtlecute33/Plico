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
        relist: Boolean = false,
        unmetered: Boolean = false,
        visible: Boolean = false,
    ) = SyncPlan.of(trigger, gmail, now, lastListAt, lastSweepAt, relist, unmetered, visible)

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
