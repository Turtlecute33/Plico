package app.plico.ui.setup

import app.plico.account.OAuthIssuer
import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Provider
import app.plico.data.PushMode
import app.plico.ui.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthResumeTest {
    @Test
    fun `a sign-in for an OAuth account of Plico continues as sign in again`() {
        assertEquals(Route.Reauth(4), OAuthResume.routeFor(account(AuthKind.OAUTH)))
        assertEquals(Route.Reauth(4), OAuthResume.routeFor(account(AuthKind.OAUTH), OAuthIssuer.GOOGLE))
        val microsoft = account(AuthKind.OAUTH, Provider.MICROSOFT)
        assertEquals(Route.Reauth(4), OAuthResume.routeFor(microsoft, OAuthIssuer.MICROSOFT))
    }

    @Test
    fun `a sign-in of another issuer than the account's continues in setup`() {
        assertEquals(Route.Setup, OAuthResume.routeFor(account(AuthKind.OAUTH), OAuthIssuer.MICROSOFT))
        assertEquals(Route.Setup, OAuthResume.routeFor(account(AuthKind.OAUTH, Provider.MICROSOFT), OAuthIssuer.GOOGLE))
        // Old pending entries without an issuer are Google's.
        assertEquals(Route.Setup, OAuthResume.routeFor(account(AuthKind.OAUTH, Provider.MICROSOFT)))
    }

    @Test
    fun `any other sign-in continues in setup`() {
        assertEquals(Route.Setup, OAuthResume.routeFor(null))
        assertEquals(Route.Setup, OAuthResume.routeFor(null, OAuthIssuer.MICROSOFT))
        assertEquals(Route.Setup, OAuthResume.routeFor(account(AuthKind.PASSWORD)))
        assertEquals(Route.Setup, OAuthResume.routeFor(account(AuthKind.DEVICE)))
        assertEquals(Route.Setup, OAuthResume.routeFor(account(AuthKind.PASSWORD, Provider.OTHER), OAuthIssuer.GOOGLE))
    }

    private fun account(auth: AuthKind, provider: Provider = Provider.GMAIL) = Account(
        id = 4, provider = provider, email = "jane@example.org", senderName = "", label = "Mail", color = 0,
        auth = auth, deviceAccount = null, bodyDays = 30, pushMode = PushMode.INSTANT, pollMinutes = 15,
        gmailCategories = setOf("primary"), archiveFolder = null, signature = "", state = AccountState.OK,
        stateText = null, serverKeepaliveS = null, quotaUsed = null, quotaLimit = null, sort = 0,
    )
}
