package app.plico.account

import android.content.Intent
import app.plico.mail.imap.ImapClient
import app.plico.mail.smtp.SmtpClient

/** How one account signs in. Implementations: password, browser OAuth, device account. See DESIGN.md §6. */
interface Credentials {
    /** Authenticates [client] (PLAIN or XOAUTH2). Throws [MailError.AuthRequired] when the user must act. */
    suspend fun authImap(client: ImapClient)

    suspend fun authSmtp(client: SmtpClient)

    /** SystemClock.elapsedRealtime() when the IMAP session must be renewed (OAuth/device), else null. */
    val expiresAt: Long?

    /** Drops a cached access token after an auth failure. */
    suspend fun invalidate()

    /**
     * Before account removal: ends the grant on the provider side (OAuth: POST /revoke; device account:
     * drops the token from AccountManager). Best effort; network failures are ignored.
     */
    suspend fun revoke() {}
}

/** App-level error classes (DESIGN.md §14). */
sealed class MailError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The user must sign in again. [intent] is a consent screen from AccountManager, if any. */
    class AuthRequired(message: String, val intent: Intent? = null, cause: Throwable? = null) : MailError(message, cause)

    /** Certificate or hostname failure. */
    class Tls(cause: Throwable) : MailError("TLS failure", cause)

    class Network(cause: Throwable) : MailError("Network failure", cause)

    /** Server asks to slow down (SMTP 4xx, [UNAVAILABLE], too many connections). */
    class Throttled(message: String, cause: Throwable? = null) : MailError(message, cause)

    /** Permanent server refusal with the server text. */
    class Server(message: String, cause: Throwable? = null) : MailError(message, cause)

    class TooLarge(val limit: Long) : MailError("Message too large")

    /** Account setup problem the user must fix (e.g. Gmail All Mail hidden from IMAP, client not registered). */
    class Setup(message: String) : MailError(message)
}
