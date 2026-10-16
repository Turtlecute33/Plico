package app.plico.push.policy

/**
 * Runs client refreshes and polls during wakes that already happen (DESIGN.md §8.3, push PB13).
 *
 * At every wake (alarm, server byte, network callback, app visible), an item runs when it is due before
 * `now + W`: W = max(3 min, interval / 3) for refreshes, interval / 3 for polls. An item that runs before its
 * due time is early; an early refresh never counts toward interval growth.
 */
object Coalescing {
    const val MIN_REFRESH_WINDOW_MS = 3 * MINUTE_MS

    enum class Kind { REFRESH, POLL }

    enum class Timing {
        /** Not due within the window. */
        LATER,

        /** Due within the window, pulled into this wake. */
        EARLY,

        /** Due now or overdue. */
        DUE,
    }

    fun windowMs(kind: Kind, intervalMs: Long): Long = when (kind) {
        Kind.REFRESH -> maxOf(MIN_REFRESH_WINDOW_MS, intervalMs / 3)
        Kind.POLL -> intervalMs / 3
    }

    fun timing(now: Long, dueAt: Long, intervalMs: Long, kind: Kind): Timing = when {
        dueAt <= now -> Timing.DUE
        dueAt < now + windowMs(kind, intervalMs) -> Timing.EARLY
        else -> Timing.LATER
    }
}
