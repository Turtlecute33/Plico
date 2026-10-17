package app.plico.mail.net

/** One mail server. [host] is ASCII, lower case and has no trailing dot. */
data class Endpoint(val host: String, val port: Int, val tls: TlsMode)
