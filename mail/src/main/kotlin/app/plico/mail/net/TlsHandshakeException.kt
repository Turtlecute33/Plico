package app.plico.mail.net

import java.io.IOException
import javax.net.ssl.SSLException

/** The TLS handshake failed for a reason that is not the certificate (e.g. a plaintext server on this port). */
class TlsHandshakeException(cause: SSLException) : IOException(cause.message, cause)
