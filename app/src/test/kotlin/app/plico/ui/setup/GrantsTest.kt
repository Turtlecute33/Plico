package app.plico.ui.setup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrantsTest {
    @Test
    fun `complete needs notifications and the exemption or exact alarms`() {
        assertTrue(Grants(notifications = true, battery = true, exactAlarms = false).complete)
        assertTrue(Grants(notifications = true, battery = false, exactAlarms = true).complete)
        assertFalse(Grants(notifications = true, battery = false, exactAlarms = false).complete)
        assertFalse(Grants(notifications = false, battery = true, exactAlarms = true).complete)
    }

    @Test
    fun `exact alarms are offered only as the fallback`() {
        val none = Grants(notifications = true, battery = false, exactAlarms = false)
        assertFalse(none.offersExactAlarms(batteryRefused = false))
        assertTrue(none.offersExactAlarms(batteryRefused = true))
        assertFalse(none.copy(battery = true).offersExactAlarms(batteryRefused = true))
        // Granted already: shown as done.
        assertTrue(none.copy(exactAlarms = true).offersExactAlarms(batteryRefused = false))
    }
}
