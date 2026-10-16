package app.plico.push.policy

import app.plico.push.policy.NetworkKey.Transport
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NetworkKeyTest {
    private fun ips(vararg literals: String) = literals.map { InetAddress.getByName(it) }

    @Test
    fun cellularAndOtherIgnoreDns() {
        assertEquals("cellular", NetworkKey.of(Transport.CELLULAR, ips("10.0.0.1")))
        assertEquals("cellular", NetworkKey.of(Transport.CELLULAR))
        assertEquals("other", NetworkKey.of(Transport.OTHER, ips("1.1.1.1")))
    }

    @Test
    fun wifiKeyIsAShortHashOfTheDnsServers() {
        val key = NetworkKey.of(Transport.WIFI, ips("192.168.1.1", "2001:db8::53"))
        assertTrue(Regex("wifi:[0-9a-f]{12}").matches(key), key)
        assertEquals(key, NetworkKey.of(Transport.WIFI, ips("2001:db8::53", "192.168.1.1", "192.168.1.1")))
        assertNotEquals(key, NetworkKey.of(Transport.WIFI, ips("192.168.1.1")))
        assertNotEquals(key, NetworkKey.of(Transport.WIFI, ips("192.168.0.1", "2001:db8::53")))
    }

    @Test
    fun wifiKeyIsStableAndContainsNoAddress() {
        val key = NetworkKey.of(Transport.WIFI, ips("192.168.1.1"))
        assertEquals(key, NetworkKey.of(Transport.WIFI, ips("192.168.1.1")))
        assertFalse("192.168" in key)
        assertTrue(Regex("wifi:[0-9a-f]{12}").matches(NetworkKey.of(Transport.WIFI)))
    }

    @Test
    fun ipv6ScopeDoesNotChangeTheKey() {
        assertEquals(
            NetworkKey.of(Transport.WIFI, ips("fe80::1")),
            NetworkKey.of(Transport.WIFI, ips("fe80::1%1")),
        )
    }

    @Test
    fun keysAreValidLearnedIntervalKeys() {
        val learned = LearnedIntervals(MemoryStore())
        for (key in listOf(NetworkKey.of(Transport.WIFI, ips("9.9.9.9")), NetworkKey.CELLULAR, NetworkKey.OTHER)) {
            learned["gmail|$key"] = LearnedInterval(9)
        }
    }
}
