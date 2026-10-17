package app.plico.ui.settings

import app.plico.R
import app.plico.account.Providers
import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Prefs
import app.plico.data.Provider
import app.plico.data.PushMode
import app.plico.data.Servers
import app.plico.html.LogoRules
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import app.plico.push.Grants
import app.plico.push.PushStatus
import app.plico.sync.GmailSync
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsModelTest {
    private val healthy = PushStatus(
        running = true,
        pausedByUser = false,
        stoppedBySystem = false,
        hasNetwork = true,
        grants = Grants(notifications = true, batteryUnrestricted = true, exactAlarms = true, backgroundRestricted = false),
    )

    private fun PushStatus.with(change: Grants.() -> Grants) = copy(grants = grants.change())

    @Test
    fun choicesFollowTheSpec() {
        assertEquals(listOf(0, 5, 10, 20), Choices.UNDO_SEND_SECONDS)
        assertEquals(listOf(0, 1, 5, 30), Choices.LOCK_TIMEOUT_MINUTES)
        assertEquals(listOf(15, 30, 60), Choices.POLL_MINUTES)
        assertEquals(listOf(30, 90, 365, 0), Choices.BODY_DAYS)
    }

    @Test
    fun everyChoiceHasItsOwnText() {
        assertDistinct(PushMode.entries.map { it.title() })
        assertDistinct(PushMode.entries.map { it.description() })
        assertDistinct(Prefs.Swipe.entries.map { it.title() })
        // Every category that the sync filter knows has a title: one list for both (GmailSync.CATEGORIES).
        assertDistinct(GmailSync.CATEGORIES.map(::gmailCategoryTitle))
        assertDistinct(PushState.entries.map { it.text })
        assertDistinct(IdentityProblem.entries.map { it.text })
        assertDistinct(PushCheck.entries.flatMap { listOf(it.texts(true), it.texts(false)) }.toSet().map { it.first })
    }

    @Test
    fun signInTitleDependsOnProviderAndMethod() {
        assertDistinct(
            listOf(
                signInTitle(Provider.RISEUP, AuthKind.PASSWORD),
                signInTitle(Provider.GMAIL, AuthKind.PASSWORD),
                signInTitle(Provider.GMAIL, AuthKind.OAUTH),
                signInTitle(Provider.GMAIL, AuthKind.DEVICE),
                signInTitle(Provider.MICROSOFT, AuthKind.OAUTH),
            ),
        )
        assertEquals(R.string.settings_auth_browser_microsoft, signInTitle(Provider.MICROSOFT, AuthKind.OAUTH))
        assertEquals(R.string.settings_auth_browser, signInTitle(Provider.GMAIL, AuthKind.OAUTH))
        assertEquals(R.string.settings_auth_password, signInTitle(Provider.OTHER, AuthKind.PASSWORD))
    }

    @Test
    fun identitiesNoteByProvider() {
        assertEquals(R.string.settings_identities_gmail, identitiesNote(Provider.GMAIL))
        assertEquals(R.string.settings_identities_riseup, identitiesNote(Provider.RISEUP))
        assertEquals(R.string.settings_identities_other, identitiesNote(Provider.OTHER))
        assertEquals(R.string.settings_identities_other, identitiesNote(Provider.MICROSOFT))
    }

    @Test
    fun serverSettingsOnlyForOtherServersWithAPassword() {
        assertTrue(canChangeServers(account(Provider.OTHER, AuthKind.PASSWORD)))
        assertFalse(canChangeServers(account(Provider.RISEUP, AuthKind.PASSWORD)))
        assertFalse(canChangeServers(account(Provider.GMAIL, AuthKind.PASSWORD)))
        assertFalse(canChangeServers(account(Provider.MICROSOFT, AuthKind.OAUTH)))
        assertEquals(R.string.setup_security_tls, securityTitle(TlsMode.IMPLICIT))
        assertEquals(R.string.setup_security_starttls, securityTitle(TlsMode.STARTTLS))
    }

    @Test
    fun onlyProblemStatesHaveATitle() {
        assertNull(AccountState.OK.problemTitle())
        assertDistinct(AccountState.entries.filter { it != AccountState.OK }.map { it.problemTitle() })
    }

    @Test
    fun wakesFromTheServerKeepalive() {
        // Dovecot's default of 2 min (DESIGN §8.3).
        assertEquals(720, wakesPerDay(120))
        assertEquals(288, wakesPerDay(300))
        assertEquals(1, wakesPerDay(86_400))
        assertEquals(15, wakesPerDay(5_890))
        assertNull(wakesPerDay(null))
        assertNull(wakesPerDay(0))
        assertNull(wakesPerDay(-5))
    }

    @Test
    fun checksWhenPolling() {
        assertEquals(96, pollsPerDay(15))
        assertEquals(48, pollsPerDay(30))
        assertEquals(24, pollsPerDay(60))
        assertEquals(0, pollsPerDay(0))
    }

    @Test
    fun estimateFollowsTheMode() {
        assertEquals(WakeEstimate(720, null), wakeEstimate(PushMode.INSTANT, 120, 15))
        assertEquals(WakeEstimate(720, 48), wakeEstimate(PushMode.INSTANT_ON_WIFI, 120, 30))
        assertEquals(WakeEstimate(null, 24), wakeEstimate(PushMode.POLL, 120, 60))
        assertEquals(WakeEstimate(null, null), wakeEstimate(PushMode.INSTANT, null, 15))
        assertEquals(WakeEstimate(null, 96), wakeEstimate(PushMode.INSTANT_ON_WIFI, null, 15))
    }

    @Test
    fun keepaliveShowsMinutesFrom90Seconds() {
        assertNull(roundedMinutes(45))
        assertNull(roundedMinutes(89))
        assertEquals(2, roundedMinutes(90))
        assertEquals(2, roundedMinutes(119))
        assertEquals(2, roundedMinutes(149))
        assertEquals(3, roundedMinutes(150))
        assertEquals(29, roundedMinutes(29 * 60))
    }

    @Test
    fun quotaShare() {
        assertEquals(0.25f, quotaFraction(256, 1024))
        assertEquals(1f, quotaFraction(2048, 1024))
        assertNull(quotaFraction(null, 1024))
        assertNull(quotaFraction(10, null))
        assertNull(quotaFraction(10, 0))
    }

    @Test
    fun labelsAreOneCleanLine() {
        assertEquals("Work mail", cleanLabel("  Work\r\nmail\u0000 "))
        assertNull(cleanLabel(" \t\n"))
        assertEquals(Choices.LABEL_MAX, cleanLabel("x".repeat(100))!!.length)
        assertEquals("Ada Lovelace", cleanLine("Ada\u007FLovelace", Choices.NAME_MAX))
        assertEquals("", cleanLine("   ", Choices.NAME_MAX))
    }

    @Test
    fun signatureKeepsLinesButNotTrailingSpace() {
        assertEquals("Ada\n\nhttps://example.org", cleanSignature("Ada\r\n\r\nhttps://example.org \n\n"))
        assertEquals("a\nb", cleanSignature("a\rb"))
        assertEquals(Choices.SIGNATURE_MAX, cleanSignature("y".repeat(5_000)).length)
    }

    @Test
    fun clientIdForm() {
        val id = "123456789012-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com"
        assertEquals(id, ClientIds.normalize(id))
        assertEquals(id, ClientIds.normalize("  ${id.substring(0, 20)}\n${id.substring(20)} "))
        assertEquals("", ClientIds.normalize(""))
        assertEquals("", ClientIds.normalize("  \n"))
        assertNull(ClientIds.normalize("abc.apps.googleusercontent.com"))
        assertNull(ClientIds.normalize("123-abc.apps.googleusercontent.com.evil.example"))
        assertNull(ClientIds.normalize("123-ABC.apps.googleusercontent.com"))
        assertNull(ClientIds.normalize("https://accounts.google.com"))
        // Setup saves with this rule too (it accepted upper case before, which Settings then refused).
        assertNull(ClientIds.normalize("GOCSPX-secret"))
    }

    @Test
    fun microsoftClientIdIsAGuidInLowerCase() {
        val id = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        assertEquals(id, ClientIds.normalizeMicrosoft(id))
        assertEquals(id, ClientIds.normalizeMicrosoft("  0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0\n"))
        assertEquals(id, ClientIds.normalizeMicrosoft("0f1e2d3c-4b5a-6978- 8796-a5b4c3d2e1f0"))
        assertEquals("", ClientIds.normalizeMicrosoft(" "))
        assertNull(ClientIds.normalizeMicrosoft("0f1e2d3c4b5a69788796a5b4c3d2e1f0"))
        assertNull(ClientIds.normalizeMicrosoft("{0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0}"))
        assertNull(ClientIds.normalizeMicrosoft("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1fg"))
        assertNull(ClientIds.normalizeMicrosoft("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0-1"))
        assertNull(ClientIds.normalizeMicrosoft("123-abc.apps.googleusercontent.com"))
        // The Google rule does not take a GUID.
        assertNull(ClientIds.normalize(id))
    }

    @Test
    fun signInClientsShowWhenUsedOrSet() {
        val riseup = account(Provider.RISEUP, AuthKind.PASSWORD)
        assertFalse(showsSignInClients(emptyList(), "", ""))
        assertFalse(showsSignInClients(listOf(riseup, account(Provider.GMAIL, AuthKind.PASSWORD)), "", " "))
        assertTrue(showsSignInClients(listOf(riseup, account(Provider.GMAIL, AuthKind.OAUTH)), "", ""))
        assertTrue(showsSignInClients(listOf(account(Provider.GMAIL, AuthKind.DEVICE)), "", ""))
        assertTrue(showsSignInClients(listOf(account(Provider.MICROSOFT, AuthKind.OAUTH)), "", ""))
        assertTrue(showsSignInClients(emptyList(), "1-a.apps.googleusercontent.com", ""))
        assertTrue(showsSignInClients(emptyList(), "", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"))
        assertFalse(usesGoogleClient(listOf(account(Provider.GMAIL, AuthKind.PASSWORD))))
        assertTrue(usesMicrosoftClient(listOf(account(Provider.MICROSOFT, AuthKind.OAUTH))))
    }

    @Test
    fun identityChecks() {
        val own = "me@riseup.net"
        val existing = listOf("alias@riseup.net")
        assertNull(identityProblem(" other@riseup.net ", own, existing))
        assertEquals(IdentityProblem.INVALID, identityProblem("not an address", own, existing))
        assertEquals(IdentityProblem.INVALID, identityProblem("a@b\r\nBcc: x@y", own, existing))
        assertEquals(IdentityProblem.INVALID, identityProblem("", own, existing))
        assertEquals(IdentityProblem.OWN_ADDRESS, identityProblem("ME@riseup.net", own, existing))
        assertEquals(IdentityProblem.EXISTS, identityProblem("Alias@Riseup.net", own, existing))
    }

    @Test
    fun categoriesKeepOrderAndAtLeastOne() {
        assertEquals(listOf("primary", "updates"), withCategory(setOf("updates"), "primary", true).toList())
        assertEquals(setOf("primary"), withCategory(setOf("primary", "social"), "social", false))
        assertEquals(setOf("primary"), withCategory(setOf("primary"), "primary", false))
        assertEquals(setOf("primary"), withCategory(setOf("primary"), "unknown", true))
    }

    @Test
    fun healthyPushShowsOnlyGrantedChecks() {
        assertEquals(
            listOf(PushCheck.NOTIFICATIONS to true, PushCheck.BATTERY to true),
            pushChecks(healthy),
        )
        assertEquals(PushState.RUNNING, pushState(healthy, hasAccounts = true))
    }

    @Test
    fun exactAlarmsAreAskedOnlyWithoutTheExemption() {
        val optimized = healthy.with { copy(batteryUnrestricted = false) }
        assertEquals(
            listOf(PushCheck.NOTIFICATIONS to true, PushCheck.BATTERY to false, PushCheck.EXACT_ALARMS to true),
            pushChecks(optimized),
        )
        assertEquals(
            listOf(
                PushCheck.NOTIFICATIONS to true,
                PushCheck.BATTERY to false,
                PushCheck.EXACT_ALARMS to false,
                PushCheck.MAY_STOP to false,
            ),
            pushChecks(optimized.with { copy(exactAlarms = false) }),
        )
    }

    @Test
    fun restrictedComesFirst() {
        val checks = pushChecks(healthy.with { copy(backgroundRestricted = true, notifications = false) })
        assertEquals(PushCheck.RESTRICTED to false, checks.first())
        assertTrue(PushCheck.NOTIFICATIONS to false in checks)
    }

    @Test
    fun pushStateOrder() {
        assertEquals(PushState.NO_ACCOUNTS, pushState(healthy.copy(pausedByUser = true), hasAccounts = false))
        assertEquals(PushState.PAUSED, pushState(healthy.copy(pausedByUser = true, stoppedBySystem = true), true))
        assertEquals(PushState.STOPPED, pushState(healthy.copy(running = false, stoppedBySystem = true), true))
        assertEquals(PushState.RESTRICTED, pushState(healthy.with { copy(backgroundRestricted = true) }, true))
        assertEquals(PushState.NOT_RUNNING, pushState(healthy.copy(running = false, hasNetwork = false), true))
        assertEquals(PushState.NO_NETWORK, pushState(healthy.copy(hasNetwork = false), true))
    }

    @Test
    fun connectionsWithoutAccountsListOnlySetup() {
        val connections = connections(emptyList(), "", "", null)
        assertEquals(
            listOf(Connection(R.string.settings_about_setup, emptyList(), R.string.settings_about_setup_text)),
            connections,
        )
    }

    @Test
    fun connectionsListTheServersOfEveryAccount() {
        val riseup = account(Provider.RISEUP, AuthKind.PASSWORD, Providers.RISEUP_SERVERS)
        val gmail = account(Provider.GMAIL, AuthKind.PASSWORD, Providers.GMAIL_SERVERS)
        val other = account(
            Provider.OTHER, AuthKind.PASSWORD,
            Servers(Endpoint("mail.example.org", 143, TlsMode.STARTTLS), Endpoint("mail.example.org", 587, TlsMode.STARTTLS)),
        )
        val second = account(Provider.RISEUP, AuthKind.PASSWORD, Providers.RISEUP_SERVERS)
        val connections = connections(listOf(riseup, gmail, other, second), "", "", null)
        assertEquals(
            listOf(
                "mail.riseup.net:993", "mail.riseup.net:465", "imap.gmail.com:993", "smtp.gmail.com:465",
                "mail.example.org:143", "mail.example.org:587",
            ),
            connections.first().hosts,
        )
        assertEquals(R.string.settings_about_mail_servers, connections.first().title)
        // An app password needs no sign-in host.
        assertTrue(connections.none { it.title == R.string.settings_about_google_sign_in })
        assertDistinct(connections.map { it.title })
    }

    @Test
    fun connectionsListTheSignInHostsOfUsedOrSetClients() {
        val google = connections(listOf(account(Provider.GMAIL, AuthKind.OAUTH, Providers.GMAIL_SERVERS)), "", "", null)
            .single { it.title == R.string.settings_about_google_sign_in }
        assertEquals(listOf("accounts.google.com", "oauth2.googleapis.com"), google.hosts)
        assertTrue(connections(emptyList(), "1-a.apps.googleusercontent.com", "", null).any { it.hosts == google.hosts })
        val microsoftAccount = account(Provider.MICROSOFT, AuthKind.OAUTH, Providers.OUTLOOK_SERVERS)
        val microsoft = connections(listOf(microsoftAccount), "", "", null)
        assertEquals(
            listOf("outlook.office365.com:993", "smtp-mail.outlook.com:587"),
            microsoft.single { it.title == R.string.settings_about_mail_servers }.hosts,
        )
        assertEquals(
            listOf("login.microsoftonline.com"),
            microsoft.single { it.title == R.string.settings_about_microsoft_sign_in }.hosts,
        )
        assertTrue(microsoft.none { it.title == R.string.settings_about_google_sign_in })
        val set = connections(emptyList(), "", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", null)
        assertTrue(set.any { it.title == R.string.settings_about_microsoft_sign_in })
    }

    @Test
    fun connectionsListTheLogoSourceOnlyWhenLogosAreOn() {
        assertTrue(connections(emptyList(), "", "", null).none { it.title == R.string.settings_about_logos })
        val ddg = connections(emptyList(), "", "", Prefs.LogoSource.DUCKDUCKGO).single { it.title == R.string.settings_about_logos }
        assertEquals(LogoRules.hosts(Prefs.LogoSource.DUCKDUCKGO), ddg.hosts)
        assertNull(ddg.text)
        val google = connections(emptyList(), "", "", Prefs.LogoSource.GOOGLE).single { it.title == R.string.settings_about_logos }
        assertEquals(listOf("www.google.com", "*.gstatic.com"), google.hosts)
        val website = connections(emptyList(), "", "", Prefs.LogoSource.WEBSITE).single { it.title == R.string.settings_about_logos }
        assertEquals(emptyList(), website.hosts)
        assertEquals(R.string.settings_about_logos_websites, website.text)
    }

    @Test
    fun certificateTransparencyFromAndroid16() {
        assertFalse(certificateTransparency(35))
        assertTrue(certificateTransparency(36))
        assertTrue(certificateTransparency(37))
    }

    private fun account(provider: Provider, auth: AuthKind, servers: Servers = Servers.NONE) = Account(
        id = 1, provider = provider, email = "jane@example.org", senderName = "", label = "Mail", color = 0,
        auth = auth, deviceAccount = null, bodyDays = 30, pushMode = PushMode.INSTANT, pollMinutes = 15,
        gmailCategories = setOf("primary"), archiveFolder = null, signature = "", state = AccountState.OK,
        stateText = null, serverKeepaliveS = null, quotaUsed = null, quotaLimit = null, sort = 0, servers = servers,
    )

    private fun assertDistinct(values: List<Any?>) {
        assertTrue(values.none { it == null || it == 0 }, "missing text in $values")
        assertEquals(values.size, values.toSet().size, "duplicate in $values")
    }
}
