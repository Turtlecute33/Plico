package app.plico.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchedulerTest {
    private class FakeAlarms(
        var exact: Boolean = false,
        var exempt: Boolean = false,
        var revoked: Boolean = false,
    ) : Scheduler.Alarms {
        val calls = mutableListOf<String>()

        override fun canScheduleExact() = exact
        override fun batteryExempt() = exempt

        override fun setExact(at: Long) {
            if (revoked) throw SecurityException()
            calls += "exact $at"
        }

        override fun setInexact(at: Long) {
            calls += "inexact $at"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    @Test
    fun exactWithThePermission() {
        val alarms = FakeAlarms(exact = true)
        assertEquals(Scheduler.Kind.EXACT, Scheduler(alarms).arm(1_000))
        assertEquals(listOf("exact 1000"), alarms.calls)
    }

    @Test
    fun exactWithTheBatteryExemptionAlone() {
        val alarms = FakeAlarms(exempt = true)
        assertEquals(Scheduler.Kind.EXACT, Scheduler(alarms).arm(1_000))
    }

    @Test
    fun inexactWithoutGrants() {
        val alarms = FakeAlarms()
        assertEquals(Scheduler.Kind.INEXACT, Scheduler(alarms).arm(1_000))
        assertEquals(listOf("inexact 1000"), alarms.calls)
    }

    @Test
    fun revokedGrantFallsBackToInexact() {
        val alarms = FakeAlarms(exact = true, revoked = true)
        assertEquals(Scheduler.Kind.INEXACT, Scheduler(alarms).arm(2_000))
        assertEquals(listOf("inexact 2000"), alarms.calls)
    }

    @Test
    fun noDueTimeCancels() {
        val alarms = FakeAlarms(exact = true)
        assertNull(Scheduler(alarms).arm(null))
        assertEquals(listOf("cancel"), alarms.calls)
    }
}
