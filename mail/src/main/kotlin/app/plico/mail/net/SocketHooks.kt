package app.plico.mail.net

import java.net.Socket
import javax.net.ssl.SSLSession

/** Platform hooks that the Android app supplies. */
interface SocketHooks {
    /** Called after the TCP connect and before the TLS handshake. */
    fun onConnected(socket: Socket) {}

    /** Explicit hostname check after the handshake. Must return true, or the connection fails. */
    fun verifyHostname(host: String, session: SSLSession): Boolean
}
