package app.plico.account

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.plico.PlicoApp
import app.plico.R
import app.plico.data.Prefs
import app.plico.data.Provider
import app.plico.ui.components.SystemPages
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

/**
 * Result of a browser sign-in. [email] is the id_token `email` claim. [login] is the XOAUTH2 user when it differs
 * from [email] (Microsoft work accounts without an `email` claim), else null.
 */
class SignIn(val email: String, val login: String?, val refreshToken: String, val accessToken: AccessToken) {
    override fun toString() = "SignIn"
}

/**
 * An issuer that [BrowserOAuth] signs in to. Microsoft always uses the authority `common`.
 *
 * @param brand the name of the issuer in texts; it is not translated.
 * @param revokeUrl null when the issuer has no revoke endpoint for public clients.
 * @param scopes the scopes of the authorization request.
 * @param refreshScope the scopes of a refresh request; null sends none.
 * @param mailScope the scope that gives access to mail; the granted scopes must contain it.
 * @param hosts the only mail servers that get the tokens of this issuer.
 */
enum class OAuthIssuer(
    val id: String,
    val brand: String,
    val authUrl: String,
    val tokenUrl: String,
    val revokeUrl: String?,
    val scopes: String,
    val refreshScope: String?,
    val mailScope: String,
    val redirectStyle: RedirectStyle,
    val hosts: Set<String>,
) {
    GOOGLE(
        "google", "Google", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
        "https://oauth2.googleapis.com/revoke", Providers.GMAIL_SCOPE + " openid email", null, Providers.GMAIL_SCOPE,
        RedirectStyle.PATH, setOf("imap.gmail.com", "smtp.gmail.com"),
    ),
    MICROSOFT(
        "microsoft", "Microsoft",
        "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        "https://login.microsoftonline.com/common/oauth2/v2.0/token", null,
        "openid email profile offline_access https://outlook.office.com/IMAP.AccessAsUser.All " +
            "https://outlook.office.com/SMTP.Send",
        "https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send offline_access",
        "IMAP.AccessAsUser.All", RedirectStyle.AUTHORITY,
        setOf("outlook.office365.com", "smtp-mail.outlook.com", "smtp.office365.com"),
    ),
    ;

    /** PATH: `<applicationId>:/oauth2redirect`. AUTHORITY: `<applicationId>://oauth2redirect`. */
    enum class RedirectStyle { PATH, AUTHORITY }

    /** The user's own client ID for this issuer; empty when none is set. */
    fun clientId(prefs: Prefs): String = when (this) {
        GOOGLE -> prefs.googleClientId
        MICROSOFT -> prefs.microsoftClientId
    }.trim()

    /** The redirect URI of this issuer for [applicationId]. The manifest registers the scheme. */
    fun redirectUri(applicationId: String): String = when (redirectStyle) {
        RedirectStyle.PATH -> "$applicationId:/$REDIRECT_NAME"
        RedirectStyle.AUTHORITY -> "$applicationId://$REDIRECT_NAME"
    }

    companion object {
        internal const val REDIRECT_NAME = "oauth2redirect"

        /** The issuer with [id]; Google when it is missing or unknown (pending entries of older versions). */
        fun of(id: String?): OAuthIssuer = entries.firstOrNull { it.id == id } ?: GOOGLE

        /** The issuer of the OAuth sign-in of [provider]; null when the provider has none. */
        fun of(provider: Provider): OAuthIssuer? = when (provider) {
            Provider.GMAIL -> GOOGLE
            Provider.MICROSOFT -> MICROSOFT
            else -> null
        }
    }
}

/**
 * Browser OAuth with the user's own client ID (DESIGN.md §6, privacy PS9/PS16, apis AF16): PKCE S256, `state`,
 * the default browser, the redirect URI of [issuer].
 *
 * Flow: [start] stores the pending sign-in in Prefs and opens the browser. [OAuthRedirectActivity] calls
 * [onRedirect], which stores the code in that entry and opens MainActivity with [ACTION_DONE]. Setup then
 * calls [completePending]. The entry is used once and is valid for 10 min, also across process death.
 */
