package app.plico.push

import app.plico.push.policy.NetworkRules.Event
import app.plico.sync.NetState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetEventsTest {
    private fun net(handle: Long, validated: Boolean = true, unmetered: Boolean = false, charging: Boolean = false) =
        NetState(handle, "cellular", validated, unmetered, charging)

    @Test
    fun firstNetworkAndSwitchesAreNew() {
        assertEquals(Event.DEFAULT_CHANGED, NetEvents.next(null, null, net(1)))
        assertEquals(Event.DEFAULT_CHANGED, NetEvents.next(net(1), 1, net(2)))
        // Back after a gap, but on another network.
        assertEquals(Event.DEFAULT_CHANGED, NetEvents.next(null, 1, net(2)))
    }

    @Test
    fun lossParksOnce() {
        assertEquals(Event.LOST, NetEvents.next(net(1), 1, null))
        assertNull(NetEvents.next(null, 1, null))
    }

    @Test
    fun validationOrUnblockingOnTheSameNetwork() {
        assertEquals(Event.VALIDATED, NetEvents.next(net(1, validated = false), 1, net(1)))
        // Blocked counts as no network; the same handle back = unblocked.
        assertEquals(Event.VALIDATED, NetEvents.next(null, 1, net(1)))
        assertNull(NetEvents.next(net(1), 1, net(1, validated = false)))
    }

    @Test
    fun otherChangesNeedNoSessionEvent() {
        assertNull(NetEvents.next(net(1), 1, net(1, unmetered = true, charging = true)))
    }
}
