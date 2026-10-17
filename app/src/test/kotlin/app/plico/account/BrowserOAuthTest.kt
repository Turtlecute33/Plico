package app.plico.account

import app.plico.account.BrowserOAuth.Companion.MAX_AGE_MS
import app.plico.account.BrowserOAuth.Failure
import app.plico.account.BrowserOAuth.Pending
import app.plico.data.Provider
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

class BrowserOAuthTest {

    @Test
    fun `PKCE S256 matches RFC 7636 appendix B`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            BrowserOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `random tokens are 43 unreserved characters and differ`() {
        val tokens = List(20) { BrowserOAuth.randomToken() }
        for (token in tokens) {
            assertEquals(43, token.length)
            assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' }, token)
        }
        assertEquals(tokens.size, tokens.toSet().size)
        assertEquals(43, BrowserOAuth.challenge(tokens.first()).length)
    }

    @Test
    fun `redirect URI uses the application id as scheme`() {
        assertEquals("app.plico:/oauth2redirect", OAuthIssuer.GOOGLE.redirectUri("app.plico"))
        assertEquals("app.plico.debug:/oauth2redirect", OAuthIssuer.GOOGLE.redirectUri("app.plico.debug"))
        assertEquals("app.plico://oauth2redirect", OAuthIssuer.MICROSOFT.redirectUri("app.plico"))
        assertEquals("app.plico.debug://oauth2redirect", OAuthIssuer.MICROSOFT.redirectUri("app.plico.debug"))
    }

    @Test
    fun `a redirect must have the form of the pending issuer`() {
        val google = OAuthIssuer.GOOGLE
        val microsoft = OAuthIssuer.MICROSOFT
        assertTrue(BrowserOAuth.isRedirect(google, "app.plico", "app.plico", null, "/oauth2redirect"))
        assertTrue(BrowserOAuth.isRedirect(google, "app.plico", "app.plico", "", "/oauth2redirect"))
        assertFalse(BrowserOAuth.isRedirect(google, "app.plico", "app.plico", "oauth2redirect", ""))
        assertFalse(BrowserOAuth.isRedirect(google, "app.plico", "app.plico.debug", null, "/oauth2redirect"))
        assertFalse(BrowserOAuth.isRedirect(google, "app.plico", "app.plico", null, "/other"))
        assertTrue(BrowserOAuth.isRedirect(microsoft, "app.plico", "app.plico", "oauth2redirect", ""))
        assertTrue(BrowserOAuth.isRedirect(microsoft, "app.plico", "app.plico", "oauth2redirect", "/"))
        assertTrue(BrowserOAuth.isRedirect(microsoft, "app.plico", "app.plico", "oauth2redirect", null))
        assertFalse(BrowserOAuth.isRedirect(microsoft, "app.plico", "app.plico", null, "/oauth2redirect"))
        assertFalse(BrowserOAuth.isRedirect(microsoft, "app.plico", "app.plico", "evil", ""))
        assertFalse(BrowserOAuth.isRedirect(microsoft, "app.plico", "app.plico", "oauth2redirect", "/x"))
    }

    @Test
    fun `issuers are found by id and by provider`() {
        assertEquals(OAuthIssuer.MICROSOFT, OAuthIssuer.of("microsoft"))
        assertEquals(OAuthIssuer.GOOGLE, OAuthIssuer.of("google"))
        assertEquals(OAuthIssuer.GOOGLE, OAuthIssuer.of(null as String?), "entries of older versions")
        assertEquals(OAuthIssuer.GOOGLE, OAuthIssuer.of("unknown"))
        assertEquals(OAuthIssuer.GOOGLE, OAuthIssuer.of(Provider.GMAIL))
        assertEquals(OAuthIssuer.MICROSOFT, OAuthIssuer.of(Provider.MICROSOFT))
        assertNull(OAuthIssuer.of(Provider.RISEUP))
        assertNull(OAuthIssuer.of(Provider.OTHER))
    }

