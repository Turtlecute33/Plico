package app.plico.mail.net

import java.io.IOException

/** STARTTLS refused, a PREAUTH greeting before TLS, or bytes after the STARTTLS reply. Never retried as plaintext. */
class StartTlsException(message: String) : IOException(message)
