package app.plico.mail.net

import java.net.SocketTimeoutException

/**
 * STARTTLS mode: the plaintext greeting, or a reply before the switch to TLS, did not come in time. Often the port
 * is one for implicit TLS (993, 465), where the server waits for the TLS handshake of the client. It is a
 * [SocketTimeoutException], so every caller handles it as a read timeout; only setup reads it as a wrong security
 * setting. Timeouts after the switch stay plain [SocketTimeoutException]s.
 */
class PlaintextTimeoutException(cause: SocketTimeoutException) : SocketTimeoutException("No reply before TLS") {
    init {
        initCause(cause)
    }
}

/** Runs [block], a read before TLS in STARTTLS mode: a read timeout becomes [PlaintextTimeoutException]. */
internal inline fun <T> beforeTls(block: () -> T): T = try {
    block()
} catch (e: PlaintextTimeoutException) {
    throw e
} catch (e: SocketTimeoutException) {
    throw PlaintextTimeoutException(e)
}
