package app.plico.push.policy

import app.plico.push.policy.Backoff.Kind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackoffTest {
    // nextDouble() = 0.5 gives a jitter factor of exactly 1.
    private val backoff = Backoff(FixedRandom(0.5))
    private var now = 0L

    /** One attempt that fails with [kind]; returns the delay to the retry. */
    private fun fail(kind: Kind = Kind.NETWORK, upMs: Long = 0, byUser: Boolean = false): Backoff.Retry {
        assertTrue(backoff.onAttempt(byUser))
        now += 2 * SEC
        if (upMs > 0) {
            backoff.onIdle(now)
            now += upMs
        }
        return assertNotNull(backoff.onFailure(now, kind))
    }

    private fun delays(count: Int, kind: Kind = Kind.NETWORK, upMs: Long = 0): List<Long> = List(count) {
        val retry = fail(kind, upMs)
        (retry.at - now).also { now = retry.at }
    }

    @Test
    fun failFastLoopKeepsClimbing() {
        // IDLE '+' arrives, but a middlebox kills every connection after 30 s: never healthy, never reset.
        assertEquals(
            listOf(0, 5 * SEC, 20 * SEC, 2 * MIN, 5 * MIN, 15 * MIN, 30 * MIN, 60 * MIN, 60 * MIN),
            delays(9, upMs = 30 * SEC),
        )
        assertEquals(8, backoff.step)
    }

    @Test
    fun inProcessStepsThenAlarms() {
        val retries = List(5) { fail().also { now = it.at } }
        assertEquals(listOf(true, true, true, false, false), retries.map { it.inProcess })
        assertEquals(retries[4].at, backoff.retryAt)
        assertFalse(backoff.isDue(retries[4].at - 1))
        assertTrue(backoff.isDue(retries[4].at))
        backoff.onAttempt()
        assertNull(backoff.retryAt, "an attempt consumes the alarm")
    }

    @Test
    fun inProcessRetryArmsNoAlarm() {
        val retry = fail()
        assertTrue(retry.inProcess)
        assertEquals(now, retry.at)
        assertNull(backoff.retryAt)
    }

    @Test
    fun twoMinutesInIdleIsHealthy() {
        delays(4)
        assertEquals(4, backoff.step)
        val retry = fail(upMs = 2 * MIN)
        assertEquals(now, retry.at, "reset, then the first step again")
        assertTrue(retry.inProcess)
        assertEquals(1, backoff.step)
    }

    @Test
    fun healthyIsMeasuredFromTheFirstIdleOfTheConnection() {
        delays(4)
        assertTrue(backoff.onAttempt())
        backoff.onIdle(now)
        now += MIN
        backoff.onIdle(now)
        now += MIN
        val retry = assertNotNull(backoff.onFailure(now, Kind.NETWORK))
        assertEquals(now, retry.at)
    }

    @Test
    fun successfulRefreshOrPollIsHealthy() {
        delays(5)
        backoff.markHealthy()
        assertEquals(0, backoff.step)
        assertEquals(listOf(0L, 5 * SEC), delays(2))
    }

    @Test
    fun throttledStartsAtTheTwoMinuteAlarm() {
        val retry = fail(Kind.THROTTLED)
        assertFalse(retry.inProcess)
        assertEquals(2 * MIN, retry.at - now)
        now = retry.at
        assertEquals(listOf(5 * MIN, 15 * MIN), delays(2))
        assertEquals(listOf(30 * MIN), delays(1, Kind.THROTTLED), "a later step stays where it is")
    }

    @Test
    fun tlsUsesFifteenThirtySixty() {
        assertEquals(listOf(15 * MIN, 30 * MIN, 60 * MIN, 60 * MIN), delays(4, Kind.TLS))
        assertTrue(backoff.onNetworkChanged(7))
        assertEquals(listOf(15 * MIN), delays(1, Kind.TLS), "retry on network change, then the TLS steps again")
    }

    @Test
    fun userActionKeepsTheStepAndTheAlarm() {
        delays(4)
        val alarm = assertNotNull(backoff.retryAt)
        now = alarm - MIN
        val retry = fail(byUser = true)
        assertEquals(alarm, retry.at)
        assertFalse(retry.inProcess)
        assertEquals(alarm, backoff.retryAt)
        assertEquals(4, backoff.step)

        now = alarm
        assertEquals(listOf(5 * MIN), delays(1), "the alarm attempt continues the sequence")
    }

    @Test
    fun userActionWithoutBackoffIsANormalAttempt() {
        val retry = fail(byUser = true)
        assertTrue(retry.inProcess)
        assertEquals(1, backoff.step)
    }

    @Test
    fun userActionThatBecomesHealthyResets() {
        delays(4)
        val retry = fail(upMs = 3 * MIN, byUser = true)
        assertEquals(now, retry.at)
        assertEquals(1, backoff.step)
    }

    @Test
    fun networkChangeResetsOncePerNetwork() {
        assertTrue(backoff.onNetworkChanged(100))
        delays(5)
        assertFalse(backoff.onNetworkChanged(100), "VALIDATED or unblocked on the same network")
        assertEquals(5, backoff.step)
        assertTrue(backoff.retryAt != null)

        assertTrue(backoff.onNetworkChanged(101))
        assertEquals(0, backoff.step)
        assertNull(backoff.retryAt)
        assertFalse(backoff.onNetworkChanged(101))
    }

    @Test
    fun authRequiredStopsRetries() {
        delays(2)
        assertTrue(backoff.onAttempt())
        assertNull(backoff.onFailure(now, Kind.AUTH))
        assertTrue(backoff.stopped)
        assertNull(backoff.retryAt)
        assertFalse(backoff.isDue(now + 2 * HOUR))
        assertFalse(backoff.onAttempt())
        assertFalse(backoff.onAttempt(byUser = true))
        backoff.onNetworkChanged(5)
        assertTrue(backoff.stopped, "a new network does not retry a sign-in problem")

        backoff.reset()
        assertFalse(backoff.stopped)
        assertEquals(listOf(0L), delays(1))
    }

    @Test
    fun alarmStepsHaveTwentyPercentJitter() {
        val bases = listOf(2 * MIN, 5 * MIN, 15 * MIN, 30 * MIN, 60 * MIN)
        val random = Random(42)
        val seen = mutableSetOf<Long>()
        repeat(200) {
            val b = Backoff(random)
            var t = 0L
            repeat(3) { t = assertNotNull(b.onFailure(t, Kind.NETWORK)).at }
            for (base in bases) {
                val retry = assertNotNull(b.onFailure(t, Kind.NETWORK))
                val delay = retry.at - t
                assertTrue(delay >= base * 8 / 10 && delay < base * 12 / 10, "delay $delay for $base")
                if (base == 2 * MIN) seen += delay
                t = retry.at
            }
        }
        assertTrue(seen.size > 100, "jitter spreads the retries")
    }

    @Test
    fun inProcessStepsHaveNoJitter() {
        val b = Backoff(FixedRandom(0.99))
        assertEquals(listOf(0L, 5 * SEC, 20 * SEC), List(3) { assertNotNull(b.onFailure(0, Kind.NETWORK)).at })
    }
}