    @Test
    fun `auth URL carries every parameter`() {
        val url = BrowserOAuth.authUrl(
            OAuthIssuer.GOOGLE,
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
        val url = BrowserOAuth.authUrl(OAuthIssuer.GOOGLE, "id", "app.plico:/oauth2redirect", "s", "c", " ")
        assertFalse("login_hint" in params(url.substringAfter('?')))
    }

    @Test
    fun `token response is parsed`() {
        val response = BrowserOAuth.parseToken(
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
        assertTrue(BrowserOAuth.grantsMail(OAuthIssuer.GOOGLE, response.scope))
        assertFalse("ya29" in response.toString())
    }

    @Test
    fun `refresh response has no refresh token`() {
        val response =
            BrowserOAuth.parseToken("""{"access_token":"ya29.b","expires_in":3599,"refresh_token":null,"token_type":"Bearer"}""")
        assertEquals("ya29.b", response.accessToken)
        assertNull(response.refreshToken)
        assertNull(response.idToken)
        assertNull(response.scope)
        assertTrue(BrowserOAuth.grantsMail(OAuthIssuer.GOOGLE, response.scope))
    }

    @Test
    fun `token response without access token or JSON fails`() {
        assertFailsWith<IOException> { BrowserOAuth.parseToken("""{"expires_in":3599}""") }
        assertFailsWith<IOException> { BrowserOAuth.parseToken("""{"access_token":""}""") }
        assertFailsWith<IOException> { BrowserOAuth.parseToken("<html>502</html>") }
        assertEquals(3600, BrowserOAuth.parseToken("""{"access_token":"t"}""").expiresInS)
    }

    @Test
    fun `scope without Gmail is detected`() {
        assertFalse(BrowserOAuth.grantsMail(OAuthIssuer.GOOGLE, "openid https://www.googleapis.com/auth/userinfo.email"))
        assertFalse(BrowserOAuth.grantsMail(OAuthIssuer.GOOGLE, "https://mail.google.com/x openid"))
        assertTrue(BrowserOAuth.grantsMail(OAuthIssuer.GOOGLE, "https://mail.google.com/"))
    }

    @Test
    fun `error responses are parsed and mapped`() {
        assertEquals("invalid_grant", BrowserOAuth.parseError("""{"error":"invalid_grant","error_description":"Bad Request"}"""))
        assertEquals(
            "invalid_token",
            BrowserOAuth.parseError("""{"error": "invalid_token", "error_description": "Token expired or revoked"}"""),
        )
        assertNull(BrowserOAuth.parseError("""{"error":null}"""))
        assertNull(BrowserOAuth.parseError("Service Unavailable"))
        assertNull(BrowserOAuth.parseError(""))
        assertNull(BrowserOAuth.parseError(null))

        assertEquals(Failure.GRANT, BrowserOAuth.failure(400, "invalid_grant"))
        assertEquals(Failure.CLIENT, BrowserOAuth.failure(401, "invalid_client"))
        assertEquals(Failure.CLIENT, BrowserOAuth.failure(400, "unauthorized_client"))
        assertEquals(Failure.CLIENT, BrowserOAuth.failure(400, "invalid_request"))
        assertEquals(Failure.TEMPORARY, BrowserOAuth.failure(503, null))
        assertEquals(Failure.TEMPORARY, BrowserOAuth.failure(500, "server_error"))
        assertEquals(Failure.TEMPORARY, BrowserOAuth.failure(429, "rate_limit_exceeded"))
        assertEquals(Failure.TEMPORARY, BrowserOAuth.failure(302, null))
    }

    @Test
    fun `id token email is read from the payload`() {
        // base64url without padding; the payload uses '-' and '_' where standard base64 has '+' and '/'.
        val claims = """{"iss":"https://accounts.google.com","aud":"??>","email":"user.name@gmail.com","email_verified":true}"""
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
        assertTrue('-' in payload || '_' in payload)
        assertFalse('=' in payload)
        assertEquals("user.name@gmail.com", BrowserOAuth.emailFromIdToken("eyJhbGciOiJSUzI1NiJ9.$payload.c2ln"))

        val padded = Base64.getUrlEncoder().encodeToString("""{"email":"a@gmail.com"}""".toByteArray())
        assertTrue(padded.endsWith("="))
        assertEquals("a@gmail.com", BrowserOAuth.emailFromIdToken("h.$padded.s"))
    }

    @Test
    fun `malformed id tokens give no email`() {
        val noEmail = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"1"}""".toByteArray())
        assertNull(BrowserOAuth.emailFromIdToken("h.$noEmail.s"))
        assertNull(BrowserOAuth.emailFromIdToken("onlyone"))
        assertNull(BrowserOAuth.emailFromIdToken("a.b"))
        assertNull(BrowserOAuth.emailFromIdToken("h.!!!.s"))
        assertNull(BrowserOAuth.emailFromIdToken("h.${Base64.getUrlEncoder().encodeToString("not json".toByteArray())}.s"))
    }

    @Test
    fun `pending entry round-trips through JSON`() {
        val pending = Pending("state", "verifier", "me@gmail.com", 1234)
        assertEquals(pending, Pending.parse(pending.toJson()))
        val done = pending.copy(code = "4/0Ab")
        assertEquals(done, Pending.parse(done.toJson()))
        val microsoft = pending.copy(issuer = OAuthIssuer.MICROSOFT)
        assertEquals(microsoft, Pending.parse(microsoft.toJson()))
        val old = """{"state":"s","verifier":"v","email":"me@gmail.com","createdAt":1}"""
        assertEquals(OAuthIssuer.GOOGLE, Pending.parse(old)?.issuer, "an entry of an older version is Google")
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
        assertFalse(BrowserOAuth.isStale(pending.toJson(), 1_000 + MAX_AGE_MS))
        assertFalse(BrowserOAuth.isStale(pending.copy(code = "4/0Ab").toJson(), 2_000), "a redirect waits for setup")
        assertTrue(BrowserOAuth.isStale(pending.toJson(), 1_001 + MAX_AGE_MS), "abandoned in the browser")
        assertTrue(BrowserOAuth.isStale(pending.toJson(), 999), "after a reboot")
        assertTrue(BrowserOAuth.isStale("not json", 1_000))
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

    @Test
    fun `Microsoft auth URL asks for an account and the mail scopes`() {
        val url = BrowserOAuth.authUrl(
            OAuthIssuer.MICROSOFT,
            clientId = "00000000-0000-0000-0000-000000000001",
            redirectUri = "app.plico://oauth2redirect",
            state = "st4te",
            challenge = "c",
            email = "me@outlook.com",
        )
        assertTrue(url.startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"))
        val query = url.substringAfter('?')
        assertFalse(' ' in query || '+' in query)
        assertEquals(
            mapOf(
                "client_id" to "00000000-0000-0000-0000-000000000001",
                "redirect_uri" to "app.plico://oauth2redirect",
                "response_type" to "code",
                "scope" to "openid email profile offline_access https://outlook.office.com/IMAP.AccessAsUser.All " +
                    "https://outlook.office.com/SMTP.Send",
                "state" to "st4te",
                "code_challenge" to "c",
                "code_challenge_method" to "S256",
                "login_hint" to "me@outlook.com",
                "response_mode" to "query",
                "prompt" to "select_account",
            ),
            params(query),
        )
    }

    @Test
    fun `token forms per issuer`() {
        val google = BrowserOAuth.exchangeForm(OAuthIssuer.GOOGLE, "code", "verifier", "id", "app.plico:/oauth2redirect")
        assertEquals(
            listOf(
                "grant_type" to "authorization_code", "code" to "code", "code_verifier" to "verifier", "client_id" to "id",
                "redirect_uri" to "app.plico:/oauth2redirect",
            ),
            google,
        )
        val microsoft = BrowserOAuth.exchangeForm(OAuthIssuer.MICROSOFT, "code", "verifier", "id", "app.plico://oauth2redirect")
        assertEquals("scope" to OAuthIssuer.MICROSOFT.scopes, microsoft.last())
        assertEquals(
            listOf("grant_type" to "refresh_token", "refresh_token" to "r", "client_id" to "id"),
            BrowserOAuth.refreshForm(OAuthIssuer.GOOGLE, "r", "id"),
        )
        assertEquals(
            "scope" to "https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send offline_access",
            BrowserOAuth.refreshForm(OAuthIssuer.MICROSOFT, "r", "id").last(),
        )
    }

    @Test
    fun `Microsoft mail scope is found without case`() {
        assertTrue(BrowserOAuth.grantsMail(OAuthIssuer.MICROSOFT, "https://outlook.office.com/IMAP.AccessAsUser.All openid"))
        assertTrue(BrowserOAuth.grantsMail(OAuthIssuer.MICROSOFT, "https://outlook.office.com/imap.accessasuser.all"))
        assertFalse(BrowserOAuth.grantsMail(OAuthIssuer.MICROSOFT, "openid profile https://outlook.office.com/SMTP.Send"))
    }

    @Test
    fun `issuer hosts are the pinned mail hosts`() {
        assertEquals(setOf("imap.gmail.com", "smtp.gmail.com"), OAuthIssuer.GOOGLE.hosts)
        assertEquals(setOf("outlook.office365.com", "smtp-mail.outlook.com", "smtp.office365.com"), OAuthIssuer.MICROSOFT.hosts)
        for (host in Providers.OUTLOOK_SERVERS.let { listOf(it.imap.host, it.smtp.host) } +
            Providers.M365_SERVERS.let { listOf(it.imap.host, it.smtp.host) }) {
            assertTrue(host in OAuthIssuer.MICROSOFT.hosts, host)
        }
        assertNull(OAuthIssuer.MICROSOFT.revokeUrl, "Microsoft has no revoke for public clients")
    }

    @Test
    fun `Google identity is the email claim`() {
        val token = AccessToken("a", 1)
        assertEquals("me@gmail.com", BrowserOAuth.identity(OAuthIssuer.GOOGLE, claims("me@gmail.com", null), "x@y.z", "r", token)?.email)
        assertNull(BrowserOAuth.identity(OAuthIssuer.GOOGLE, claims(null, "me@gmail.com"), "me@gmail.com", "r", token))
        assertNull(BrowserOAuth.identity(OAuthIssuer.GOOGLE, null, "me@gmail.com", "r", token))
        assertNull(BrowserOAuth.identity(OAuthIssuer.GOOGLE, claims("me@gmail.com", "other@gmail.com"), "", "r", token)?.login)
    }

    @Test
    fun `Microsoft without an email claim uses the typed address`() {
        val token = AccessToken("a", 1)
        val work = BrowserOAuth.identity(
            OAuthIssuer.MICROSOFT, claims(null, "jdoe@contoso.onmicrosoft.com"), "john@contoso.com", "r", token,
        )
        assertEquals("john@contoso.com", work?.email)
        assertEquals("jdoe@contoso.onmicrosoft.com", work?.login, "the sign-in name is the XOAUTH2 user")
        assertEquals(true, work?.fromHint, "the address does not tell which account signed in")
        val same = BrowserOAuth.identity(OAuthIssuer.MICROSOFT, claims("Me@Outlook.com", "me@outlook.com"), "", "r", token)
        assertEquals("Me@Outlook.com", same?.email)
        assertNull(same?.login, "the same address is no separate login")
        assertEquals(false, same?.fromHint)
        assertEquals(false, BrowserOAuth.identity(OAuthIssuer.GOOGLE, claims("me@gmail.com", null), "", "r", token)?.fromHint)
        val phone = BrowserOAuth.identity(OAuthIssuer.MICROSOFT, claims("me@outlook.com", "+420123456789"), "", "r", token)
        assertNull(phone?.login, "a sign-in name that is not an address")
        assertNull(BrowserOAuth.identity(OAuthIssuer.MICROSOFT, claims(null, null), "", "r", token), "no address at all")
        assertEquals("me@contoso.com", BrowserOAuth.identity(OAuthIssuer.MICROSOFT, null, "me@contoso.com", "r", token)?.email)
    }

    @Test
    fun `id token claims are read`() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"preferred_username":"jdoe@contoso.onmicrosoft.com","name":"J"}""".toByteArray())
        val claims = BrowserOAuth.claims("h.$payload.s")
        assertNull(claims?.email)
        assertEquals("jdoe@contoso.onmicrosoft.com", claims?.preferredUsername)
    }

