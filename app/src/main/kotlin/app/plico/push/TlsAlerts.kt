package app.plico.push

/**
 * When a TLS failure becomes a notification (DESIGN §8.3 error classes, push PB9): the app shows the state
 * at once, but "Secure connection failed" is posted only after the failures went on for 1 h, or happened
 * on a second network. Times are elapsedRealtime. Thread-safe.
 */
internal class TlsAlerts(private val delayMs: Long = 60 * 60_000L) {
    private class Streak(val since: Long, val networks: MutableSet<Long> = HashSet(), var posted: Boolean = false)

    private val streaks = HashMap<Long, Streak>()

    /** A TLS failure of [accountId] on [network] (a network handle). True when the alert is due now. */
    @Synchronized
    fun onFailure(accountId: Long, now: Long, network: Long?): Boolean {
        val streak = streaks.getOrPut(accountId) { Streak(now) }
        network?.let(streak.networks::add)
        return streak.takeDue(now)
    }

    /** Accounts whose alert became due by time alone (checked at every wake). */
    @Synchronized
    fun due(now: Long): List<Long> = streaks.filterValues { it.takeDue(now) }.keys.toList()

    /** When the next alert becomes due; part of the alarm time. */
    @Synchronized
    fun nextDueAt(): Long? = streaks.values.filterNot { it.posted }.minOfOrNull { it.since + delayMs }

    /** The account connected, or has another problem: the streak ends. */
    @Synchronized
    fun clear(accountId: Long) {
        streaks.remove(accountId)
    }

    private fun Streak.takeDue(now: Long): Boolean {
        if (posted || (now - since < delayMs && networks.size < 2)) return false
        posted = true
        return true
    }
}
