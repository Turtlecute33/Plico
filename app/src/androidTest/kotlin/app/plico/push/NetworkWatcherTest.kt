package app.plico.push

import androidx.test.platform.app.InstrumentationRegistry
import app.plico.push.policy.NetworkRules
import app.plico.sync.NetState
import app.plico.sync.WakeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The network watcher on the device (push PB5). The emulator must have a network. */
class NetworkWatcherTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = LinkedBlockingQueue<Pair<NetworkRules.Event?, NetState?>>()
    private val watcher = NetworkWatcher(context, NoLock, scope) { event, net -> changes.put(event to net) }

    @After
    fun stop() {
        watcher.stop()
        scope.cancel()
    }

    // The 30-min backstop wake: a callback that never came must not leave push without network.
    @Test
    fun recheckFindsANetworkThatACallbackMissed() {
        watcher.start()
        val known = assertNotNull(watcher.network(), "the emulator has no network")
        Thread.sleep(3_000) // the callbacks of the registration and their debounce
        changes.clear()

        missCallbacks()
        assertNull(watcher.network())
        watcher.recheck()

        val (event, net) = assertNotNull(changes.poll(10, TimeUnit.SECONDS), "no change after the recheck")
        assertEquals(NetworkRules.Event.VALIDATED, event)
        assertEquals(known.handle, net?.handle)
        assertEquals(net, watcher.network())

        // A second recheck finds nothing new.
        watcher.recheck()
        assertNull(changes.poll(4, TimeUnit.SECONDS))
    }

    /** The debounced state says "no network", as after a lost onAvailable. */
    @Suppress("UNCHECKED_CAST")
    private fun missCallbacks() {
        val field = NetworkWatcher::class.java.getDeclaredField("state").apply { isAccessible = true }
        (field.get(watcher) as MutableStateFlow<NetState?>).value = null
    }

    private object NoLock : WakeHolder {
        override fun renew(timeoutMs: Long) = Unit
        override fun release() = Unit
    }
}