    @Test
    fun `Microsoft errors are mapped by their AADSTS code first`() {
        for (code in listOf("AADSTS50194", "AADSTS50020", "AADSTS700016")) {
            assertEquals(Failure.ACCOUNT_TYPES, BrowserOAuth.failure(400, "unauthorized_client", code), code)
            assertEquals(Failure.ACCOUNT_TYPES, BrowserOAuth.failure(null, "invalid_request", code), code)
        }
        for (code in listOf("AADSTS65001", "AADSTS90094")) {
            assertEquals(Failure.ADMIN, BrowserOAuth.failure(400, "invalid_grant", code), code)
        }
        assertEquals(Failure.ADMIN, BrowserOAuth.failure(null, "consent_required"))
        for (code in listOf("AADSTS7000218", "AADSTS50011")) {
            assertEquals(Failure.CLIENT, BrowserOAuth.failure(400, "invalid_request", code), code)
        }
        assertEquals(Failure.GRANT, BrowserOAuth.failure(400, "invalid_grant", "AADSTS70000"))
        assertEquals(Failure.GRANT, BrowserOAuth.failure(400, "interaction_required", "AADSTS50076"))
        assertEquals(Failure.DENIED, BrowserOAuth.failure(null, "access_denied", "AADSTS65004"))
        assertEquals(Failure.CLIENT, BrowserOAuth.failure(null, "unauthorized_client"))
        assertEquals(Failure.TEMPORARY, BrowserOAuth.failure(null, "temporarily_unavailable"))
        assertEquals(Failure.TEMPORARY, BrowserOAuth.failure(503, "temporarily_unavailable", "AADSTS90033"))
    }

