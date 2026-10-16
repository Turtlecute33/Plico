package app.plico.account

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import app.plico.PlicoApp
import app.plico.R
import app.plico.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/** Result of a browser sign-in. [email] is the id_token `email` claim and the XOAUTH2 user. */
class SignIn(val email: String, val refreshToken: String, val accessToken: AccessToken) {
    override fun toString() = "SignIn"
}

/**
 * Browser OAuth for Gmail with the user's own client ID (DESIGN.md §6, privacy PS9/PS16, apis AF16):
 * PKCE S256, `state`, the default browser, redirect `<applicationId>:/oauth2redirect`.
 *
 * Flow: [start] stores the pending sign-in in Prefs and opens the browser. [OAuthRedirectActivity] calls
 * [onRedirect], which stores the code in that entry and opens MainActivity with [ACTION_DONE]. Setup then
 * calls [completePending]. The entry is used once and is valid for 10 min, also across process death.
 */
class GoogleOAuth(private val context: Context, private val prefs: Prefs) : TokenEndpoint {
    constructor(app: PlicoApp) : this(app, app.prefs)

    /** `<applicationId>:/oauth2redirect`. The manifest registers the scheme. */
    val redirectUri: String get() = redirectUri(context.packageName)

    /**
     * Opens Google's consent page in the default browser. [email] is the login hint; the user can still pick
     * another account, so the result's email comes from the id_token. Throws [MailError.Setup] without a client ID.
     */
    @SuppressLint("UseKtx") // androidx.core is for FileProvider only (DESIGN.md §2).
    fun start(activity: Activity, email: String) {
        val clientId = clientId()
        val verifier = randomToken()
        val state = randomToken()
        prefs.putString(PENDING_KEY, Pending(state, verifier, email, now()).toJson())
        val url = authUrl(clientId, redirectUri, state, challenge(verifier), email)
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            prefs.putString(PENDING_KEY, null)
            throw MailError.Setup(context.getString(R.string.account_error_no_browser))
        }
    }

    /**
     * Stores the code or error of a browser redirect in the pending entry. Only the URI is read, never extras.
     * Returns false when the redirect does not match the pending sign-in (path, state, age or a second use).
     */
    fun onRedirect(uri: Uri): Boolean {
        val ours = uri.scheme == context.packageName && uri.authority.isNullOrEmpty() && uri.path == REDIRECT_PATH
        if (!ours) return false
        val pending = Pending.parse(prefs.getString(PENDING_KEY)) ?: return false
        val done = pending.redirected(
            state = uri.getQueryParameter("state"),
            code = uri.getQueryParameter("code"),
            error = uri.getQueryParameter("error"),
            now = now(),
        ) ?: return false
        prefs.putString(PENDING_KEY, done.toJson())
        return true
    }

    /**
     * Exchanges the code that [onRedirect] stored. Returns null while no redirect has arrived. Throws
     * [MailError.AuthRequired] when the user declined, [MailError.Setup] for an expired sign-in, a rejected
     * client or a missing Gmail scope, and IOException for network failures.
     */
    suspend fun completePending(): SignIn? {
        val pending = Pending.parse(prefs.getString(PENDING_KEY)) ?: return null
        if (!pending.done) {
            if (!pending.isFresh(now())) prefs.putString(PENDING_KEY, null)
            return null
        }
        prefs.putString(PENDING_KEY, null)
        pending.error?.let { error ->
            throw if (error == "access_denied") {
                MailError.AuthRequired(context.getString(R.string.account_error_oauth_denied))
            } else {
                MailError.Setup(context.getString(R.string.account_error_oauth_failed, error))
            }
        }
        val code = pending.code
        if (code == null || !pending.isFresh(now())) {
            throw MailError.Setup(context.getString(R.string.account_error_oauth_expired))
        }

        val response = tokenRequest(
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to pending.verifier,
            "client_id" to clientId(),
            "redirect_uri" to redirectUri,
        )
        if (!grantsMail(response.scope)) throw MailError.Setup(context.getString(R.string.account_error_oauth_scope))
        val email = response.idToken?.let(::emailFromIdToken)
        val refreshToken = response.refreshToken
        if (email == null || refreshToken == null) {
            throw MailError.Server(context.getString(R.string.account_error_oauth_incomplete))
        }
        return SignIn(email, refreshToken, response.accessToken())
    }

    override suspend fun refresh(refreshToken: String): AccessToken =
        tokenRequest(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId(),
        ).accessToken()

    /** Revokes the grant of the whole client (all tokens). The token goes in the body, never in the URL. */
    override suspend fun revoke(token: String) {
        val (status, body) = post(REVOKE_URL, listOf("token" to token))
        if (status !in 200..299 && parseError(body) != "invalid_token") throw IOException("Revoke failed: HTTP $status")
    }

    private fun clientId(): String = prefs.googleClientId.trim().ifEmpty {
        throw MailError.Setup(context.getString(R.string.account_error_no_client_id))
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun TokenResponse.accessToken() = AccessToken(accessToken, now() + expiresInS * 1000)

    private suspend fun tokenRequest(vararg form: Pair<String, String>): TokenResponse {
        val (status, body) = post(TOKEN_URL, form.toList())
        if (status in 200..299) return parseToken(body)
        throw when (failure(status, parseError(body))) {
            Failure.GRANT -> MailError.AuthRequired(context.getString(R.string.account_error_oauth_grant))
            Failure.CLIENT -> MailError.Setup(context.getString(R.string.account_error_oauth_client))
            Failure.TEMPORARY -> IOException("Token endpoint: HTTP $status")
        }
    }

    /** POSTs a form; returns the HTTP status and at most 64 KiB of the body. */
    private suspend fun post(url: String, form: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        val body = formEncode(form).toByteArray(Charsets.UTF_8)
        val connection = URI(url).toURL().openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
            // The platform default names the phone model and build.
            connection.setRequestProperty("User-Agent", "Plico")
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            status to (stream?.use { String(it.readNBytes(MAX_RESPONSE_BYTES), Charsets.UTF_8) } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    internal enum class Failure { GRANT, CLIENT, TEMPORARY }

    /** A token endpoint answer: [accessToken] lives [expiresInS] seconds. */
    internal class TokenResponse(
        val accessToken: String,
        val expiresInS: Long,
        val refreshToken: String?,
        val idToken: String?,
        val scope: String?,
    ) {
        override fun toString() = "TokenResponse(expiresInS=$expiresInS, scope=$scope)"
    }

    /** The sign-in that waits for the browser. [code] or [error] is set by the redirect. */
    internal data class Pending(
        val state: String,
        val verifier: String,
        val email: String,
        val createdAt: Long,
        val code: String? = null,
        val error: String? = null,
    ) {
        val done: Boolean get() = code != null || error != null

        /** [createdAt] and [now] are elapsedRealtime; a reboot makes the age negative, which is not fresh. */
        fun isFresh(now: Long) = now - createdAt in 0..MAX_AGE_MS

        /** The entry with the redirect's result, or null when the redirect does not belong to this entry. */
        fun redirected(state: String?, code: String?, error: String?, now: Long): Pending? = when {
            done || state != this.state || !isFresh(now) -> null
            !error.isNullOrEmpty() -> copy(error = error.take(MAX_PARAM_LENGTH))
            !code.isNullOrEmpty() && code.length <= MAX_PARAM_LENGTH -> copy(code = code)
            else -> null
        }

        fun toJson(): String = JSONObject()
            .put("state", state)
            .put("verifier", verifier)
            .put("email", email)
            .put("createdAt", createdAt)
            .putOpt("code", code)
            .putOpt("error", error)
            .toString()

        override fun toString() = "Pending(createdAt=$createdAt, done=$done)"

        companion object {
            fun parse(json: String?): Pending? {
                if (json == null) return null
                return try {
                    val o = JSONObject(json)
                    Pending(
                        state = o.getString("state"),
                        verifier = o.getString("verifier"),
                        email = o.getString("email"),
                        createdAt = o.getLong("createdAt"),
                        code = o.text("code"),
                        error = o.text("error"),
                    )
                } catch (_: JSONException) {
                    null
                }
            }
        }
    }

    companion object {
        /** Action of the MainActivity intent after a redirect; setup then calls [completePending]. */
        const val ACTION_DONE = "app.plico.action.OAUTH_DONE"

        internal const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        internal const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        internal const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        internal const val SCOPES = Providers.GMAIL_SCOPE + " openid email"
        internal const val PENDING_KEY = "oauth.pending"
        internal const val REDIRECT_PATH = "/oauth2redirect"
        internal const val MAX_AGE_MS = 10 * 60_000L
        private const val MAX_PARAM_LENGTH = 2048
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val TIMEOUT_MS = 20_000

        private val random = SecureRandom()

        internal fun redirectUri(applicationId: String) = "$applicationId:$REDIRECT_PATH"

        /** 32 random bytes, base64url without padding: a 43-character PKCE verifier (RFC 7636 §4.1) or state. */
        internal fun randomToken(): String =
            base64Url(ByteArray(32).also(random::nextBytes))

        /** S256 code challenge: BASE64URL(SHA-256(ASCII(verifier))) (RFC 7636 §4.2). */
        internal fun challenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        internal fun authUrl(
            clientId: String,
            redirectUri: String,
            state: String,
            challenge: String,
            email: String,
        ): String = AUTH_URL + "?" + formEncode(
                listOfNotNull(
                    "client_id" to clientId,
                    "redirect_uri" to redirectUri,
                    "response_type" to "code",
                    "scope" to SCOPES,
                    "state" to state,
                    "code_challenge" to challenge,
                    "code_challenge_method" to "S256",
                    email.takeIf { it.isNotBlank() }?.let { "login_hint" to it },
                    "access_type" to "offline",
                    "prompt" to "consent",
                ),
            )

        /** Parses a successful token response. Throws IOException when the access token is missing. */
        internal fun parseToken(json: String): TokenResponse = try {
            val o = JSONObject(json)
            TokenResponse(
                accessToken = o.text("access_token") ?: throw IOException("No access token"),
                expiresInS = o.optLong("expires_in", DEFAULT_EXPIRES_IN_S).coerceAtLeast(0),
                refreshToken = o.text("refresh_token"),
                idToken = o.text("id_token"),
                scope = o.text("scope"),
            )
        } catch (_: JSONException) {
            throw IOException("Malformed token response")
        }

        /** The OAuth `error` code of an error response, or null. */
        internal fun parseError(json: String?): String? = try {
            json?.let { JSONObject(it).text("error") }
        } catch (_: JSONException) {
            null
        }

        internal fun failure(status: Int, error: String?): Failure = when {
            error == "invalid_grant" -> Failure.GRANT
            error == "invalid_client" || error == "unauthorized_client" -> Failure.CLIENT
            status >= 500 || status == 408 || status == 429 -> Failure.TEMPORARY
            status in 400..499 -> Failure.CLIENT
            else -> Failure.TEMPORARY
        }

        /** Granular consent lets the user untick Gmail; then the grant is useless for IMAP. */
        internal fun grantsMail(scope: String?): Boolean = scope == null || Providers.GMAIL_SCOPE in scope.split(' ')

        /**
         * The `email` claim of an id_token. The token comes straight from Google's token endpoint over TLS,
         * so its signature is not checked (privacy PS16).
         */
        internal fun emailFromIdToken(idToken: String): String? = try {
            val payload = idToken.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
            JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).text("email")
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JSONException) {
            null
        }

        private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun formEncode(params: List<Pair<String, String>>): String =
            params.joinToString("&") { (name, value) -> encode(name) + "=" + encode(value) }

        private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

        /** Android's optString() turns JSON null into "null"; this returns null for null, missing and empty. */
        private fun JSONObject.text(name: String): String? =
            if (isNull(name)) null else optString(name).ifEmpty { null }

        private const val DEFAULT_EXPIRES_IN_S = 3600L
    }
}
