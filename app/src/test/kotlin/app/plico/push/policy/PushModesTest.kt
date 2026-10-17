package app.plico.push.policy

import app.plico.data.PushMode
import app.plico.push.policy.PushModes.Effective
import kotlin.test.Test
import kotlin.test.assertEquals

class PushModesTest {
    @Test
    fun theUserModeDecidesOnAServerWithIdle() {
        for (wifi in listOf(true, false)) for (charging in listOf(true, false)) {
            assertEquals(Effective.IDLE, PushModes.effective(PushMode.INSTANT, idle = true, wifi, charging))
            assertEquals(Effective.POLL, PushModes.effective(PushMode.POLL, idle = true, wifi, charging))
        }
        val onWifi = PushMode.INSTANT_ON_WIFI
        assertEquals(Effective.IDLE, PushModes.effective(onWifi, idle = true, unmeteredWifi = true, charging = false))
        assertEquals(Effective.IDLE, PushModes.effective(onWifi, idle = true, unmeteredWifi = false, charging = true))
        assertEquals(Effective.POLL, PushModes.effective(onWifi, idle = true, unmeteredWifi = false, charging = false))
    }

    @Test
    fun aServerWithoutIdleAlwaysPolls() {
        for (mode in PushMode.entries) for (wifi in listOf(true, false)) for (charging in listOf(true, false)) {
            assertEquals(Effective.POLL, PushModes.effective(mode, idle = false, wifi, charging), "$mode $wifi $charging")
        }
    }
}
