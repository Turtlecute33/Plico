package app.plico.ui.setup

import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.PushMode
import app.plico.data.Provider
import app.plico.ui.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthResumeTest {
    @Test
    fun `a sign-in for an OAuth account of Plico continues as sign in again`() {
        assertEquals(Route.Reauth(4), OAuthResume.routeFor(account(AuthKind.OAUTH)))
    }

    @Test
    fun `any other sign-in continues in setup`() {
        assertEquals(Route.Setup, OAuthResume.routeFor(null))
        assertEquals(Route.Setup, OAuthResume.routeFor(account(AuthKind.PASSWORD)))
        assertEquals(Route.Setup, OAuthResume.routeFor(account(AuthKind.DEVICE)))
    }

    private fun account(auth: AuthKind) = Account(
        id = 4, provider = Provider.GMAIL, email = "jane@gmail.com", senderName = "", label = "Gmail", color = 0,
        auth = auth, deviceAccount = null, bodyDays = 30, pushMode = PushMode.INSTANT, pollMinutes = 15,
        gmailCategories = setOf("primary"), archiveFolder = null, signature = "", state = AccountState.OK,
        stateText = null, serverKeepaliveS = null, quotaUsed = null, quotaLimit = null, sort = 0,
    )
}
