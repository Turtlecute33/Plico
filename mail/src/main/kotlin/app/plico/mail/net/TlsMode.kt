package app.plico.mail.net

/** How a connection starts TLS. There is no cleartext mode: STARTTLS is always required, never optional. */
enum class TlsMode { IMPLICIT, STARTTLS }
