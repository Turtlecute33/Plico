package app.plico.account

import java.net.Socket
import kotlin.test.Test

class AndroidSocketHooksTest {
    @Test
    fun `socket tuning failures are ignored`() {
        // On the JVM the platform calls are stubs; on a device an unsupported option fails the same way.
        Socket().use { AndroidSocketHooks.onConnected(it) }
    }
}
