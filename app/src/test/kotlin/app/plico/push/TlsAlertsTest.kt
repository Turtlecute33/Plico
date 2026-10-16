package app.plico.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TlsAlertsTest {
    private val hour = 60 * 60_000L
    private val alerts = TlsAlerts(delayMs = hour)

    @Test
    fun alertsAfterAnHourOfFailures() {
        assertFalse(alerts.onFailure(1, now = 0, network = 10))
        assertEquals(hour, alerts.nextDueAt())
        assertFalse(alerts.onFailure(1, now = hour - 1, network = 10))
        assertTrue(alerts.onFailure(1, now = hour, network = 10))
        // Once per streak.
        assertFalse(alerts.onFailure(1, now = 2 * hour, network = 10))
        assertNull(alerts.nextDueAt())
    }

    @Test
    fun alertsAtOnceOnASecondNetwork() {
        assertFalse(alerts.onFailure(1, now = 0, network = 10))
        assertFalse(alerts.onFailure(1, now = 60_000, network = null))
        assertTrue(alerts.onFailure(1, now = 120_000, network = 11))
    }

    @Test
    fun wakesFindDueAlerts() {
        alerts.onFailure(1, now = 0, network = 10)
        alerts.onFailure(2, now = 30 * 60_000L, network = 10)
        assertEquals(emptyList(), alerts.due(hour - 1))
        assertEquals(listOf(1L), alerts.due(hour))
        assertEquals(hour + 30 * 60_000L, alerts.nextDueAt())
        assertEquals(emptyList(), alerts.due(hour + 1))
    }

    @Test
    fun aGoodConnectionEndsTheStreak() {
        alerts.onFailure(1, now = 0, network = 10)
        alerts.clear(1)
        assertNull(alerts.nextDueAt())
        assertFalse(alerts.onFailure(1, now = hour + 1, network = 10))
    }
}
