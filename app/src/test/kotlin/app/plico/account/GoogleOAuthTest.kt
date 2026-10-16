package app.plico.account

import app.plico.account.GoogleOAuth.Companion.MAX_AGE_MS
import app.plico.account.GoogleOAuth.Failure
import app.plico.account.GoogleOAuth.Pending
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleOAuthTest {

    @Test
    fun `PKCE S256 matches RFC 7636 appendix B`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GoogleOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `random tokens are 43 unreserved characters and differ`() {
        val tokens = List(20) { GoogleOAuth.randomToken() }
        for (token in tokens) {
            assertEquals(43, token.length)
            assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' }, token)
        }
        assertEquals(tokens.size, tokens.toSet().size)
        assertEquals(43, GoogleOAuth.challenge(tokens.first()).length)
    }

    @Test
    fun `redirect URI uses the application id as scheme`() {
        assertEquals("app.plico:/oauth2redirect", GoogleOAuth.redirectUri("app.plico"))
        assertEquals("app.plico.debug:/oauth2redirect", GoogleOAuth.redirectUri("app.plico.debug"))
    }

    @Test
    fun `auth URL carries every parameter`() {
        val url = GoogleOAuth.authUrl(
            clientId = "123-abc.apps.googleusercontent.com",
            redirectUri = "app.plico:/oauth2redirect",
            state = "st4te",
            challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            email = "a+b@gmail.com",
        )
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        val query = url.substringAfter('?')
        assertFalse(' ' in query || '+' in query, "spaces must be %20: $query")
        assertEquals(
            mapOf(
                "client_id" to "123-abc.apps.googleusercontent.com",
                "redirect_uri" to "app.plico:/oauth2redirect",
                "response_type" to "code",
                "scope" to "https://mail.google.com/ openid email",
                "state" to "st4te",
                "code_challenge" to "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "code_challenge_method" to "S256",
                "login_hint" to "a+b@gmail.com",
                "access_type" to "offline",
                "prompt" to "consent",
            ),
            params(query),
        )
    }

    @Test
    fun `auth URL without an address has no login hint`() {
        val url = GoogleOAuth.authUrl("id", "app.plico:/oauth2redirect", "s", "c", " ")
        assertFalse("login_hint" in params(url.substringAfter('?')))
    }

    @Test
    fun `token response is parsed`() {
        val response = GoogleOAuth.parseToken(
            """
            {"access_token": "ya29.a0Af", "expires_in": 3599, "refresh_token": "1//0g-refresh",
             "scope": "openid https://mail.google.com/ https://www.googleapis.com/auth/userinfo.email",
             "token_type": "Bearer", "id_token": "h.p.s"}
            """.trimIndent(),
        )
        assertEquals("ya29.a0Af", response.accessToken)
        assertEquals(3599, response.expiresInS)
        assertEquals("1//0g-refresh", response.refreshToken)
        assertEquals("h.p.s", response.idToken)
        assertTrue(GoogleOAuth.grantsMail(response.scope))
        assertFalse("ya29" in response.toString())
    }

    @Test
    fun `refresh response has no refresh token`() {
        val response =
            GoogleOAuth.parseToken("""{"access_token":"ya29.b","expires_in":3599,"refresh_token":null,"token_type":"Bearer"}""")
        assertEquals("ya29.b", response.accessToken)
        assertNull(response.refreshToken)
        assertNull(response.idToken)
        assertNull(response.scope)
        assertTrue(GoogleOAuth.grantsMail(response.scope))
    }

    @Test
    fun `token response without access token or JSON fails`() {
        assertFailsWith<IOException> { GoogleOAuth.parseToken("""{"expires_in":3599}""") }
        assertFailsWith<IOException> { GoogleOAuth.parseToken("""{"access_token":""}""") }
        assertFailsWith<IOException> { GoogleOAuth.parseToken("<html>502</html>") }
        assertEquals(3600, GoogleOAuth.parseToken("""{"access_token":"t"}""").expiresInS)
    }

    @Test
    fun `scope without Gmail is detected`() {
        assertFalse(GoogleOAuth.grantsMail("openid https://www.googleapis.com/auth/userinfo.email"))
        assertFalse(GoogleOAuth.grantsMail("https://mail.google.com/x openid"))
        assertTrue(GoogleOAuth.grantsMail("https://mail.google.com/"))
    }

    @Test
    fun `error responses are parsed and mapped`() {
        assertEquals("invalid_grant", GoogleOAuth.parseError("""{"error":"invalid_grant","error_description":"Bad Request"}"""))
        assertEquals(
            "invalid_token",
            GoogleOAuth.parseError("""{"error": "invalid_token", "error_description": "Token expired or revoked"}"""),
        )
        assertNull(GoogleOAuth.parseError("""{"error":null}"""))
        assertNull(GoogleOAuth.parseError("Service Unavailable"))
        assertNull(GoogleOAuth.parseError(""))
        assertNull(GoogleOAuth.parseError(null))

        assertEquals(Failure.GRANT, GoogleOAuth.failure(400, "invalid_grant"))
        assertEquals(Failure.CLIENT, GoogleOAuth.failure(401, "invalid_client"))
        assertEquals(Failure.CLIENT, GoogleOAuth.failure(400, "unauthorized_client"))
        assertEquals(Failure.CLIENT, GoogleOAuth.failure(400, "invalid_request"))
        assertEquals(Failure.TEMPORARY, GoogleOAuth.failure(503, null))
        assertEquals(Failure.TEMPORARY, GoogleOAuth.failure(500, "server_error"))
        assertEquals(Failure.TEMPORARY, GoogleOAuth.failure(429, "rate_limit_exceeded"))
        assertEquals(Failure.TEMPORARY, GoogleOAuth.failure(302, null))
    }

    @Test
    fun `id token email is read from the payload`() {
        // base64url without padding; the payload uses '-' and '_' where standard base64 has '+' and '/'.
        val claims = """{"iss":"https://accounts.google.com","aud":"??>","email":"user.name@gmail.com","email_verified":true}"""
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
        assertTrue('-' in payload || '_' in payload)
        assertFalse('=' in payload)
        assertEquals("user.name@gmail.com", GoogleOAuth.emailFromIdToken("eyJhbGciOiJSUzI1NiJ9.$payload.c2ln"))

        val padded = Base64.getUrlEncoder().encodeToString("""{"email":"a@gmail.com"}""".toByteArray())
        assertTrue(padded.endsWith("="))
        assertEquals("a@gmail.com", GoogleOAuth.emailFromIdToken("h.$padded.s"))
    }

    @Test
    fun `malformed id tokens give no email`() {
        val noEmail = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"1"}""".toByteArray())
        assertNull(GoogleOAuth.emailFromIdToken("h.$noEmail.s"))
        assertNull(GoogleOAuth.emailFromIdToken("onlyone"))
        assertNull(GoogleOAuth.emailFromIdToken("a.b"))
        assertNull(GoogleOAuth.emailFromIdToken("h.!!!.s"))
        assertNull(GoogleOAuth.emailFromIdToken("h.${Base64.getUrlEncoder().encodeToString("not json".toByteArray())}.s"))
    }

    @Test
    fun `pending entry round-trips through JSON`() {
        val pending = Pending("state", "verifier", "me@gmail.com", 1234)
        assertEquals(pending, Pending.parse(pending.toJson()))
        val done = pending.copy(code = "4/0Ab")
        assertEquals(done, Pending.parse(done.toJson()))
        assertNull(Pending.parse(null))
        assertNull(Pending.parse("not json"))
        assertNull(Pending.parse("""{"state":"s"}"""))
        assertFalse("verifier" in done.toString() || "4/0Ab" in done.toString())
    }

    @Test
    fun `pending entry is fresh for 10 minutes`() {
        val pending = Pending("s", "v", "e", createdAt = 1_000)
        assertTrue(pending.isFresh(1_000))
        assertTrue(pending.isFresh(1_000 + MAX_AGE_MS))
        assertFalse(pending.isFresh(1_001 + MAX_AGE_MS))
        assertFalse(pending.isFresh(999), "a reboot resets elapsedRealtime")
    }

    @Test
    fun `app start drops only entries that can no longer complete`() {
        val pending = Pending("s", "v", "me@gmail.com", createdAt = 1_000)
        assertFalse(GoogleOAuth.isStale(pending.toJson(), 1_000 + MAX_AGE_MS))
        assertFalse(GoogleOAuth.isStale(pending.copy(code = "4/0Ab").toJson(), 2_000), "a redirect waits for setup")
        assertTrue(GoogleOAuth.isStale(pending.toJson(), 1_001 + MAX_AGE_MS), "abandoned in the browser")
        assertTrue(GoogleOAuth.isStale(pending.toJson(), 999), "after a reboot")
        assertTrue(GoogleOAuth.isStale("not json", 1_000))
    }

    @Test
    fun `redirect with matching state stores the code once`() {
        val pending = Pending("s1", "v", "e", createdAt = 0)
        val done = pending.redirected("s1", "4/0Ab", null, now = 60_000)
        assertEquals("4/0Ab", done?.code)
        assertTrue(done!!.done)
        assertNull(done.redirected("s1", "4/other", null, now = 60_000), "single use")
    }

    @Test
    fun `redirect errors are stored`() {
        val done = Pending("s1", "v", "e", createdAt = 0).redirected("s1", null, "access_denied", now = 1)
        assertEquals("access_denied", done?.error)
        assertNull(done?.code)
    }

    @Test
    fun `redirects that do not belong to the entry are refused`() {
        val pending = Pending("s1", "v", "e", createdAt = 0)
        assertNull(pending.redirected("s2", "code", null, now = 1))
        assertNull(pending.redirected(null, "code", null, now = 1))
        assertNull(pending.redirected("s1", "code", null, now = MAX_AGE_MS + 1))
        assertNull(pending.redirected("s1", null, null, now = 1))
        assertNull(pending.redirected("s1", "", null, now = 1))
        assertNull(pending.redirected("s1", "x".repeat(5000), null, now = 1))
    }

    private fun params(query: String): Map<String, String> = query.split('&').associate {
        val (name, value) = it.split('=', limit = 2)
        URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
    }.also { assertNotEquals(0, it.size) }
}
