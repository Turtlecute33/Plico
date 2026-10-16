package app.plico.ui.setup

import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.PushMode
import app.plico.data.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SetupStateTest {
    private val start = SetupState()
    private val riseupAccount = SignedIn(Provider.RISEUP, "joe@riseup.net", AuthKind.PASSWORD, byteArrayOf(1), null)
    private val gmailAccount = SignedIn(Provider.GMAIL, "jane@gmail.com", AuthKind.OAUTH, byteArrayOf(2), null)

    @Test
    fun `welcome is the root when adding`() {
        assertEquals(Page.WELCOME, start.rootPage)
        assertFalse(start.handlesBack)
        assertNull(start.back())
    }

    @Test
    fun `choosing a provider opens its page and clears old errors`() {
        val failed = start.copy(problem = Problem(Problem.Kind.NETWORK), emailError = InputError.INVALID)
        val riseup = failed.choose(Provider.RISEUP, emptyList())
        assertEquals(Page.RISEUP, riseup.page)
        assertEquals(Provider.RISEUP, riseup.provider)
        assertNull(riseup.problem)
        assertNull(riseup.emailError)
        val gmail = start.choose(Provider.GMAIL, listOf(Method.PASSWORD))
        assertEquals(Page.GMAIL, gmail.page)
        assertEquals(listOf(Method.PASSWORD), gmail.methods)
        assertTrue(gmail.handlesBack)
    }

    @Test
    fun `back from a provider page returns to a fresh welcome`() {
        val riseup = start.choose(Provider.RISEUP, emptyList()).failed(Problem(Problem.Kind.WRONG_PASSWORD))
        assertEquals(SetupState(), riseup.back())
    }

    @Test
    fun `back cancels a test and keeps the page`() {
        val testing = start.choose(Provider.RISEUP, emptyList()).start(Busy.TESTING)
        assertTrue(testing.handlesBack)
        val cancelled = testing.back()!!
        assertNull(cancelled.busy)
        assertEquals(Page.RISEUP, cancelled.page)
    }

    @Test
    fun `back does nothing while saving`() {
        val saving = start.choose(Provider.RISEUP, emptyList()).tested(riseupAccount, false).start(Busy.SAVING)
        assertSame(saving, saving.back())
    }

    @Test
    fun `start clears the problem and the field errors`() {
        val state = start.choose(Provider.RISEUP, emptyList())
            .invalid(InputError.REQUIRED, InputError.REQUIRED)
            .failed(Problem(Problem.Kind.NETWORK))
            .start(Busy.TESTING)
        assertNull(state.problem)
        assertNull(state.emailError)
        assertNull(state.passwordError)
    }

    @Test
    fun `a failure ends the work and shows the problem on the same page`() {
        val state = start.choose(Provider.GMAIL, listOf(Method.PASSWORD)).start(Busy.TESTING)
            .failed(Problem(Problem.Kind.APP_PASSWORD_REQUIRED))
        assertEquals(Page.GMAIL, state.page)
        assertNull(state.busy)
        assertEquals(Problem.Kind.APP_PASSWORD_REQUIRED, state.problem?.kind)
    }

    @Test
    fun `a passed test opens the options, and back drops the sign-in`() {
        val options = start.choose(Provider.GMAIL, listOf(Method.BROWSER)).start(Busy.TESTING)
            .tested(gmailAccount, showIpNote = true)
        assertEquals(Page.OPTIONS, options.page)
        assertSame(gmailAccount, options.signedIn)
        assertTrue(options.showIpNote)
        assertNull(options.busy)
        val back = options.back()!!
        assertEquals(Page.GMAIL, back.page)
        assertNull(back.signedIn)
    }

    @Test
    fun `an added account asks for the grants, and back from there ends the flow`() {
        val permissions = start.choose(Provider.RISEUP, emptyList()).tested(riseupAccount, false)
            .start(Busy.SAVING).added()
        assertEquals(Page.PERMISSIONS, permissions.page)
        assertNull(permissions.busy)
        assertTrue(permissions.handlesBack)
        assertTrue(permissions.back()!!.done)
    }

    @Test
    fun `the own client page returns to the Gmail page`() {
        val gmail = start.choose(Provider.GMAIL, listOf(Method.PASSWORD))
        val client = gmail.ownClient().copy(clientIdError = true)
        assertEquals(Page.OWN_CLIENT, client.page)
        val back = client.back()!!
        assertEquals(Page.GMAIL, back.page)
        assertFalse(back.clientIdError)
        val saved = client.clientSaved(listOf(Method.BROWSER, Method.PASSWORD))
        assertEquals(Page.GMAIL, saved.page)
        assertEquals(listOf(Method.BROWSER, Method.PASSWORD), saved.methods)
    }

    @Test
    fun `sign in again starts on the provider page and saves after the test`() {
        val reauth = SetupState.signInAgain(account(AuthKind.PASSWORD, Provider.RISEUP), emptyList())
        assertEquals(Page.RISEUP, reauth.page)
        assertEquals(Page.RISEUP, reauth.rootPage)
        assertFalse(reauth.handlesBack)
        assertNull(reauth.back())
        val saving = reauth.start(Busy.TESTING).tested(riseupAccount, showIpNote = true)
        assertEquals(Busy.SAVING, saving.busy)
        assertEquals(Page.RISEUP, saving.page)
        assertFalse(saving.showIpNote)
        val saved = saving.reauthSaved()
        assertEquals(Page.SIGNED_IN, saved.page)
        assertTrue(saved.back()!!.done)
    }

    @Test
    fun `sign in again offers the own client only for the OAuth methods`() {
        assertTrue(start.offersOwnClient)
        assertFalse(SetupState.signInAgain(account(AuthKind.PASSWORD), emptyList()).offersOwnClient)
        assertTrue(SetupState.signInAgain(account(AuthKind.OAUTH), emptyList()).offersOwnClient)
        assertTrue(SetupState.signInAgain(account(AuthKind.DEVICE), emptyList()).offersOwnClient)
    }

    @Test
    fun `Gmail methods when adding`() {
        assertEquals(listOf(Method.PASSWORD), methods(device = false, browser = false, reauth = null))
        assertEquals(listOf(Method.BROWSER, Method.PASSWORD), methods(device = false, browser = true, reauth = null))
        val all = listOf(Method.DEVICE, Method.BROWSER, Method.PASSWORD)
        assertEquals(all, methods(device = true, browser = true, reauth = null))
    }

    @Test
    fun `Gmail methods when signing in again are the account's own`() {
        assertEquals(listOf(Method.PASSWORD), methods(device = true, browser = true, reauth = AuthKind.PASSWORD))
        assertEquals(listOf(Method.BROWSER), methods(device = true, browser = true, reauth = AuthKind.OAUTH))
        assertEquals(emptyList(), methods(device = true, browser = false, reauth = AuthKind.OAUTH))
        assertEquals(listOf(Method.DEVICE), methods(device = true, browser = true, reauth = AuthKind.DEVICE))
        assertEquals(emptyList(), methods(device = false, browser = true, reauth = AuthKind.DEVICE))
    }

    private fun methods(device: Boolean, browser: Boolean, reauth: AuthKind?) =
        SetupState.gmailMethods(device, browser, reauth)

    @Test
    fun `a browser result completes only when the flow can use it`() {
        assertTrue(start.acceptsBrowserResult(reauthMode = false))
        val gmail = start.choose(Provider.GMAIL, listOf(Method.BROWSER))
        assertTrue(gmail.start(Busy.BROWSER).acceptsBrowserResult(reauthMode = false))
        assertFalse(gmail.start(Busy.TESTING).acceptsBrowserResult(reauthMode = false))
        assertFalse(gmail.tested(gmailAccount, false).acceptsBrowserResult(reauthMode = false))
        assertFalse(start.choose(Provider.RISEUP, emptyList()).acceptsBrowserResult(reauthMode = false))
        assertFalse(start.finished().acceptsBrowserResult(reauthMode = false))
        // Sign in again: only an OAuth account, and not before it has loaded.
        assertFalse(start.acceptsBrowserResult(reauthMode = true))
        assertTrue(SetupState.signInAgain(account(AuthKind.OAUTH), emptyList()).acceptsBrowserResult(reauthMode = true))
        assertFalse(SetupState.signInAgain(account(AuthKind.PASSWORD), emptyList()).acceptsBrowserResult(reauthMode = true))
    }

    @Test
    fun `signed in hides its secret`() {
        assertEquals("SignedIn(RISEUP, PASSWORD)", riseupAccount.toString())
    }

    private fun account(auth: AuthKind, provider: Provider = Provider.GMAIL) = Account(
        id = 7, provider = provider, email = "jane@gmail.com", senderName = "", label = "Gmail", color = 0,
        auth = auth, deviceAccount = null, bodyDays = 30, pushMode = PushMode.INSTANT, pollMinutes = 15,
        gmailCategories = setOf("primary"), archiveFolder = null, signature = "", state = AccountState.SIGN_IN_NEEDED,
        stateText = null, serverKeepaliveS = null, quotaUsed = null, quotaLimit = null, sort = 0,
    )
}
