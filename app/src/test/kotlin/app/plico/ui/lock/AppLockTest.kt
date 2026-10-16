package app.plico.ui.lock

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTest {
    private var enabled = true
    private var timeout = 5
    private var now = 1_000_000L
    private fun lock() = AppLock({ enabled }, { timeout }, { now })

    private fun AppLock.away(ms: Long) {
        onBackground()
        now += ms
        onForeground()
    }

    @Test
    fun dueAfterTheTimeout() {
        assertFalse(AppLock.isDue(1, backgroundAt = 0, now = 59_999))
        assertTrue(AppLock.isDue(1, backgroundAt = 0, now = 60_000))
        assertTrue(AppLock.isDue(30, backgroundAt = 5_000, now = 5_000 + 30 * 60_000))
    }

    @Test
    fun zeroMeansAtOnce() {
        assertTrue(AppLock.isDue(0, backgroundAt = 42, now = 42))
        assertTrue(AppLock.isDue(-3, backgroundAt = 42, now = 42))
    }

    @Test
    fun lockedAtStartWhenEnabled() {
        assertTrue(lock().locked.value)
        enabled = false
        assertFalse(lock().locked.value)
    }

    @Test
    fun unlockOpens() {
        val lock = lock()
        lock.unlock()
        assertFalse(lock.locked.value)
    }

    @Test
    fun shortAbsenceKeepsItOpen() {
        val lock = lock().apply { unlock() }
        lock.away(5 * 60_000L - 1)
        assertFalse(lock.locked.value)
    }

    @Test
    fun absenceOfTheTimeoutLocks() {
        val lock = lock().apply { unlock() }
        lock.away(5 * 60_000L)
        assertTrue(lock.locked.value)
    }

    @Test
    fun timeoutZeroLocksOnEveryReturn() {
        timeout = 0
        val lock = lock().apply { unlock() }
        lock.away(0)
        assertTrue(lock.locked.value)
    }

    @Test
    fun eachAbsenceCountsOnItsOwn() {
        val lock = lock().apply { unlock() }
        lock.away(3 * 60_000L)
        lock.away(3 * 60_000L)
        assertFalse(lock.locked.value)
    }

    @Test
    fun theTimeoutSettingIsReadOnReturn() {
        val lock = lock().apply { unlock() }
        lock.onBackground()
        now += 60_000
        timeout = 1
        lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test
    fun foregroundWithoutBackgroundChangesNothing() {
        val lock = lock()
        lock.onForeground()
        assertTrue(lock.locked.value)
        lock.unlock()
        now += 60 * 60_000L
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test
    fun leavingWhileLockedStartsNoTimer() {
        // The screen-lock page of the system prompt stops the activity while it is locked.
        val lock = lock()
        lock.onBackground()
        now += 60 * 60_000L
        lock.unlock()
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test
    fun turningTheLockOnDoesNotLockAtOnce() {
        enabled = false
        val lock = lock()
        enabled = true
        lock.away(60_000)
        assertFalse(lock.locked.value)
        lock.away(5 * 60_000L)
        assertTrue(lock.locked.value)
    }

    @Test
    fun turningTheLockOffOpensOnReturn() {
        val lock = lock()
        enabled = false
        lock.onForeground()
        assertFalse(lock.locked.value)
        lock.away(60 * 60_000L)
        assertFalse(lock.locked.value)
    }

    @Test
    fun enabledFollowsTheSetting() {
        val lock = lock()
        assertTrue(lock.enabled)
        enabled = false
        assertFalse(lock.enabled)
    }
}
