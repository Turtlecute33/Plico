package app.plico.account

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeviceCredentialsTest {
    private val minute = 60_000L
    private var now = 1_000 * minute

    /** Simulates AccountManager: it returns its cached token until that token is invalidated. */
    private var cached: String? = "old"
    private var minted = 0
    private val fetches = ArrayList<String>()
    private val cleared = ArrayList<String>()

    private val credentials = DeviceCredentials(
        user = "me@gmail.com",
        fetch = { (cached ?: "token-${++minted}").also { cached = it; fetches += it } },
        clear = { if (cached == it) cached = null; cleared += it },
        clock = { now },
    )

    private suspend fun login(): String {
        val (client, stream) = imap(IMAP_OK)
        credentials.authImap(client)
        return stream.saslResponse().substringAfter("Bearer ").trimEnd('\u0001')
    }

    @Test
    fun `an unknown token is invalidated and fetched again`() = runTest {
        assertNull(credentials.expiresAt)
        assertEquals("token-1", login())
        assertEquals(listOf("old", "token-1"), fetches)
        assertEquals(listOf("old"), cleared)
        assertEquals(now + 55 * minute, credentials.expiresAt)
    }

    @Test
    fun `a token younger than 45 minutes is reused`() = runTest {
        login()
        now += 45 * minute
        assertEquals("token-1", login())
        assertEquals(2, fetches.size)
        assertEquals(1, cleared.size)
    }

    @Test
    fun `an older token is replaced`() = runTest {
        login()
        now += 45 * minute + 1
        assertEquals("token-2", login())
        assertEquals(listOf("token-1"), cleared.drop(1))
        assertEquals(now + 55 * minute, credentials.expiresAt)
    }

    @Test
    fun `invalidation within 10 minutes fetches without clearing`() = runTest {
        login()
        val firstClear = now
        now += 9 * minute
        credentials.invalidate()
        assertEquals("token-1", login())
        assertEquals(1, cleared.size, "at most one invalidation per 10 min")
        assertEquals(firstClear + 55 * minute, credentials.expiresAt, "the acquired time stays a safe lower bound")
    }

    @Test
    fun `invalidation after 10 minutes clears and fetches`() = runTest {
        login()
        now += 10 * minute
        credentials.invalidate()
        assertEquals("token-2", login())
        assertEquals(2, cleared.size)
        assertEquals(now + 55 * minute, credentials.expiresAt)
    }

    @Test
    fun `SMTP uses the same token without changing the session end`() = runTest {
        login()
        val sessionEnd = credentials.expiresAt
        now += 46 * minute
        val (client, stream) = smtp(SMTP_OK)
        credentials.authSmtp(client)
        assertEquals("user=me@gmail.com\u0001auth=Bearer token-2\u0001\u0001", stream.saslResponse())
        assertEquals(sessionEnd, credentials.expiresAt)
    }

    @Test
    fun `a refused token is fetched again on the next login`() = runTest {
        login()
        val (client, _) = imap("+ eyJzdGF0dXMiOiI0MDAifQ==", "A1 NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)")
        now += 11 * minute
        assertFailsWith<MailError.AuthRequired> { credentials.authImap(client) }
        assertEquals("token-2", login())
    }

    @Test
    fun `revoke drops the token from AccountManager`() = runTest {
        login()
        credentials.revoke()
        assertEquals(listOf("old", "token-1"), cleared)
        assertNull(cached)
    }

    @Test
    fun `fetch errors propagate`() = runTest {
        val failing = DeviceCredentials("me@gmail.com", { throw MailError.AuthRequired("consent") }, {}, { now })
        val error = assertFailsWith<MailError.AuthRequired> { failing.authImap(imap(IMAP_OK).first) }
        assertEquals("consent", error.message)
    }
}
