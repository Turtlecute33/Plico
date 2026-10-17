package app.plico.account

import android.content.Context
import android.os.SystemClock
import app.plico.mail.imap.ImapClient
import app.plico.mail.smtp.SmtpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gmail XOAUTH2 with a token of the Google account on the phone (DESIGN.md §6, apis AF5, push PB17). The token
 * goes only to Google's mail servers.
 *
 * AccountManager gives no expiry and may return a cached token that is almost 1 h old. So the acquired
 * time is kept in memory: when it is unknown or older than 45 min, the cached token is invalidated and a
 * new one is fetched, at most once per 10 min. A token fetched after an invalidation is newer than that
 * invalidation, which gives a safe acquired time without a second invalidation.
 */
class DeviceCredentials internal constructor(
    private val user: String,
    private val fetch: suspend () -> String,
    private val clear: suspend (String) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : Credentials {
    /** [accountName] is the `com.google` account the user picked; it is also the XOAUTH2 user. */
    constructor(context: Context, accountName: String) : this(
        user = accountName,
        fetch = { DeviceGoogleAuth.backgroundToken(context, accountName) },
        clear = { DeviceGoogleAuth.clearToken(context, it) },
    )

    private val mutex = Mutex()
    private var token: String? = null

    /** Earliest possible mint time of [token]; null = unknown. */
    private var acquired: Long? = null

    /** Last invalidation in AccountManager. */
    private var cleared: Long? = null

    /** Acquired time of the token of the last IMAP login. */
    @Volatile
    private var session: Long? = null

    override val expiresAt: Long? get() = session?.let { it + SESSION_MS }

    override suspend fun authImap(client: ImapClient) = withContext(Dispatchers.IO) {
        checkHost(client.host)
        val (value, acquiredAt) = token()
        login { client.authenticateXoauth2(user, value) }
        session = acquiredAt
    }

    override suspend fun authSmtp(client: SmtpClient) = withContext(Dispatchers.IO) {
        checkHost(client.host)
        val (value, _) = token()
        login { client.authXoauth2(user, value) }
    }

    /** The next login gets a new token (subject to the 10-min limit). */
    override suspend fun invalidate() {
        mutex.withLock { acquired = null }
    }

    override suspend fun revoke() {
        val current = mutex.withLock {
            acquired = null
            token.also { token = null }
        } ?: return
        try {
            clear(current)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the account is removed either way.
        }
    }

    private suspend fun token(): Pair<String, Long> = mutex.withLock {
        val now = clock()
        val current = token
        val at = acquired
        if (current != null && at != null && now - at <= MAX_AGE_MS) return@withLock current to at

        val lastClear = cleared
        if (lastClear != null && now - lastClear < MIN_CLEAR_INTERVAL_MS) {
            val fresh = fetch()
            token = fresh
            acquired = lastClear
            return@withLock fresh to lastClear
        }
        clear(current ?: fetch())
        cleared = now
        val fresh = fetch()
        token = fresh
        acquired = now
        fresh to now
    }

    /** A Google token goes only to Google's servers; checked before a token is fetched. */
    private fun checkHost(host: String) {
        if (host !in OAuthIssuer.GOOGLE.hosts) throw wrongHost()
    }

    private suspend inline fun login(block: () -> Unit) {
        try {
            authenticating(block)
        } catch (e: MailError.AuthRequired) {
            invalidate()
            throw e
        }
    }

    private companion object {
        const val MAX_AGE_MS = 45 * 60_000L
        const val MIN_CLEAR_INTERVAL_MS = 10 * 60_000L
        const val SESSION_MS = 55 * 60_000L
    }
}
