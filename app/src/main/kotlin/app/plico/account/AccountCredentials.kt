package app.plico.account

import app.plico.PlicoApp
import app.plico.R
import app.plico.data.Account
import app.plico.data.AuthKind
import app.plico.data.Provider
import app.plico.data.Tables
import app.plico.mail.imap.ImapClient
import app.plico.mail.smtp.SmtpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap

private val cache = CredentialsCache()

/**
 * The [Credentials] of a stored [account]. Instances are cached per account id, so that an access token
 * outlives one connection. A change of provider, address, user name, sign-in method or device account gives a
 * new instance. Secrets are read from the Vault for each use.
 *
 * A sign-in method that does not fit the provider (OAuth on a server without an issuer, a device account outside
 * Gmail) gives credentials that refuse every login: a Google or Microsoft token never goes to another server.
 */
fun credentialsFor(app: PlicoApp, account: Account): Credentials = cache.get(account) {
    val secret: suspend () -> String = { storedSecret(app, account.id) }
    when (val kind = kindOf(account)) {
        CredentialsKind.Password -> PasswordCredentials(account.provider, account.login, secret)
        is CredentialsKind.OAuth -> OAuthCredentials(
            account.login, secret, BrowserOAuth(app, kind.issuer),
            onRotated = { old, new -> rotate(app, account.id, old, new) },
            hosts = kind.issuer.hosts,
            smtpOff = if (kind.issuer == OAuthIssuer.MICROSOFT) app.getString(R.string.account_note_smtp_off) else null,
        )
        CredentialsKind.Device -> DeviceCredentials(app, account.deviceAccount ?: account.email)
        CredentialsKind.Refused -> Refusing(app.getString(R.string.account_error_sign_in_kind))
    }
}

/** Drops the cached credentials of [accountId]: after "Sign in again" and after account removal. */
fun forgetCredentials(accountId: Long) = cache.remove(accountId)

/** Which [Credentials] an account gets. */
internal sealed interface CredentialsKind {
    data object Password : CredentialsKind

    data class OAuth(val issuer: OAuthIssuer) : CredentialsKind

    data object Device : CredentialsKind

    /** The sign-in method does not fit the provider. */
    data object Refused : CredentialsKind
}

/** OAuth only for a provider with an issuer; the device account only for Gmail. */
internal fun kindOf(account: Account): CredentialsKind = when (account.auth) {
    AuthKind.PASSWORD -> CredentialsKind.Password
    AuthKind.OAUTH -> OAuthIssuer.of(account.provider)?.let(CredentialsKind::OAuth) ?: CredentialsKind.Refused
    AuthKind.DEVICE -> if (account.provider == Provider.GMAIL) CredentialsKind.Device else CredentialsKind.Refused
}

/** Credentials that refuse every login with [message]; no secret is read. */
internal class Refusing(private val message: String) : Credentials {
    override val expiresAt: Long? get() = null

    override suspend fun authImap(client: ImapClient) = throw MailError.Setup(message)

    override suspend fun authSmtp(client: SmtpClient) = throw MailError.Setup(message)

    override suspend fun invalidate() {}
}

/**
 * Stores a rotated refresh token. In one transaction, and only when the row still signs in with OAuth and still
 * holds [old]: a "Sign in again" or another sign-in method while the refresh ran must win.
 */
private suspend fun rotate(app: PlicoApp, accountId: Long, old: String, new: String) = withContext(Dispatchers.IO) {
    app.db.tx(Tables.ACCOUNT) {
        val auth = app.accounts.get(accountId)?.auth
        val stored = decrypted(app, app.accounts.secret(accountId))
        if (mayRotate(auth, stored, old)) app.accounts.setSecret(accountId, app.vault.encryptString(new))
    }
}

/** True when a rotated token may replace the stored one: the row has OAUTH and its secret is still [old]. */
internal fun mayRotate(auth: AuthKind?, stored: String?, old: String): Boolean = auth == AuthKind.OAUTH && stored == old

private fun decrypted(app: PlicoApp, blob: ByteArray?): String? = try {
    blob?.let(app.vault::decryptString)
} catch (_: GeneralSecurityException) {
    null
}

private fun storedSecret(app: PlicoApp, accountId: Long): String =
    decrypted(app, app.accounts.secret(accountId))
        ?: throw MailError.AuthRequired(app.getString(R.string.account_error_no_secret))

/** Per-account cache; an entry is replaced when the sign-in fields of the account change. */
internal class CredentialsCache {
    private data class Key(
        val provider: Provider,
        val email: String,
        val login: String,
        val auth: AuthKind,
        val deviceAccount: String?,
    )

    private class Entry(val key: Key, val credentials: Credentials)

    private val entries = ConcurrentHashMap<Long, Entry>()

    fun get(account: Account, create: () -> Credentials): Credentials {
        val key = Key(account.provider, account.email, account.login, account.auth, account.deviceAccount)
        val entry = entries.compute(account.id) { _, old -> if (old?.key == key) old else Entry(key, create()) }
        return entry!!.credentials
    }

    fun remove(accountId: Long) {
        entries.remove(accountId)
    }
}
