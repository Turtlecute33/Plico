package app.plico.account

import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Provider
import app.plico.data.PushMode
import app.plico.data.Servers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class CredentialsCacheTest {
    private val cache = CredentialsCache()
    private var created = 0

    private fun get(account: Account) = cache.get(account) {
        created++
        PasswordCredentials(account.provider, account.email, "pw")
    }

    @Test
    fun `the same account gets the same instance`() {
        val account = account()
        assertSame(get(account), get(account))
        assertSame(get(account), get(account.copy(label = "Work", pushMode = PushMode.POLL, state = AccountState.SIGN_IN_NEEDED)))
        assertEquals(1, created)
    }

    @Test
    fun `a sign-in change gives a new instance`() {
        val account = account()
        var last = get(account)
        for (changed in listOf(
            account.copy(auth = AuthKind.OAUTH),
            account.copy(auth = AuthKind.DEVICE, deviceAccount = "me@gmail.com"),
            account.copy(auth = AuthKind.DEVICE, deviceAccount = "other@gmail.com"),
            account.copy(email = "new@gmail.com"),
            account.copy(provider = Provider.RISEUP),
            account.copy(provider = Provider.RISEUP, servers = Servers.NONE.copy(login = "me")),
        )) {
            val next = get(changed)
            assertNotSame(last, next)
            last = next
        }
        assertEquals(7, created)
    }

    @Test
    fun `the same user name on other servers keeps the instance`() {
        val account = account()
        val first = get(account)
        assertSame(first, get(account.copy(servers = Providers.GMAIL_SERVERS)))
        assertSame(first, get(account.copy(servers = Servers.NONE.copy(login = account.email))), "login = the address")
    }

    @Test
    fun `accounts are cached separately and can be forgotten`() {
        val first = get(account(id = 1))
        val second = get(account(id = 2))
        assertNotSame(first, second)
        cache.remove(1)
        assertNotSame(first, get(account(id = 1)))
        assertSame(second, get(account(id = 2)))
    }

    private fun account(id: Long = 7) = Account(
        id = id,
        provider = Provider.GMAIL,
        email = "me@gmail.com",
        senderName = "",
        label = "Gmail",
        color = 0,
        auth = AuthKind.PASSWORD,
        deviceAccount = null,
        bodyDays = 30,
        pushMode = PushMode.INSTANT,
        pollMinutes = 15,
        gmailCategories = setOf("primary"),
        archiveFolder = null,
        signature = "",
        state = AccountState.OK,
        stateText = null,
        serverKeepaliveS = null,
        quotaUsed = null,
        quotaLimit = null,
        sort = 0,
    )
}
