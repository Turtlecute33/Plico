package app.plico.account

import app.plico.PlicoApp
import app.plico.R
import app.plico.data.Account
import app.plico.data.AuthKind
import app.plico.data.Provider
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap

private val cache = CredentialsCache()

/**
 * The [Credentials] of a stored [account]. Instances are cached per account id, so that an access token
 * outlives one connection. A change of provider, address, sign-in method or device account gives a new
 * instance. Secrets are read from the Vault for each use.
 */
fun credentialsFor(app: PlicoApp, account: Account): Credentials = cache.get(account) {
    val secret: suspend () -> String = { storedSecret(app, account.id) }
    when (account.auth) {
        AuthKind.PASSWORD -> PasswordCredentials(account.provider, account.email, secret)
        AuthKind.OAUTH -> {
            // Only Gmail accounts sign in with OAuth so far.
            val issuer = OAuthIssuer.of(account.provider) ?: OAuthIssuer.GOOGLE
            OAuthCredentials(account.email, secret, BrowserOAuth(app, issuer), hosts = issuer.hosts)
        }
        AuthKind.DEVICE -> DeviceCredentials(app, account.deviceAccount ?: account.email)
    }
}

/** Drops the cached credentials of [accountId]: after "Sign in again" and after account removal. */
fun forgetCredentials(accountId: Long) = cache.remove(accountId)

private fun storedSecret(app: PlicoApp, accountId: Long): String {
    val plain = try {
        app.accounts.secret(accountId)?.let(app.vault::decryptString)
    } catch (_: GeneralSecurityException) {
        null
    }
    return plain ?: throw MailError.AuthRequired(app.getString(R.string.account_error_no_secret))
}

/** Per-account cache; an entry is replaced when the sign-in fields of the account change. */
internal class CredentialsCache {
    private data class Key(val provider: Provider, val email: String, val auth: AuthKind, val deviceAccount: String?)

    private class Entry(val key: Key, val credentials: Credentials)

    private val entries = ConcurrentHashMap<Long, Entry>()

    fun get(account: Account, create: () -> Credentials): Credentials {
        val key = Key(account.provider, account.email, account.auth, account.deviceAccount)
        val entry = entries.compute(account.id) { _, old -> if (old?.key == key) old else Entry(key, create()) }
        return entry!!.credentials
    }

    fun remove(accountId: Long) {
        entries.remove(accountId)
    }
}
