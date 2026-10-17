package app.plico.sync

import app.plico.push.policy.KeepalivePolicy.Action

/** Why the loop runs a cycle now. */
internal data class SyncTrigger(
    /** First cycle on a new connection. */
    val connected: Boolean = false,
    /** IDLE reported a change, or the server announced new mail during other work. */
    val changed: Boolean = false,
    /** Pull to refresh, or the app became visible. */
    val userRefresh: Boolean = false,
    /** A poll of a polling account. */
    val poll: Boolean = false,
    /** A keepalive check that ended IDLE (DESIGN.md §8.3). */
    val check: Action? = null,
)

/**
 * What one loop cycle does, in the order of DESIGN.md §7.1. Push events touch only the IDLE mailbox and the
 * notify folders; the full STATUS sweep runs on user refresh, app visible, polls and every 6 h.
 */
internal data class SyncPlan(
    val listFolders: Boolean,
    /** New mail of the IDLE mailbox (Gmail: All Mail), then its flags and expunges. */
    val fastPath: Boolean,
    /**
     * A flag check is due (user refresh, the 6-h sweep). Without CONDSTORE: the full local range instead of the
     * newest 200 rows, and the STATUS sweep does not skip unchanged mailboxes (D8).
     */
    val fullFlags: Boolean,
    /** Not Gmail: STATUS of the folders with notify = 1 (push PB16). */
    val notifySweep: Boolean,
    /** Gmail: STATUS of All Mail at a refresh wake (push PB16, simplicity EM7). */
    val allMailStatus: Boolean,
    /** STATUS of every synced mailbox; changed ones sync. */
    val fullSweep: Boolean,
    /** One backfill step: only on an unmetered network or while the app is visible. */
    val backfill: Boolean,
) {
    companion object {
        const val LIST_EVERY_MS = 6 * 60 * 60_000L
        const val SWEEP_EVERY_MS = 6 * 60 * 60_000L

        private val SWEEP_CHECKS = setOf(Action.REFRESH, Action.EARLY_REFRESH, Action.RE_IDLE)

        /** [lastListAt] and [lastSweepAt] are elapsedRealtime values, null when not yet done in this process. */
        fun of(
            trigger: SyncTrigger,
            gmail: Boolean,
            now: Long,
            lastListAt: Long?,
            lastSweepAt: Long?,
            unknownLabel: Boolean,
            unmetered: Boolean,
            appVisible: Boolean,
        ): SyncPlan {
            val sweepDue = lastSweepAt == null || now - lastSweepAt >= SWEEP_EVERY_MS
            val fullSweep = trigger.userRefresh || trigger.poll || sweepDue
            val fastPath = trigger.connected || trigger.changed || fullSweep
            val refreshWake = trigger.check in SWEEP_CHECKS
            return SyncPlan(
                listFolders = lastListAt == null || now - lastListAt >= LIST_EVERY_MS || unknownLabel,
                fastPath = fastPath,
                fullFlags = trigger.userRefresh || sweepDue,
                notifySweep = !gmail && !fullSweep && (fastPath || refreshWake),
                allMailStatus = gmail && !fastPath && refreshWake,
                fullSweep = fullSweep,
                backfill = unmetered || appVisible,
            )
        }
    }
}
