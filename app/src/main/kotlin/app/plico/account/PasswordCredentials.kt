package app.plico.account

import app.plico.data.Provider
import app.plico.mail.imap.ImapClient
import app.plico.mail.smtp.SmtpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Password sign-in with the account password or an app password. [password] is read for every login, so the
 * plain text stays in memory only for the exchange.
 */
class PasswordCredentials(
    private val provider: Provider,
    private val user: String,
    private val password: suspend () -> String,
) : Credentials {
    /** For setup's connection test, before the password is stored. */
    constructor(provider: Provider, user: String, password: String) : this(provider, user, { password })

    override val expiresAt: Long? get() = null

    override suspend fun authImap(client: ImapClient) = withContext(Dispatchers.IO) {
        val secret = normalize(provider, password())
        authenticating { client.authenticatePassword(user, secret) }
    }

    override suspend fun authSmtp(client: SmtpClient) = withContext(Dispatchers.IO) {
        val secret = normalize(provider, password())
        authenticating { client.authPassword(user, secret) }
    }

    override suspend fun invalidate() {}

    companion object {
        /** Gmail shows app passwords in groups of four ("abcd efgh ijkl mnop"); Riseup passwords stay as typed. */
        fun normalize(provider: Provider, password: String): String =
            if (provider == Provider.GMAIL) password.filterNot(Char::isWhitespace) else password
    }
}
