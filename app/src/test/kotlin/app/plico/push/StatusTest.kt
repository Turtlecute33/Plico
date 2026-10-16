package app.plico.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusTest {
    private val ok = Grants(
        notifications = true,
        batteryUnrestricted = true,
        exactAlarms = false,
        backgroundRestricted = false,
    )

    @Test
    fun reliableWithEitherGrant() {
        assertTrue(ok.reliable)
        assertTrue(ok.copy(batteryUnrestricted = false, exactAlarms = true).reliable)
    }

    @Test
    fun withoutBothGrantsPushMayStop() {
        assertFalse(ok.copy(batteryUnrestricted = false).reliable)
    }

    @Test
    fun restrictedBatteryTurnsPushOffWhateverTheGrants() {
        assertFalse(ok.copy(backgroundRestricted = true, exactAlarms = true).reliable)
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
