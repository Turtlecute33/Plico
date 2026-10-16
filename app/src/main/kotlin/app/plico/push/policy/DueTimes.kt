package app.plico.push.policy

internal const val SECOND_MS = 1_000L
internal const val MINUTE_MS = 60 * SECOND_MS
internal const val HOUR_MS = 60 * MINUTE_MS

/** Due-time math for the single coalesced alarm (DESIGN.md §8.3 Scheduler). Times are elapsedRealtime. */
object DueTimes {
    /** Backstop alarm while there is no default network (push PB5). */
    const val NO_NETWORK_BACKSTOP_MS = 30 * MINUTE_MS

    /** The earliest non-null time, or null when every time is null. */
    fun min(vararg times: Long?): Long? = min(times.asIterable())

    fun min(times: Iterable<Long?>): Long? = times.filterNotNull().minOrNull()

    /**
     * When to arm the alarm: the earliest of the sessions' [dues], plus a 30-min backstop from [now] while
     * there is no network. Null = no alarm.
     */
    fun alarmAt(now: Long, dues: Iterable<Long?>, hasNetwork: Boolean): Long? =
        min(min(dues), if (hasNetwork) null else now + NO_NETWORK_BACKSTOP_MS)
}
