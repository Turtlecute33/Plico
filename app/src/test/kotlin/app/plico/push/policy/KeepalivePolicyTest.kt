package app.plico.push.policy

import app.plico.push.policy.KeepalivePolicy.Action
import app.plico.push.policy.KeepalivePolicy.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeepalivePolicyTest {
    private val store = MemoryStore()
    private val learned = LearnedIntervals(store)
    private var clock = 0L

    private fun gmail() = KeepalivePolicy(9, "gmail", learned)
    private fun riseup() = KeepalivePolicy(15, "riseup", learned)

    private fun KeepalivePolicy.idle(network: String, expiresAt: Long? = null) =
        onIdleStarted(clock, network, expiresAt)

    /** Jumps to the next due time, expects a normal refresh there, reports it and re-enters IDLE. */
    private fun KeepalivePolicy.refresh(network: String, alive: Boolean = true) {
        clock = assertNotNull(nextDueAt())
        val action = decide(clock)
        assertEquals(Action.REFRESH, action)
        onIdleEnded()
        onChecked(action, clock, alive)
        clock += SEC
        idle(network)
    }

    private fun KeepalivePolicy.serverClose(ageMs: Long, network: String, networkChanged: Boolean = false) {
        clock += ageMs
        onServerClosed(clock, networkChanged)
        clock += 5 * SEC
        idle(network)
    }

    /** A client-mode session against a silent server that ends every IDLE at [serverLimitMs] (null = never). */
    private inner class SilentServer(val policy: KeepalivePolicy, val network: String, val serverLimitMs: Long?) {
        var closes = 0
        var refreshes = 0
        private var idleStart = clock

        init {
            policy.idle(network)
        }

        fun runUntil(end: Long) {
            while (true) {
                val due = assertNotNull(policy.nextDueAt())
                val close = serverLimitMs?.let { idleStart + it }
                val next = minOf(due, close ?: Long.MAX_VALUE)
                if (next > end) return
                if (close != null && close < due) {
                    clock = close
                    policy.onServerClosed(clock, networkChanged = false)
                    closes++
                    clock += 5 * SEC
                } else {
                    clock = due
                    val action = policy.decide(clock)
                    assertEquals(Action.REFRESH, action)
                    policy.onIdleEnded()
                    policy.onChecked(action, clock, alive = true)
                    refreshes++
                    clock += SEC
                }
                idleStart = clock
                policy.idle(network)
            }
        }
    }

    // Client mode (Gmail)

    @Test
    fun silentServerDroppingIdleAtTenMinutesConvergesBelowAndLocks() {
        val policy = gmail()
        val sim = SilentServer(policy, "wifi:a", serverLimitMs = 10 * MIN)
        sim.runUntil(6 * HOUR)
        assertEquals(9 * MIN, policy.intervalMs)
        assertEquals(1, sim.closes, "one failed probe at 11 min, then locked")
        assertTrue(learned["gmail|wifi:a"]!!.isLocked(clock))
        assertTrue(sim.refreshes > 30)
    }

    @Test
    fun intervalEndsBelowAnyServerLimit() {
        for (limit in listOf(6 * MIN, 8 * MIN, 10 * MIN + 30 * SEC, 13 * MIN + 30 * SEC, 20 * MIN + 10 * SEC)) {
            val policy = KeepalivePolicy(9, "gmail", LearnedIntervals(MemoryStore()))
            val sim = SilentServer(policy, "wifi:a", limit)
            sim.runUntil(clock + 12 * HOUR)
            assertTrue(policy.intervalMs < limit, "limit $limit: interval ${policy.intervalMs}")
            assertTrue(policy.intervalMs >= limit - 3 * MIN, "limit $limit: interval ${policy.intervalMs} too low")
            assertEquals(1, sim.closes, "limit $limit")
        }
    }

    @Test
    fun healthyGmailGrowsToTwentyEight() {
        val policy = gmail()
        val sim = SilentServer(policy, "wifi:a", serverLimitMs = null)
        sim.runUntil(28 * MIN)
        assertEquals(11 * MIN, policy.intervalMs, "+2 min after 3 alive refreshes")
        sim.runUntil(24 * HOUR)
        assertEquals(28 * MIN, policy.intervalMs)
        assertEquals(LearnedInterval(28), learned["gmail|wifi:a"])
        assertEquals(0, sim.closes)
    }

    @Test
    fun clientRefreshIsDueAtIdleStartPlusInterval() {
        val policy = riseup()
        clock = 1_000 * MIN
        policy.idle("cellular")
        assertEquals(Mode.CLIENT, policy.mode)
        assertNull(policy.cadenceMs)
        assertEquals(clock + 15 * MIN, policy.nextDueAt())
    }

    @Test
    fun deadRefreshLowersAgainEachTimeDownToFive() {
        val policy = gmail()
        policy.idle("wifi:a")
        policy.refresh("wifi:a", alive = false)
        assertEquals(LearnedInterval(7, lockedAt = 9 * MIN), learned["gmail|wifi:a"])
        policy.refresh("wifi:a", alive = false)
        assertEquals(5 * MIN, policy.intervalMs)
        policy.refresh("wifi:a", alive = false)
        assertEquals(5 * MIN, policy.intervalMs)
    }

    @Test
    fun deadRefreshEndsIdle() {
        val policy = gmail()
        policy.idle("wifi:a")
        clock = 9 * MIN
        policy.onChecked(Action.REFRESH, clock, alive = false)
        assertNull(policy.nextDueAt())
        assertEquals(Action.NONE, policy.decide(clock + HOUR))
    }

    @Test
    fun lockedIntervalNeverGrows() {
        val policy = gmail()
        policy.idle("wifi:a")
        repeat(3) { policy.refresh("wifi:a") }
        assertEquals(11 * MIN, policy.intervalMs)
        policy.serverClose(10 * MIN, "wifi:a")
        assertEquals(9 * MIN, policy.intervalMs)
        repeat(30) { policy.refresh("wifi:a") }
        assertEquals(9 * MIN, policy.intervalMs)
    }

    @Test
    fun serverCloseCountsOnlyInClientModeAfterFiveMinutesWithoutNetworkChange() {
        val policy = gmail()
        policy.idle("wifi:a")
        policy.serverClose(5 * MIN - 1, "wifi:a")
        assertNull(learned["gmail|wifi:a"])
        policy.serverClose(8 * MIN, "wifi:a", networkChanged = true)
        assertNull(learned["gmail|wifi:a"])
        policy.serverClose(6 * MIN, "wifi:a")
        assertEquals(5, learned["gmail|wifi:a"]!!.minutes, "capped at age - 1 min")
    }

    @Test
    fun serverCloseOutsideIdleIsIgnored() {
        val policy = gmail()
        policy.idle("wifi:a")
        policy.onIdleEnded()
        clock += 8 * MIN
        policy.onServerClosed(clock, networkChanged = false)
        assertNull(learned["gmail|wifi:a"])
    }

    @Test
    fun earlyRefreshesNeverCountTowardGrowth() {
        val policy = gmail()
        policy.idle("wifi:a")
        assertEquals(Action.NONE, policy.decide(6 * MIN))
        repeat(6) {
            val start = clock
            clock = start + 7 * MIN
            assertEquals(Action.EARLY_REFRESH, policy.decide(clock))
            policy.onIdleEnded()
            policy.onChecked(Action.EARLY_REFRESH, clock, alive = true)
            policy.idle("wifi:a")
        }
        assertEquals(9 * MIN, policy.intervalMs)

        // An early refresh does not break a run of normal ones either.
        policy.refresh("wifi:a")
        policy.refresh("wifi:a")
        clock += 8 * MIN
        policy.onChecked(Action.EARLY_REFRESH, clock, alive = true)
        policy.idle("wifi:a")
        assertEquals(9 * MIN, policy.intervalMs)
        policy.refresh("wifi:a")
        assertEquals(11 * MIN, policy.intervalMs)
    }

    @Test
    fun earlyRefreshFailureStillCounts() {
        val policy = gmail()
        policy.idle("wifi:a")
        clock = 7 * MIN
        assertEquals(Action.EARLY_REFRESH, policy.decide(clock))
        policy.onChecked(Action.EARLY_REFRESH, clock, alive = false)
        assertEquals(LearnedInterval(7, lockedAt = clock), learned["gmail|wifi:a"])
    }

    @Test
    fun newNetworkKeyStartsUnlockedAndKnownKeyKeepsItsLock() {
        val policy = gmail()
        policy.idle("wifi:a")
        repeat(3) { policy.refresh("wifi:a") }
        policy.serverClose(10 * MIN, "wifi:a")
        assertEquals(9 * MIN, policy.intervalMs)

        policy.idle("cellular")
        repeat(3) { policy.refresh("cellular") }
        assertEquals(11 * MIN, policy.intervalMs, "the new key grows")

        policy.idle("wifi:a")
        assertEquals(9 * MIN, policy.intervalMs)
        repeat(6) { policy.refresh("wifi:a") }
        assertEquals(9 * MIN, policy.intervalMs, "wifi:a is still locked")

        policy.idle("cellular")
        assertEquals(11 * MIN, policy.intervalMs, "each key keeps its own value")
    }

    @Test
    fun networkChangeRestartsTheAliveRun() {
        val policy = gmail()
        policy.idle("wifi:a")
        repeat(2) { policy.refresh("wifi:a") }
        policy.idle("wifi:b")
        policy.refresh("wifi:b")
        assertEquals(9 * MIN, policy.intervalMs)
        repeat(2) { policy.refresh("wifi:b") }
        assertEquals(11 * MIN, policy.intervalMs)
        assertNull(learned["gmail|wifi:a"])
    }

    @Test
    fun lockEndsAfterTwentyFourHours() {
        val policy = gmail()
        policy.idle("wifi:a")
        policy.refresh("wifi:a", alive = false)
        val lockedAt = learned["gmail|wifi:a"]!!.lockedAt!!
        assertEquals(7 * MIN, policy.intervalMs)

        clock = lockedAt + 23 * HOUR
        policy.idle("wifi:a")
        repeat(3) { policy.refresh("wifi:a") }
        assertEquals(7 * MIN, policy.intervalMs)

        clock = lockedAt + 24 * HOUR
        policy.idle("wifi:a")
        repeat(3) { policy.refresh("wifi:a") }
        assertEquals(LearnedInterval(9), learned["gmail|wifi:a"])
    }

    @Test
    fun learnedValuesAreSharedBySessionsOfOneScopeOnly() {
        val first = gmail()
        val second = gmail()
        val other = riseup()
        first.idle("wifi:a")
        repeat(3) { first.refresh("wifi:a") }
        second.idle("wifi:a")
        other.idle("wifi:a")
        assertEquals(11 * MIN, second.intervalMs)
        assertEquals(15 * MIN, other.intervalMs)
    }

    // Server mode (Dovecot)

    private fun KeepalivePolicy.keepalives(from: Long, every: Long, count: Int): Long {
        var t = from
        repeat(count) {
            onKeepalive(t)
            t += every
        }
        return t - every
    }

    @Test
    fun dovecotKeepalivesSelectServerModeAndDeadPeerCheck() {
        val policy = riseup()
        policy.idle("cellular")
        assertEquals(Action.NONE, policy.onKeepalive(2 * MIN))
        policy.onKeepalive(4 * MIN)
        assertNull(policy.cadenceMs, "one gap is not enough")
        assertEquals(Mode.CLIENT, policy.mode)
        policy.onKeepalive(6 * MIN)
        assertEquals(2 * MIN, policy.cadenceMs)
        assertEquals(Mode.SERVER, policy.mode)

        // Next keepalive at 8 min; the check is due 2.5 min after the last one.
        assertEquals(8 * MIN + 30 * SEC, policy.nextDueAt())
        policy.onKeepalive(8 * MIN)
        assertEquals(10 * MIN + 30 * SEC, policy.nextDueAt())
        assertEquals(Action.NONE, policy.decide(10 * MIN + 29 * SEC))
        assertEquals(Action.DEAD_PEER_CHECK, policy.decide(10 * MIN + 30 * SEC))
    }

    @Test
    fun deadPeerMarginIsAQuarterOfLongCadences() {
        val policy = riseup()
        policy.idle("wifi:a")
        val last = policy.keepalives(from = 8 * MIN, every = 8 * MIN, count = 3)
        assertEquals(Mode.SERVER, policy.mode)
        assertEquals(last + 10 * MIN, policy.nextDueAt())
    }

    @Test
    fun cadenceAtOrAboveTheClientIntervalStaysClientMode() {
        val policy = riseup()
        policy.idle("wifi:a")
        policy.keepalives(from = 15 * MIN, every = 15 * MIN, count = 3)
        assertEquals(15 * MIN, policy.cadenceMs)
        assertEquals(Mode.CLIENT, policy.mode)
    }

    @Test
    fun cadenceIsTheMedianOfTheLastFiveGaps() {
        val policy = riseup()
        policy.idle("wifi:a")
        var t = 0L
        // One short gap (keepalive soon after other output) does not move the median.
        for (gap in listOf(2 * MIN, 2 * MIN, 20 * SEC, 2 * MIN, 2 * MIN)) {
            t += gap
            policy.onKeepalive(t)
        }
        assertEquals(2 * MIN, policy.cadenceMs)
        for (gap in listOf(3 * MIN, 3 * MIN, 3 * MIN)) {
            t += gap
            policy.onKeepalive(t)
        }
        assertEquals(3 * MIN, policy.cadenceMs, "only the last five gaps count")
    }

    @Test
    fun gapsAreMeasuredWithinOneIdleOnly() {
        val policy = riseup()
        policy.idle("wifi:a")
        policy.onKeepalive(2 * MIN)
        clock = 3 * MIN
        policy.onIdleEnded()
        policy.idle("wifi:a")
        policy.onKeepalive(3 * MIN + 10 * SEC)
        policy.onKeepalive(5 * MIN + 10 * SEC)
        assertNull(policy.cadenceMs, "the 70 s gap across the re-IDLE is not counted")
        policy.onKeepalive(7 * MIN + 10 * SEC)
        assertEquals(2 * MIN, policy.cadenceMs)
    }

    @Test
    fun deadPeerCheckAfterReIdleCountsFromIdleStart() {
        val policy = riseup()
        policy.idle("wifi:a")
        policy.keepalives(from = 2 * MIN, every = 2 * MIN, count = 3)
        clock = 6 * MIN + 20 * SEC
        policy.onIdleEnded()
        policy.idle("wifi:a")
        assertEquals(clock + 2 * MIN + 30 * SEC, policy.nextDueAt())
    }

    @Test
    fun reIdleRunsInAKeepaliveWakeAfterTwentyFiveMinutes() {
        val policy = riseup()
        policy.idle("wifi:a")
        var t = 2 * MIN
        while (t <= 24 * MIN) {
            assertEquals(Action.NONE, policy.onKeepalive(t))
            if (t >= 6 * MIN) assertEquals(t + 2 * MIN + 30 * SEC, policy.nextDueAt())
            assertEquals(Action.NONE, policy.decide(t))
            t += 2 * MIN
        }
        assertEquals(Action.RE_IDLE, policy.onKeepalive(26 * MIN))
        assertEquals(Action.NONE, policy.decide(26 * MIN), "re-IDLE never needs its own alarm")
        clock = 26 * MIN
        policy.onIdleEnded()
        policy.onChecked(Action.RE_IDLE, clock, alive = true)
        policy.idle("wifi:a")
        assertEquals(Action.NONE, policy.onKeepalive(28 * MIN))
        assertEquals(Mode.SERVER, policy.mode)
        assertNull(learned["riseup|wifi:a"])
    }

    @Test
    fun twoAliveDeadPeerChecksInARowSwitchToClientMode() {
        val policy = riseup()
        policy.idle("wifi:a")
        policy.keepalives(from = 2 * MIN, every = 2 * MIN, count = 3)

        // The server stops sending keepalives.
        clock = assertNotNull(policy.nextDueAt())
        assertEquals(Action.DEAD_PEER_CHECK, policy.decide(clock))
        policy.onIdleEnded()
        policy.onChecked(Action.DEAD_PEER_CHECK, clock, alive = true)
        clock += SEC
        policy.idle("wifi:a")
        assertEquals(Mode.SERVER, policy.mode)

        clock = assertNotNull(policy.nextDueAt())
        assertEquals(Action.DEAD_PEER_CHECK, policy.decide(clock))
        policy.onIdleEnded()
        policy.onChecked(Action.DEAD_PEER_CHECK, clock, alive = true)
        clock += SEC
        policy.idle("wifi:a")
        assertEquals(Mode.CLIENT, policy.mode)
        assertNull(policy.cadenceMs)
        assertEquals(clock + 15 * MIN, policy.nextDueAt())

        // Keepalives come back: measured again, server mode again.
        policy.keepalives(from = clock + 2 * MIN, every = 2 * MIN, count = 3)
        assertEquals(Mode.SERVER, policy.mode)
    }

    @Test
    fun keepaliveBetweenAliveChecksResetsTheCount() {
        val policy = riseup()
        policy.idle("wifi:a")
        policy.keepalives(from = 2 * MIN, every = 2 * MIN, count = 3)
        repeat(3) {
            clock = assertNotNull(policy.nextDueAt())
            assertEquals(Action.DEAD_PEER_CHECK, policy.decide(clock))
            policy.onChecked(Action.DEAD_PEER_CHECK, clock, alive = true)
            clock += SEC
            policy.idle("wifi:a")
            policy.onKeepalive(clock + MIN)
        }
        assertEquals(Mode.SERVER, policy.mode)
    }

    @Test
    fun serverModeFailuresDoNotTouchTheClientInterval() {
        val policy = riseup()
        policy.idle("wifi:a")
        policy.keepalives(from = 2 * MIN, every = 2 * MIN, count = 3)
        clock = assertNotNull(policy.nextDueAt())
        policy.onChecked(Action.DEAD_PEER_CHECK, clock, alive = false)
        assertNull(policy.nextDueAt())
        clock += 5 * SEC
        policy.idle("wifi:a")
        policy.serverClose(20 * MIN, "wifi:a")
        assertNull(learned["riseup|wifi:a"])
        assertEquals(Mode.SERVER, policy.mode, "the cadence is kept across reconnects")
    }

    // OAuth / device session expiry

    @Test
    fun expiryIsADueTime() {
        val policy = gmail()
        policy.idle("wifi:a", expiresAt = 40 * MIN)
        assertEquals(9 * MIN, policy.nextDueAt())
        clock = 36 * MIN
        policy.idle("wifi:a", expiresAt = 40 * MIN)
        assertEquals(40 * MIN, policy.nextDueAt())
    }

    @Test
    fun refreshWithinFiveMinutesOfExpiryReconnects() {
        val policy = gmail()
        policy.idle("wifi:a", expiresAt = 40 * MIN)
        clock = 27 * MIN
        policy.idle("wifi:a", expiresAt = 40 * MIN)
        assertEquals(Action.NONE, policy.decide(32 * MIN))
        assertEquals(Action.EARLY_REFRESH, policy.decide(35 * MIN), "exactly 5 min left")
        assertEquals(Action.RECONNECT, policy.decide(36 * MIN), "4 min left")

        clock = 18 * MIN
        policy.idle("wifi:a", expiresAt = 40 * MIN)
        assertEquals(Action.REFRESH, policy.decide(27 * MIN), "13 min left")
    }

    @Test
    fun expiryWakeReconnectsAndCoalesces() {
        learned["gmail|wifi:a"] = LearnedInterval(28)
        val policy = gmail()
        clock = 28 * MIN
        policy.idle("wifi:a", expiresAt = 40 * MIN)
        assertEquals(40 * MIN, policy.nextDueAt())
        assertEquals(Action.NONE, policy.decide(30 * MIN))
        // Window for a 28-min interval is 28 / 3 min: a Riseup keepalive wake at 31 min takes the reconnect along.
        assertEquals(Action.RECONNECT, policy.decide(31 * MIN))
        assertEquals(Action.RECONNECT, policy.decide(40 * MIN))
    }

    @Test
    fun notIdleMeansNoDueTime() {
        val policy = gmail()
        assertNull(policy.nextDueAt())
        assertEquals(Action.NONE, policy.decide(HOUR))
        assertEquals(9 * MIN, policy.intervalMs)
        policy.idle("wifi:a", expiresAt = 10 * MIN)
        policy.onIdleEnded()
        assertNull(policy.nextDueAt())
        assertEquals(Action.NONE, policy.decide(HOUR))
    }

    @Test
    fun learnedValueIsKeptInRange() {
        learned["gmail|wifi:a"] = LearnedInterval(90)
        learned["gmail|wifi:b"] = LearnedInterval(1)
        val policy = gmail()
        policy.idle("wifi:a")
        assertEquals(28 * MIN, policy.intervalMs)
        policy.idle("wifi:b")
        assertEquals(5 * MIN, policy.intervalMs)
    }

    @Test
    fun keepaliveOutsideIdleIsNotAGap() {
        val policy = riseup()
        policy.onKeepalive(MIN)
        policy.onKeepalive(3 * MIN)
        policy.onKeepalive(5 * MIN)
        assertNull(policy.cadenceMs)
        assertEquals(0, store.writes)
    }
}
