package app.plico.ui.setup

import app.plico.push.Grants
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrantsTest {
    private fun grants(notifications: Boolean, battery: Boolean, exactAlarms: Boolean) =
        Grants(notifications, battery, exactAlarms, backgroundRestricted = false)

    @Test
    fun `complete needs notifications and the exemption or exact alarms`() {
        assertTrue(grants(notifications = true, battery = true, exactAlarms = false).complete)
        assertTrue(grants(notifications = true, battery = false, exactAlarms = true).complete)
        assertFalse(grants(notifications = true, battery = false, exactAlarms = false).complete)
        assertFalse(grants(notifications = false, battery = true, exactAlarms = true).complete)
    }

    @Test
    fun `exact alarms are offered only as the fallback`() {
        val none = grants(notifications = true, battery = false, exactAlarms = false)
        assertFalse(none.offersExactAlarms(batteryRefused = false))
        assertTrue(none.offersExactAlarms(batteryRefused = true))
        assertFalse(none.copy(batteryUnrestricted = true).offersExactAlarms(batteryRefused = true))
        // Granted already: shown as done.
        assertTrue(none.copy(exactAlarms = true).offersExactAlarms(batteryRefused = false))
    }
}
