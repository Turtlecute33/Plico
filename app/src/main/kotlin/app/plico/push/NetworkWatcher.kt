package app.plico.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import app.plico.push.policy.NetworkKey
import app.plico.push.policy.NetworkRules
import app.plico.sync.NetState
import app.plico.sync.WakeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * The default network and the charging state (DESIGN §8.3 Network; push PB5, PB14, PB19). Every callback
 * holds [lock] for 30 s through a 2 s debounce; then [onChange] gets the new state and, when the sessions
 * must act, a [NetworkRules.Event]. A blocked default network counts as no network.
 */
internal class NetworkWatcher(
    private val context: Context,
    private val lock: WakeHolder,
    private val scope: CoroutineScope,
    private val onChange: suspend (NetworkRules.Event?, NetState?) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow<NetState?>(null)

    // Raw callback data, guarded by this.
    private var network: Network? = null
    private var link: Link? = null
    private var dns: List<InetAddress> = emptyList()
    private var blocked = false
    private var charging = false
    private var lastHandle: Long? = null
    private var generation = 0
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var powerReceiver: BroadcastReceiver? = null

    /** The debounced state while started; a direct reading while stopped. */
    val current: StateFlow<NetState?> = state.asStateFlow()

    fun network(): NetState? = if (synchronized(this) { callback != null }) state.value else snapshot()

    @Synchronized
    fun start() {
        if (callback != null) return
        charging = context.getSystemService(BatteryManager::class.java).isCharging
        network = connectivity.activeNetwork
        link = network?.let { connectivity.getNetworkCapabilities(it) }?.let(Link::of)
        dns = network?.let { connectivity.getLinkProperties(it) }?.dnsServers.orEmpty()
        blocked = false
        state.value = build()
        lastHandle = state.value?.handle

        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = update {
                val now = intent.action == Intent.ACTION_POWER_CONNECTED
                (now != charging).also { charging = now }
            }
        }
        val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED).apply { addAction(Intent.ACTION_POWER_DISCONNECTED) }
        context.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update {
                (network != this@NetworkWatcher.network).also { if (it) setNetwork(network) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update {
                // Signal-strength updates arrive here too; only the fields push uses count.
                val next = Link.of(caps)
                (next != link).also { link = next }
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update {
                val next = properties.dnsServers
                (next != dns).also { dns = next }
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = update {
                (blocked != this@NetworkWatcher.blocked).also { this@NetworkWatcher.blocked = blocked }
            }

            override fun onLost(network: Network) = update {
                (network == this@NetworkWatcher.network).also { if (it) setNetwork(null) }
            }
        }.also(connectivity::registerDefaultNetworkCallback)
    }

    @Synchronized
    fun stop() {
        callback?.let(connectivity::unregisterNetworkCallback)
        powerReceiver?.let(context::unregisterReceiver)
        callback = null
        powerReceiver = null
        generation++
        lock.release()
    }

    private fun setNetwork(next: Network?) {
        network = next
        link = null
        dns = emptyList()
        blocked = false
    }

    /**
     * Applies a callback under the lock; when it changed something, (re)starts the debounce. An older debounce
     * ends by itself when it sees a newer generation (it is never cancelled while it reports a change).
     */
    private fun update(change: () -> Boolean) = synchronized(this) {
        if (callback == null || !change()) return@synchronized
        lock.renew(LOCK_MS)
        val mine = ++generation
        scope.launch {
            delay(DEBOUNCE_MS)
            val (event, next) = synchronized(this@NetworkWatcher) {
                if (mine != generation) return@launch
                val next = build()
                val event = NetEvents.next(state.value, lastHandle, next)
                next?.let { lastHandle = it.handle }
                val changed = event != null || next != state.value
                state.value = next
                if (!changed) return@launch finish(mine, tail = false)
                event to next
            }
            try {
                onChange(event, next)
            } finally {
                finish(mine, tail = true)
            }
        }
    }

    /** Ends the lock of debounce [mine] unless a newer callback holds it; a [tail] lets the sessions lock first. */
    private fun finish(mine: Int, tail: Boolean) = synchronized(this) {
        if (mine != generation) return@synchronized
        if (tail) lock.renew(TAIL_MS) else lock.release()
    }

    private fun build(): NetState? = if (blocked) null else stateOf(network, link, dns, charging)

    // activeNetwork is null while the default network is blocked for this app.
    private fun snapshot(): NetState? {
        val n = connectivity.activeNetwork
        return stateOf(
            n,
            n?.let(connectivity::getNetworkCapabilities)?.let(Link::of),
            n?.let(connectivity::getLinkProperties)?.dnsServers.orEmpty(),
            context.getSystemService(BatteryManager::class.java).isCharging,
        )
    }

    private fun stateOf(n: Network?, l: Link?, dns: List<InetAddress>, charging: Boolean): NetState? {
        if (n == null || l == null) return null
        val wifi = l.transport == NetworkKey.Transport.WIFI
        return NetState(
            handle = n.networkHandle,
            key = NetworkKey.of(l.transport, if (wifi) dns else emptyList()),
            validated = l.validated,
            unmeteredWifi = wifi && l.notMetered,
            charging = charging,
        )
    }

    /** The capability fields push uses. */
    private data class Link(val transport: NetworkKey.Transport, val validated: Boolean, val notMetered: Boolean) {
        companion object {
            fun of(caps: NetworkCapabilities) = Link(
                transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKey.Transport.CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKey.Transport.WIFI
                    else -> NetworkKey.Transport.OTHER
                },
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        }
    }

    private companion object {
        const val LOCK_MS = 30_000L
        const val DEBOUNCE_MS = 2_000L
        const val TAIL_MS = 5_000L
    }
}

/** The session event for a change of the debounced network state (push PB5). Pure. */
internal object NetEvents {
    /**
     * [previous]: the last state (null = no usable network). [lastHandle]: the handle of the last usable
     * network. A network that comes back with the same handle was unblocked, which counts as VALIDATED.
     */
    fun next(previous: NetState?, lastHandle: Long?, next: NetState?): NetworkRules.Event? = when {
        next == null -> if (previous != null) NetworkRules.Event.LOST else null
        next.handle != lastHandle -> NetworkRules.Event.DEFAULT_CHANGED
        previous == null -> NetworkRules.Event.VALIDATED
        !previous.validated && next.validated -> NetworkRules.Event.VALIDATED
        else -> null
    }
}
