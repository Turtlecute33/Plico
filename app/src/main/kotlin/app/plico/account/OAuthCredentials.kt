package app.plico.account

import android.os.SystemClock
import app.plico.mail.imap.ImapClient
import app.plico.mail.smtp.SmtpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An OAuth access token. [expiresAt] is SystemClock.elapsedRealtime() at expiry. toString() hides the value. */
class AccessToken(val value: String, val expiresAt: Long) {
    override fun toString() = "AccessToken(expiresAt=$expiresAt)"
}

/** The answer to a refresh: [refreshToken] is the new refresh token when the issuer rotated it, else null. */
class Refreshed(val accessToken: AccessToken, val refreshToken: String?) {
    override fun toString() = "Refreshed(rotated=${refreshToken != null})"
}

/** The token endpoint of an OAuth issuer as [OAuthCredentials] uses it; [BrowserOAuth] is the real one. */
interface TokenEndpoint {
    /** Throws [MailError.AuthRequired] for invalid_grant and [MailError.Setup] for a rejected client. */
    suspend fun refresh(refreshToken: String): AccessToken

    /** [refresh] that also gives the refresh token which the issuer sent in place of the old one (Microsoft). */
    suspend fun refreshGrant(refreshToken: String): Refreshed = Refreshed(refresh(refreshToken), null)

    suspend fun revoke(token: String)
}

/**
 * XOAUTH2 with a browser-OAuth grant of an issuer (DESIGN.md §6, push PB17). The refresh token stays in the Vault
 * and is read only to refresh; the access token lives in memory. Gmail ends an OAuth IMAP session when its token
 * expires, so every IMAP login uses a token with at least 45 min left.
 *
 * Tokens go only to [hosts]: any other server gets [MailError.Setup] before a token is read or refreshed (empty
 * [hosts] refuse every server). A refresh token that the issuer rotates is written through [onRotated] before the
 * new access token is used.
 *
 * [user] is the XOAUTH2 user: the account address, or the Microsoft sign-in name when it differs.
 *
 * @param onRotated writes a new refresh token that the issuer sent in place of the old one.
 * @param hosts the only mail servers that get a token (the hosts of the issuer).
 * @param smtpOff the text for an account whose provider does not let apps send mail; null for Google. With it,
 *   a refused SMTP token is refreshed and tried once more; a second refusal, or a refusal that names SMTP AUTH
 *   as off, gives [MailError.Setup] with this text. So only the send fails, never IMAP.
 */
class OAuthCredentials(
    private val user: String,
    private val refreshToken: suspend () -> String,
    private val endpoint: TokenEndpoint,
    initial: AccessToken? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val onRotated: suspend (old: String, new: String) -> Unit = { _, _ -> },
    private val hosts: Set<String> = emptySet(),
    private val smtpOff: String? = null,
) : Credentials {
    private val mutex = Mutex()

    @Volatile
    private var token: AccessToken? = initial

    /** The token of the last IMAP login: it decides when that session ends. */
    @Volatile
    private var session: AccessToken? = null

    override val expiresAt: Long? get() = session?.let { it.expiresAt - EXPIRY_MARGIN_MS }

    override suspend fun authImap(client: ImapClient) = withContext(Dispatchers.IO) {
        checkHost(client.host)
        val t = token(IMAP_MIN_LEFT_MS)
        login(t) { client.authenticateXoauth2(user, t.value) }
        session = t
    }

    override suspend fun authSmtp(client: SmtpClient) = withContext(Dispatchers.IO) {
        checkHost(client.host)
        val off = smtpOff
        if (off == null) {
            smtpLogin(client)
            return@withContext
        }
        try {
            try {
                smtpLogin(client)
            } catch (e: MailError.AuthRequired) {
                // A failed refresh needs a sign-in. A refused token was dropped: one refresh, one more try.
                if (!MailErrors.hasSmtpCause(e)) throw e
                smtpLogin(client)
            }
        } catch (e: MailError.AuthRequired) {
            throw if (MailErrors.hasSmtpCause(e)) MailError.Setup(off, e) else e
        } catch (e: MailError.Setup) {
            throw if (MailErrors.hasSmtpCause(e)) MailError.Setup(off, e) else e
        }
    }

    override suspend fun invalidate() {
        token = null
    }

    override suspend fun revoke() {
        token = null
        try {
            withContext(Dispatchers.IO) { endpoint.revoke(refreshToken()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the account is removed either way.
        }
    }

    private suspend fun smtpLogin(client: SmtpClient) {
        val t = token(SMTP_MIN_LEFT_MS)
        login(t) { client.authXoauth2(user, t.value) }
    }

    /** Before any token is read: only the hosts of the issuer get one. */
    private fun checkHost(host: String) {
        if (host !in hosts) throw wrongHost()
    }

    private suspend fun token(minLeft: Long): AccessToken = mutex.withLock {
        token?.takeIf { it.expiresAt - clock() >= minLeft } ?: refreshed().also { token = it }
    }

    /** A new access token; a rotated refresh token is stored first. */
    private suspend fun refreshed(): AccessToken {
        val old = refreshToken()
        val result = endpoint.refreshGrant(old)
        val new = result.refreshToken
        if (!new.isNullOrEmpty() && new != old) onRotated(old, new)
        return result.accessToken
    }

    /** A refused token is dropped, so that the next login refreshes. */
    private inline fun login(t: AccessToken, block: () -> Unit) {
        try {
            authenticating(block)
        } catch (e: MailError.AuthRequired) {
            if (token === t) token = null
            throw e
        }
    }

    private companion object {
        const val IMAP_MIN_LEFT_MS = 45 * 60_000L
        const val SMTP_MIN_LEFT_MS = 5 * 60_000L
        const val EXPIRY_MARGIN_MS = 5 * 60_000L
    }
}