    @Test
    fun `AADSTS codes are read from the description`() {
        val body = """{"error":"invalid_grant","error_description":"AADSTS65001: The user or administrator has not """ +
            """consented to use the application. AADSTS1234: other","error_codes":[65001]}"""
        assertEquals("AADSTS65001", BrowserOAuth.aadsts(BrowserOAuth.parseErrorDescription(body)))
        assertNull(BrowserOAuth.aadsts("Bad Request"))
        assertNull(BrowserOAuth.aadsts(null))
        assertNull(BrowserOAuth.parseErrorDescription("""{"error":"invalid_grant"}"""))
        assertNull(BrowserOAuth.parseErrorDescription("<html>"))
    }

    @Test
    fun `redirect errors keep the AADSTS code`() {
        val pending = Pending("s1", "v", "e", createdAt = 0, issuer = OAuthIssuer.MICROSOFT)
        val done = pending.redirected(
            "s1", null, "access_denied", now = 1,
            description = "AADSTS65004: User declined to consent to access the app.",
        )
        assertEquals("access_denied", done?.error)
        assertEquals("AADSTS65004", done?.errorCode)
        assertEquals(done, Pending.parse(done!!.toJson()))
        assertNull(pending.redirected("s1", "code", null, now = 1, description = "AADSTS1")?.errorCode)
    }

    @Test
    fun `other apps that take the redirect are found`() {
        assertEquals(emptyList(), BrowserOAuth.otherHandlers(listOf("app.plico"), "app.plico"))
        assertEquals(emptyList(), BrowserOAuth.otherHandlers(emptyList(), "app.plico"))
        assertEquals(
            listOf("com.evil"),
            BrowserOAuth.otherHandlers(listOf("app.plico", "com.evil", "com.evil"), "app.plico"),
        )
        assertEquals(listOf("app.plico"), BrowserOAuth.otherHandlers(listOf("app.plico"), "app.plico.debug"))
    }

    private fun claims(email: String?, preferred: String?) = BrowserOAuth.Companion.Claims(email, preferred)

    private fun params(query: String): Map<String, String> = query.split('&').associate {
        val (name, value) = it.split('=', limit = 2)
        URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
    }.also { assertNotEquals(0, it.size) }
}
