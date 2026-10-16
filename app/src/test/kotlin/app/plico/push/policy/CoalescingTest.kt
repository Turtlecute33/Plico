package app.plico.push.policy

import app.plico.push.policy.Coalescing.Kind
import app.plico.push.policy.Coalescing.Timing
import app.plico.push.policy.KeepalivePolicy.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoalescingTest {

    @Test
    fun windows() {
        assertEquals(3 * MIN, Coalescing.windowMs(Kind.REFRESH, 9 * MIN))
        assertEquals(5 * MIN, Coalescing.windowMs(Kind.REFRESH, 15 * MIN))
        assertEquals(28 * MIN / 3, Coalescing.windowMs(Kind.REFRESH, 28 * MIN))
        assertEquals(5 * MIN, Coalescing.windowMs(Kind.POLL, 15 * MIN))
        assertEquals(20 * MIN, Coalescing.windowMs(Kind.POLL, 60 * MIN))
    }

    @Test
    fun timing() {
        val now = 100 * MIN
        assertEquals(Timing.DUE, Coalescing.timing(now, now, 9 * MIN, Kind.REFRESH))
        assertEquals(Timing.DUE, Coalescing.timing(now, now - MIN, 9 * MIN, Kind.REFRESH))
        assertEquals(Timing.EARLY, Coalescing.timing(now, now + 3 * MIN - 1, 9 * MIN, Kind.REFRESH))
        assertEquals(Timing.LATER, Coalescing.timing(now, now + 3 * MIN, 9 * MIN, Kind.REFRESH))
        assertEquals(Timing.EARLY, Coalescing.timing(now, now + 4 * MIN, 15 * MIN, Kind.POLL))
        assertEquals(Timing.LATER, Coalescing.timing(now, now + 6 * MIN, 15 * MIN, Kind.POLL))
        // A Riseup wake takes a 28-min refresh due in 9 min (window 28 / 3 min).
        assertEquals(Timing.EARLY, Coalescing.timing(now, now + 9 * MIN, 28 * MIN, Kind.REFRESH))
    }

    @Test
    fun gmailRefreshesRideOnRiseupKeepaliveWakes() {
        val learned = LearnedIntervals(MemoryStore())
        val riseup = KeepalivePolicy(15, "riseup", learned)
        val gmail = KeepalivePolicy(9, "gmail", learned)
        riseup.onIdleStarted(0, "cellular", null)
        gmail.onIdleStarted(30 * SEC, "cellular", null)

        var gmailRefreshes = 0
        var gmailIdleStart = 30 * SEC
        var wake = 2 * MIN
        while (wake < 6 * HOUR) {
            if (riseup.onKeepalive(wake) == Action.RE_IDLE) {
                riseup.onIdleEnded()
                riseup.onChecked(Action.RE_IDLE, wake, alive = true)
                riseup.onIdleStarted(wake, "cellular", null)
            }
            // engine.onWake(): the Gmail refresh is never overdue, so Gmail needs no alarm of its own.
            assertTrue(assertNotNull(gmail.nextDueAt()) > wake)
            when (val action = gmail.decide(wake)) {
                Action.NONE -> Unit
                Action.EARLY_REFRESH -> {
                    assertTrue(wake - gmailIdleStart in 7 * MIN..9 * MIN)
                    gmail.onIdleEnded()
                    gmail.onChecked(action, wake, alive = true)
                    gmail.onIdleStarted(wake, "cellular", null)
                    gmailIdleStart = wake
                    gmailRefreshes++
                }
                else -> error("unexpected $action")
            }
            wake += 2 * MIN
        }
        assertTrue(gmailRefreshes >= 6 * 60 / 9)
        assertEquals(9 * MIN, gmail.intervalMs, "early refreshes never grow the interval")
        assertEquals(KeepalivePolicy.Mode.SERVER, riseup.mode)
    }
}
