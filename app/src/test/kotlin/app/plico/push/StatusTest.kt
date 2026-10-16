package app.plico.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusTest {
    private val ok = PushStatus(
        running = true,
        pausedByUser = false,
        stoppedBySystem = false,
        hasNetwork = true,
        notificationsAllowed = true,
        batteryUnrestricted = true,
        exactAlarms = false,
        backgroundRestricted = false,
    )

    @Test
    fun healthyWithEitherGrant() {
        assertEquals(PushHealth.OK, ok.health)
        assertEquals(PushHealth.OK, ok.copy(batteryUnrestricted = false, exactAlarms = true).health)
        assertTrue(ok.reliable)
    }

    @Test
    fun withoutBothGrantsPushMayStop() {
        val status = ok.copy(batteryUnrestricted = false)
        assertFalse(status.reliable)
        assertEquals(PushHealth.UNRELIABLE, status.health)
    }

    @Test
    fun restrictedBatteryTurnsPushOffWhateverTheGrants() {
        val status = ok.copy(backgroundRestricted = true, exactAlarms = true)
        assertFalse(status.reliable)
        assertEquals(PushHealth.RESTRICTED, status.health)
    }

    @Test
    fun pausesComeFirst() {
        val broken = ok.copy(hasNetwork = false, notificationsAllowed = false, backgroundRestricted = true)
        assertEquals(PushHealth.PAUSED, broken.copy(pausedByUser = true, stoppedBySystem = true).health)
        assertEquals(PushHealth.STOPPED, broken.copy(stoppedBySystem = true).health)
        assertEquals(PushHealth.NOTIFICATIONS_OFF, ok.copy(notificationsAllowed = false, hasNetwork = false).health)
        assertEquals(PushHealth.NO_NETWORK, ok.copy(hasNetwork = false, batteryUnrestricted = false).health)
    }

    @Test
    fun serviceText() {
        assertEquals(ServiceText.CONNECTED, ServiceText.of(hasNetwork = true, reliable = true, delayed = false))
        assertEquals(
            ServiceText.WAITING_FOR_NETWORK,
            ServiceText.of(hasNetwork = false, reliable = false, delayed = true),
        )
        assertEquals(ServiceText.MAY_BE_LATE, ServiceText.of(hasNetwork = true, reliable = false, delayed = false))
        assertEquals(ServiceText.MAY_BE_LATE, ServiceText.of(hasNetwork = true, reliable = true, delayed = true))
    }
}
