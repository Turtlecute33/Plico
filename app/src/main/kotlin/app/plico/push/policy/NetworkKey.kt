package app.plico.push.policy

import java.net.InetAddress
import java.security.MessageDigest

/**
 * Names the network a keepalive interval was learned on (DESIGN.md §8.3, push PB19). No SSID, so no location
 * permission: every mobile network is `cellular`, Wi-Fi is `wifi:` + a short SHA-256 of its DNS servers.
 */
object NetworkKey {
    const val CELLULAR = "cellular"
    const val OTHER = "other"
    private const val WIFI_PREFIX = "wifi:"
    private const val HASH_BYTES = 6

    enum class Transport { CELLULAR, WIFI, OTHER }

    /**
     * [transport]: CELLULAR when the network has a cellular transport, else WIFI for Wi-Fi, else OTHER.
     * [dnsServers] are used for Wi-Fi only; order and duplicates do not matter.
     */
    fun of(transport: Transport, dnsServers: Collection<InetAddress> = emptyList()): String = when (transport) {
        Transport.CELLULAR -> CELLULAR
        Transport.OTHER -> OTHER
        Transport.WIFI -> WIFI_PREFIX + hash(dnsServers)
    }

    // Raw address bytes, so an IPv6 scope suffix (`%wlan0`) does not change the key.
    private fun hash(dnsServers: Collection<InetAddress>): String {
        val text = dnsServers.map { hex(it.address) }.distinct().sorted().joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))
        return hex(digest.copyOf(HASH_BYTES))
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
