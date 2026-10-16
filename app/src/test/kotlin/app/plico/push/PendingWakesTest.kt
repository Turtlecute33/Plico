package app.plico.push

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingWakesTest {
    @Test
    fun anAlarmIsAWakeThatIsTakenOnce() {
        val wakes = PendingWakes()
        assertEquals(PendingWakes.Taken(wake = false, alarm = false), wakes.take())
        wakes.wake()
        assertEquals(PendingWakes.Taken(wake = true, alarm = false), wakes.take())
        wakes.alarm()
        assertEquals(PendingWakes.Taken(wake = true, alarm = true), wakes.take())
        assertEquals(PendingWakes.Taken(wake = false, alarm = false), wakes.take())
    }

    // The engine ends the `alarm` wake lock on a taken alarm: by then the sessions must have had the wake.
    @Test
    fun aTakenAlarmNeverComesBeforeItsWake() {
        repeat(2_000) {
            val wakes = PendingWakes()
            val alarm = thread { wakes.alarm() }
            var woke = false
            while (true) {
                val taken = wakes.take()
                woke = woke || taken.wake
                if (taken.alarm) break
            }
            alarm.join()
            assertTrue(woke)
        }
    }
}