class BrowserOAuth(private val context: Context, private val prefs: Prefs, val issuer: OAuthIssuer) : TokenEndpoint {
    constructor(app: PlicoApp, issuer: OAuthIssuer) : this(app, app.prefs, issuer)

    /** The redirect URI of [issuer] for this app. */
    val redirectUri: String get() = issuer.redirectUri(context.packageName)

    /**
     * Opens the consent page of [issuer] in the default browser. [email] is the login hint; the user can still
     * pick another account, so the result's email comes from the id_token. Throws [MailError.Setup] without a
     * client ID.
     */
    fun start(activity: Activity, email: String) {
        val clientId = clientId(issuer)
        val verifier = randomToken()
        val state = randomToken()
        prefs.put(PENDING_KEY, Pending(state, verifier, email, now(), issuer = issuer).toJson())
        val url = authUrl(issuer, clientId, redirectUri, state, challenge(verifier), email)
        if (!SystemPages.browser(activity, url, toast = false)) {
            prefs.put(PENDING_KEY, null)
            throw MailError.Setup(context.getString(R.string.account_error_no_browser))
        }
    }

    /**
     * Exchanges the code that [onRedirect] stored, with the issuer of the pending entry. Returns null while no
     * redirect has arrived. Throws [MailError.AuthRequired] when the user declined, [MailError.Setup] for an
     * expired sign-in, a rejected client or a missing mail scope, and IOException for network failures.
     */
    suspend fun completePending(): SignIn? {
        val pending = pending(prefs) ?: return null
        if (!pending.done) {
            if (!pending.isFresh(now())) prefs.put(PENDING_KEY, null)
            return null
        }
        prefs.put(PENDING_KEY, null)
        val issuer = pending.issuer
        if (issuer == OAuthIssuer.MICROSOFT) TODO("B")
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
            issuer,
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to pending.verifier,
            "client_id" to clientId(issuer),
            "redirect_uri" to issuer.redirectUri(context.packageName),
        )
        if (!grantsMail(issuer, response.scope)) {
            throw MailError.Setup(context.getString(R.string.account_error_oauth_scope))
        }
        val email = response.idToken?.let(::emailFromIdToken)
        val refreshToken = response.refreshToken
        if (email == null || refreshToken == null) {
            throw MailError.Server(context.getString(R.string.account_error_oauth_incomplete))
        }
        return SignIn(email, null, refreshToken, response.accessToken())
    }

    override suspend fun refresh(refreshToken: String): AccessToken {
        if (issuer == OAuthIssuer.MICROSOFT) TODO("B")
        return tokenRequest(
            issuer,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId(issuer),
        ).accessToken()
    }

    /**
     * Revokes the grant of the whole client (all tokens). The token goes in the body, never in the URL. No-op
     * for an issuer without a revoke endpoint.
     */
    override suspend fun revoke(token: String) {
        val url = issuer.revokeUrl ?: return
        val (status, body) = post(url, listOf("token" to token))
        if (status !in 200..299 && parseError(body) != "invalid_token") throw IOException("Revoke failed: HTTP $status")
    }

    private fun clientId(issuer: OAuthIssuer): String = issuer.clientId(prefs).ifEmpty {
        throw MailError.Setup(context.getString(R.string.account_error_no_client_id))
    }

    private fun TokenResponse.accessToken() = AccessToken(accessToken, now() + expiresInS * 1000)

    private suspend fun tokenRequest(issuer: OAuthIssuer, vararg form: Pair<String, String>): TokenResponse {
        val (status, body) = post(issuer.tokenUrl, form.toList())
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
    data class Pending(
        val state: String,
        val verifier: String,
        val email: String,
        val createdAt: Long,
        val code: String? = null,
        val error: String? = null,
        val issuer: OAuthIssuer = OAuthIssuer.GOOGLE,
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
            .put("issuer", issuer.id)
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
                        issuer = OAuthIssuer.of(o.text("issuer")),
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

        internal const val PENDING_KEY = "oauth.pending"
        internal const val REDIRECT_PATH = "/" + OAuthIssuer.REDIRECT_NAME
        internal const val MAX_AGE_MS = 10 * 60_000L
        private const val MAX_PARAM_LENGTH = 2048
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val TIMEOUT_MS = 20_000

        private val random = SecureRandom()

        /**
         * Stores the code or error of a browser redirect in the pending entry. Only the URI is read, never extras.
         * Returns false when the redirect does not match the pending sign-in (the redirect URI of its issuer,
         * state, age or a second use).
         */
        fun onRedirect(context: Context, prefs: Prefs, uri: Uri): Boolean {
            val pending = pending(prefs) ?: return false
            if (!isRedirect(pending.issuer, context.packageName, uri.scheme, uri.authority, uri.path)) return false
            val done = pending.redirected(
                state = uri.getQueryParameter("state"),
                code = uri.getQueryParameter("code"),
                error = uri.getQueryParameter("error"),
                now = now(),
            ) ?: return false
            prefs.put(PENDING_KEY, done.toJson())
            return true
        }

        /** The pending sign-in, or null when there is none or it cannot be read. */
        fun pending(prefs: Prefs): Pending? = Pending.parse(prefs.get(PENDING_KEY))

        /**
         * Removes a pending sign-in that can no longer complete (abandoned in the browser, or unreadable), so that
         * its address, verifier and state do not stay in Prefs. Call it at app start.
         */
        fun dropStale(prefs: Prefs) {
            val json = prefs.get(PENDING_KEY) ?: return
            if (isStale(json, SystemClock.elapsedRealtime())) prefs.put(PENDING_KEY, null)
        }

        internal fun isStale(json: String, now: Long) = Pending.parse(json)?.isFresh(now) != true

        /**
         * True when [scheme], [authority] and [path] are those of the redirect URI of [issuer] for [applicationId].
         * The query is read by the caller; a `#` fragment is ignored.
         */
        internal fun isRedirect(
            issuer: OAuthIssuer,
            applicationId: String,
            scheme: String?,
            authority: String?,
            path: String?,
        ): Boolean = scheme == applicationId && when (issuer.redirectStyle) {
            OAuthIssuer.RedirectStyle.PATH -> authority.isNullOrEmpty() && path == REDIRECT_PATH
            OAuthIssuer.RedirectStyle.AUTHORITY ->
                authority == OAuthIssuer.REDIRECT_NAME && (path.isNullOrEmpty() || path == "/")
        }

        /** 32 random bytes, base64url without padding: a 43-character PKCE verifier (RFC 7636 §4.1) or state. */
        internal fun randomToken(): String =
            base64Url(ByteArray(32).also(random::nextBytes))

        /** S256 code challenge: BASE64URL(SHA-256(ASCII(verifier))) (RFC 7636 §4.2). */
        internal fun challenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** The consent page URL. Both issuers get a `prompt`, so that no grant is ever silent. */
        internal fun authUrl(
            issuer: OAuthIssuer,
            clientId: String,
            redirectUri: String,
            state: String,
            challenge: String,
            email: String,
        ): String = issuer.authUrl + "?" + formEncode(
                listOfNotNull(
                    "client_id" to clientId,
                    "redirect_uri" to redirectUri,
                    "response_type" to "code",
                    "scope" to issuer.scopes,
                    "state" to state,
                    "code_challenge" to challenge,
                    "code_challenge_method" to "S256",
                    email.takeIf { it.isNotBlank() }?.let { "login_hint" to it },
                ) + when (issuer) {
                    OAuthIssuer.GOOGLE -> listOf("access_type" to "offline", "prompt" to "consent")
                    OAuthIssuer.MICROSOFT -> listOf("response_mode" to "query", "prompt" to "select_account")
                },
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

        /** Granular consent lets the user untick mail; then the grant is useless for IMAP. */
        internal fun grantsMail(issuer: OAuthIssuer, scope: String?): Boolean = scope == null || when (issuer) {
            OAuthIssuer.GOOGLE -> issuer.mailScope in scope.split(' ')
            OAuthIssuer.MICROSOFT -> scope.contains(issuer.mailScope, ignoreCase = true)
        }

        /**
         * The `email` claim of an id_token. The token comes straight from the token endpoint of the issuer over
         * TLS, so its signature is not checked (privacy PS16).
         */
        internal fun emailFromIdToken(idToken: String): String? = try {
            val payload = idToken.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
            JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).text("email")
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JSONException) {
            null
        }

        private fun now() = SystemClock.elapsedRealtime()

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
