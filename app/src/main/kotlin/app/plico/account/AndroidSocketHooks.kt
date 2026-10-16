package app.plico.account

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.plico.mail.net.SocketHooks
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession

/** Android side of the `:mail` socket hooks (DESIGN.md §4.1, §8.3). */
object AndroidSocketHooks : SocketHooks {
    private const val TCP_USER_TIMEOUT_MS = 30_000

    /** Sets TCP_USER_TIMEOUT, so that unacknowledged data ends a dead connection after 30 s. */
    override fun onConnected(socket: Socket) {
        try {
            // fromSocket() returns a dup of the descriptor; closing it leaves the socket open.
            ParcelFileDescriptor.fromSocket(socket).use {
                Os.setsockoptInt(
                    it.fileDescriptor,
                    OsConstants.IPPROTO_TCP,
                    OsConstants.TCP_USER_TIMEOUT,
                    TCP_USER_TIMEOUT_MS,
                )
            }
        } catch (_: Exception) {
            // Only a tuning: the connection works without it.
        }
    }

    override fun verifyHostname(host: String, session: SSLSession): Boolean =
        HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
}
