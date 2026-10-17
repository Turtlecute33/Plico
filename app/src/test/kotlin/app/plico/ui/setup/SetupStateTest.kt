package app.plico.ui.setup

import app.plico.R
import app.plico.account.OAuthIssuer
import app.plico.account.ProviderTable
import app.plico.account.Providers
import app.plico.account.discovery.Discovery
import app.plico.account.discovery.Outcome
import app.plico.account.discovery.Source
import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Provider
import app.plico.data.PushMode
import app.plico.data.Servers
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SetupStateTest {
    private val start = SetupState()
    private val other = Servers(
        Endpoint("imap.example.org", 993, TlsMode.IMPLICIT),
        Endpoint("smtp.example.org", 587, TlsMode.STARTTLS),
    )
    private val riseupAccount = SignedIn(
        Provider.RISEUP, "joe@riseup.net", AuthKind.PASSWORD, byteArrayOf(1), null, Providers.RISEUP_SERVERS, true,
    )
    private val gmailAccount = SignedIn(
        Provider.GMAIL, "jane@gmail.com", AuthKind.OAUTH, byteArrayOf(2), null, Providers.GMAIL_SERVERS, true,
    )
    private val noMethods: (Provider) -> List<Method> = { emptyList() }

    private fun found(
        provider: Provider,
        servers: Servers,
        source: Source = Source.TABLE,
        notes: Set<ProviderTable.Note> = emptySet(),
    ) = Outcome.Found(provider, servers, source, notes)

    private fun detected(outcome: Outcome.Found, methods: List<Method> = emptyList()) =
        start.welcomed("jo@example.org", "example.org", known = false, suggestion = null, manual = false)
            .found(outcome, methods)

    @Test
    fun `welcome is the root when adding`() {
        assertEquals(Page.WELCOME, start.rootPage)
        assertFalse(start.handlesBack)
        assertNull(start.back())
    }

    @Test
    fun `next detects the servers, and cancel returns to welcome`() {
        val detecting = start.copy(emailError = InputError.INVALID)
            .welcomed("jo@example.org", "example.org", known = false, suggestion = null, manual = false)
        assertEquals(Busy.DETECTING, detecting.busy)
        assertEquals(Page.WELCOME, detecting.page)
        assertNull(detecting.emailError)
        assertTrue(detecting.handlesBack)
        val cancelled = detecting.back()!!
        assertNull(cancelled.busy)
        assertEquals(Page.WELCOME, cancelled.page)
    }

    @Test
    fun `an address that is already added stops before detection, also for manual entry`() {
        for (manual in listOf(false, true)) {
            val state = start.copy(emailError = InputError.INVALID)
                .welcomed("jo@example.org", "example.org", known = true, suggestion = "x.org", manual)
            assertEquals(Problem(Problem.Kind.ALREADY_ADDED), state.problem)
            assertEquals(Page.WELCOME, state.page)
            assertNull(state.busy)
            // The old field error does not stay next to the note.
            assertNull(state.emailError)
        }
    }

    @Test
    fun `a typo asks once, and Next again with the same address continues`() {
        val typo = start.copy(emailError = InputError.INVALID)
            .welcomed("jo@gmial.com", "gmial.com", known = false, suggestion = "gmail.com", manual = false)
        assertEquals(Problem(Problem.Kind.TYPO, "gmail.com"), typo.problem)
        assertEquals(Page.WELCOME, typo.page)
        assertNull(typo.busy)
        assertNull(typo.emailError)
        assertEquals("jo@gmial.com", typo.typoChecked)
        val again = typo.welcomed("jo@gmial.com", "gmial.com", known = false, suggestion = "gmail.com", manual = false)
        assertEquals(Busy.DETECTING, again.busy)
        assertNull(again.problem)
        // Another address is checked again.
        val changed = typo.welcomed("al@gmial.com", "gmial.com", known = false, suggestion = "gmail.com", manual = false)
        assertEquals(Problem.Kind.TYPO, changed.problem?.kind)
    }

    @Test
    fun `manual entry opens the server settings with the guess`() {
        val manual = start.welcomed("jo@example.org", "example.org", known = false, suggestion = null, manual = true)
        assertEquals(Page.SERVERS, manual.page)
        assertEquals(Provider.OTHER, manual.provider)
        assertEquals(Discovery.guess("example.org"), manual.servers)
        assertEquals(Source.MANUAL, manual.source)
        assertEquals(R.string.setup_servers_text, serversText(manual.source))
        assertNull(manual.problem)
        assertEquals(SetupState(), manual.back())
    }

    @Test
    fun `found servers open the page of the provider`() {
        val password = detected(found(Provider.OTHER, other, Source.AUTOCONFIG))
        assertEquals(Page.PASSWORD, password.page)
        assertEquals(other, password.servers)
        assertEquals(Source.AUTOCONFIG, password.source)
        assertNull(password.busy)
        assertTrue(password.offersServerChange)
        val riseup = detected(found(Provider.RISEUP, Providers.RISEUP_SERVERS))
        assertEquals(Page.PASSWORD, riseup.page)
        assertFalse(riseup.offersServerChange)
        val google = detected(found(Provider.GMAIL, Providers.GMAIL_SERVERS), listOf(Method.PASSWORD))
        assertEquals(Page.GOOGLE, google.page)
        assertEquals(listOf(Method.PASSWORD), google.methods)
        assertEquals(OAuthIssuer.GOOGLE, google.issuer)
        val notes = setOf(ProviderTable.Note.ENABLE_IMAP)
        val microsoft = detected(found(Provider.MICROSOFT, Providers.OUTLOOK_SERVERS, notes = notes))
        assertEquals(Page.MICROSOFT, microsoft.page)
        assertEquals(OAuthIssuer.MICROSOFT, microsoft.issuer)
        assertEquals(notes, microsoft.notes)
    }

    @Test
    fun `back from a sign-in page returns to welcome and keeps the retry state`() {
        for (provider in listOf(Provider.OTHER, Provider.GMAIL, Provider.MICROSOFT)) {
            val page = detected(found(provider, other)).copy(loginRetried = true).failed(Problem(Problem.Kind.NETWORK))
            val back = page.back()!!
            assertEquals(Page.WELCOME, back.page)
            assertNull(back.problem)
            assertNull(back.provider)
            assertTrue(back.loginRetried)
        }
    }

    @Test
    fun `refused providers stay on welcome with their name`() {
        val bridge = start.detecting().refused(Outcome.Refused("Proton Mail", ProviderTable.Refusal.BRIDGE))
        assertEquals(Page.WELCOME, bridge.page)
        assertNull(bridge.busy)
        assertEquals(Problem(Problem.Kind.BRIDGE, "Proton Mail"), bridge.problem)
        val tuta = start.detecting().refused(Outcome.Refused("Tuta", ProviderTable.Refusal.NO_IMAP))
        assertEquals(Problem(Problem.Kind.UNSUPPORTED, "Tuta"), tuta.problem)
    }

    @Test
    fun `nothing found opens the server settings with the guess`() {
        val guess = Discovery.guess("example.invalid")
        val state = start.detecting().manual(guess, Source.GUESS, emptySet())
        assertEquals(Page.SERVERS, state.page)
        assertEquals(guess, state.servers)
        assertNull(state.busy)
        assertEquals(R.string.setup_servers_not_found, serversText(state.source))
    }

    @Test
    fun `values from DNS outside the domain go through the server settings with the DNS text`() {
        for (source in listOf(Source.SRV, Source.MX)) {
            val state = start.detecting().manual(other, source, emptySet())
            assertEquals(Page.SERVERS, state.page)
            assertEquals(R.string.setup_servers_dns, serversText(state.source))
        }
    }

    @Test
    fun `an iCloud custom domain shows the user name note on the server settings`() {
        val notes = setOf(ProviderTable.Note.APP_PASSWORD, ProviderTable.Note.ICLOUD_LOGIN)
        val state = start.detecting().manual(other, Source.MX, notes)
        assertEquals(notes, state.notes)
        assertEquals(ProviderTable.Note.ICLOUD_LOGIN, SERVER_NOTES.first { it in state.notes })
        assertEquals(R.string.setup_note_icloud_login, noteText(ProviderTable.Note.ICLOUD_LOGIN))
        // The user-name note is only for the server settings page.
        assertFalse(ProviderTable.Note.ICLOUD_LOGIN in PASSWORD_NOTES)
    }

    @Test
    fun `entered servers lead to the password page, and back returns to them`() {
        val servers = start.detecting().manual(other, Source.GUESS, emptySet())
        val password = servers.serversEntered(other, "jo@example.org", noMethods)
        assertEquals(Page.PASSWORD, password.page)
        assertEquals(other, password.servers)
        assertEquals(Source.MANUAL, password.source)
        assertTrue(password.fromServers)
        val back = password.back()!!
        assertEquals(Page.SERVERS, back.page)
        assertFalse(back.fromServers)
        assertEquals(Page.WELCOME, back.back()!!.page)
    }

    @Test
    fun `a mixed pair of built-in and other servers is refused`() {
        val servers = start.manual(other, Source.MANUAL, emptySet())
        val gmail = Servers(
            Endpoint("imap.gmail.com", 993, TlsMode.IMPLICIT),
            Endpoint("smtp.evil.example", 465, TlsMode.IMPLICIT),
        )
        val refused = servers.serversEntered(gmail, "jo@example.org", noMethods)
        assertEquals(Page.SERVERS, refused.page)
        assertEquals(Problem(Problem.Kind.MIXED_SERVERS, "Gmail"), refused.problem)
        val outlook = Servers(
            Endpoint("mail.example.org", 993, TlsMode.IMPLICIT),
            Endpoint("smtp.office365.com", 587, TlsMode.STARTTLS),
        )
        assertEquals(
            Problem(Problem.Kind.MIXED_SERVERS, "Microsoft"),
            servers.serversEntered(outlook, "jo@example.org", noMethods).problem,
        )
    }

    @Test
    fun `typed Google hosts lead to the Google page with the built-in servers`() {
        val typed = Servers(
            Endpoint("imap.googlemail.com", 143, TlsMode.STARTTLS),
            Endpoint("smtp.googlemail.com", 587, TlsMode.STARTTLS),
            login = "someone",
        )
        val google = start.manual(other, Source.MANUAL, setOf(ProviderTable.Note.ICLOUD_LOGIN))
            .serversEntered(typed, "jo@example.org") { if (it == Provider.GMAIL) listOf(Method.PASSWORD) else emptyList() }
        assertEquals(Page.GOOGLE, google.page)
        assertEquals(Provider.GMAIL, google.provider)
        assertEquals(Providers.GMAIL_SERVERS, google.servers)
        assertEquals(listOf(Method.PASSWORD), google.methods)
        assertEquals(emptySet(), google.notes)
        // Back from a page reached from the server settings returns to them.
        assertEquals(Page.SERVERS, google.back()!!.page)
    }

    @Test
    fun `typed Microsoft hosts lead to the Microsoft page with the servers of the domain`() {
        val typed = Servers(
            Endpoint("outlook.office365.com", 993, TlsMode.IMPLICIT),
            Endpoint("smtp-mail.outlook.com", 587, TlsMode.STARTTLS),
        )
        val consumer = start.manual(other, Source.MANUAL, emptySet()).serversEntered(typed, "jo@hotmail.com", noMethods)
        assertEquals(Page.MICROSOFT, consumer.page)
        assertEquals(Providers.OUTLOOK_SERVERS, consumer.servers)
        assertEquals(setOf(ProviderTable.Note.ENABLE_IMAP), consumer.notes)
        val work = start.manual(other, Source.MANUAL, emptySet()).serversEntered(typed, "jo@contoso.example", noMethods)
        assertEquals(Providers.M365_SERVERS, work.servers)
        assertEquals(emptySet(), work.notes)
    }

    @Test
    fun `change server settings on the password page`() {
        val password = detected(found(Provider.OTHER, other, Source.AUTOCONFIG)).failed(Problem(Problem.Kind.NETWORK))
        val servers = password.changeServers()
        assertEquals(Page.SERVERS, servers.page)
        assertEquals(other, servers.servers)
        assertNull(servers.problem)
        assertEquals(R.string.setup_servers_text, serversText(servers.source))
        val errors = servers.invalidServers(ServerErrors(imapHost = InputError.IP_ADDRESS))
        assertEquals(InputError.IP_ADDRESS, errors.serverErrors?.imapHost)
        assertNull(errors.serversEntered(other, "jo@example.org", noMethods).serverErrors)
    }

    @Test
    fun `start clears the problem and the field errors`() {
        val state = detected(found(Provider.RISEUP, Providers.RISEUP_SERVERS))
            .invalid(InputError.REQUIRED, InputError.REQUIRED)
            .failed(Problem(Problem.Kind.NETWORK))
            .start(Busy.TESTING)
        assertNull(state.problem)
        assertNull(state.emailError)
        assertNull(state.passwordError)
    }

    @Test
    fun `back cancels a test and keeps the page`() {
        val testing = detected(found(Provider.RISEUP, Providers.RISEUP_SERVERS)).start(Busy.TESTING)
        assertTrue(testing.handlesBack)
        val cancelled = testing.back()!!
        assertNull(cancelled.busy)
        assertEquals(Page.PASSWORD, cancelled.page)
    }

    @Test
    fun `back does nothing while saving`() {
        val saving = detected(found(Provider.RISEUP, Providers.RISEUP_SERVERS)).tested(riseupAccount, false)
            .start(Busy.SAVING)
        assertSame(saving, saving.back())
    }

    @Test
    fun `a failure ends the work and shows the problem on the same page`() {
        val state = detected(found(Provider.GMAIL, Providers.GMAIL_SERVERS), listOf(Method.PASSWORD))
            .start(Busy.TESTING)
            .failed(Problem(Problem.Kind.APP_PASSWORD_REQUIRED))
        assertEquals(Page.GOOGLE, state.page)
        assertNull(state.busy)
        assertEquals(Problem.Kind.APP_PASSWORD_REQUIRED, state.problem?.kind)
    }

    @Test
    fun `a passed test opens the options, and back drops the sign-in`() {
        val options = detected(found(Provider.GMAIL, Providers.GMAIL_SERVERS), listOf(Method.BROWSER))
            .start(Busy.TESTING)
            .tested(gmailAccount, showIpNote = true)
        assertEquals(Page.OPTIONS, options.page)
        assertSame(gmailAccount, options.signedIn)
        assertTrue(options.showIpNote)
        assertNull(options.busy)
        val back = options.back()!!
        assertEquals(Page.GOOGLE, back.page)
        assertNull(back.signedIn)
    }

    @Test
    fun `back from the options of entered servers goes back through the password page`() {
        val options = start.manual(other, Source.GUESS, emptySet())
            .serversEntered(other, "jo@example.org", noMethods)
            .tested(riseupAccount, false)
        val password = options.back()!!
        assertEquals(Page.PASSWORD, password.page)
        assertEquals(Page.SERVERS, password.back()!!.page)
    }

    @Test
    fun `an added account asks for the grants, and back from there ends the flow`() {
        val permissions = detected(found(Provider.RISEUP, Providers.RISEUP_SERVERS)).tested(riseupAccount, false)
            .start(Busy.SAVING).added()
        assertEquals(Page.PERMISSIONS, permissions.page)
        assertNull(permissions.busy)
        assertTrue(permissions.handlesBack)
        assertTrue(permissions.back()!!.done)
    }

    @Test
    fun `the own client page returns to the page of its issuer`() {
        val google = detected(found(Provider.GMAIL, Providers.GMAIL_SERVERS), listOf(Method.PASSWORD))
        val client = google.ownClient(OAuthIssuer.GOOGLE).copy(clientIdError = true)
        assertEquals(Page.OWN_CLIENT, client.page)
        assertEquals(OAuthIssuer.GOOGLE, client.issuer)
        val back = client.back()!!
        assertEquals(Page.GOOGLE, back.page)
        assertFalse(back.clientIdError)
        val saved = client.clientSaved(listOf(Method.BROWSER, Method.PASSWORD))
        assertEquals(Page.GOOGLE, saved.page)
        assertEquals(listOf(Method.BROWSER, Method.PASSWORD), saved.methods)

        val microsoft = detected(found(Provider.MICROSOFT, Providers.OUTLOOK_SERVERS)).ownClient(OAuthIssuer.MICROSOFT)
        assertEquals(OAuthIssuer.MICROSOFT, microsoft.issuer)
        assertEquals(Page.MICROSOFT, microsoft.back()!!.page)
        val withClient = microsoft.clientSaved(listOf(Method.BROWSER))
        assertEquals(Page.MICROSOFT, withClient.page)
        assertEquals(listOf(Method.BROWSER), withClient.methods)
    }

    @Test
    fun `sign in again starts on the provider page and saves after the test`() {
        val riseup = account(AuthKind.PASSWORD, Provider.RISEUP, Providers.RISEUP_SERVERS)
        val reauth = SetupState.signInAgain(riseup, emptyList())
        assertEquals(Page.PASSWORD, reauth.page)
        assertEquals(Page.PASSWORD, reauth.rootPage)
        assertEquals(Providers.RISEUP_SERVERS, reauth.servers)
        assertFalse(reauth.handlesBack)
        assertFalse(reauth.offersServerChange)
        assertNull(reauth.back())
        val saving = reauth.start(Busy.TESTING).tested(riseupAccount, showIpNote = true)
        assertEquals(Busy.SAVING, saving.busy)
        assertEquals(Page.PASSWORD, saving.page)
        assertFalse(saving.showIpNote)
        val saved = saving.reauthSaved()
        assertEquals(Page.SIGNED_IN, saved.page)
        assertTrue(saved.back()!!.done)
    }

    @Test
    fun `sign in again roots by provider`() {
        assertEquals(Page.GOOGLE, SetupState.signInAgain(account(AuthKind.OAUTH), emptyList()).rootPage)
        val microsoft = account(AuthKind.OAUTH, Provider.MICROSOFT, Providers.OUTLOOK_SERVERS)
        assertEquals(Page.MICROSOFT, SetupState.signInAgain(microsoft, emptyList()).rootPage)
        val otherAccount = account(AuthKind.PASSWORD, Provider.OTHER, other)
        assertEquals(Page.PASSWORD, SetupState.signInAgain(otherAccount, emptyList()).rootPage)
    }

    @Test
    fun `change server settings of an account on other servers starts on the server settings`() {
        val stored = account(AuthKind.PASSWORD, Provider.OTHER, other)
        val edit = SetupState.signInAgain(stored, emptyList(), editServers = true)
        assertTrue(edit.editServers)
        assertEquals(Page.SERVERS, edit.page)
        assertEquals(Page.SERVERS, edit.rootPage)
        assertEquals(other, edit.servers)
        assertFalse(edit.handlesBack)
        assertNull(edit.back())
        val moved = other.copy(imap = Endpoint("imap.example.net", 993, TlsMode.IMPLICIT))
        val password = edit.serversEntered(moved, stored.email, noMethods)
        assertEquals(Page.PASSWORD, password.page)
        assertEquals(moved, password.servers)
        assertEquals(Page.SERVERS, password.back()!!.page)
        // Only accounts on other servers with a password have server settings.
        assertFalse(SetupState.signInAgain(account(AuthKind.OAUTH), emptyList(), editServers = true).editServers)
        val riseup = account(AuthKind.PASSWORD, Provider.RISEUP, Providers.RISEUP_SERVERS)
        assertEquals(Page.PASSWORD, SetupState.signInAgain(riseup, emptyList(), editServers = true).page)
    }

    @Test
    fun `sign in again with servers of another provider is refused`() {
        val stored = account(AuthKind.PASSWORD, Provider.OTHER, other)
        val edit = SetupState.signInAgain(stored, emptyList(), editServers = true)
        val refused = edit.serversEntered(Providers.GMAIL_SERVERS, stored.email, noMethods)
        assertEquals(Page.SERVERS, refused.page)
        assertEquals(Problem(Problem.Kind.WRONG_SERVERS, "Gmail"), refused.problem)
    }

    @Test
    fun `sign in again on the password page can change the servers and come back`() {
        val stored = account(AuthKind.PASSWORD, Provider.OTHER, other)
        val reauth = SetupState.signInAgain(stored, emptyList())
        assertTrue(reauth.offersServerChange)
        val moved = other.copy(login = "jane")
        val edited = reauth.changeServers().serversEntered(moved, stored.email, noMethods)
        assertEquals(moved, edited.servers)
        assertEquals(Page.PASSWORD, edited.page)
        assertTrue(edited.handlesBack)
        val servers = edited.back()!!
        assertEquals(Page.SERVERS, servers.page)
        val root = servers.back()!!
        assertEquals(Page.PASSWORD, root.page)
        assertEquals(other, root.servers)
        assertNull(root.back())
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

    @Test
    fun `Microsoft signs in only in the browser with a client ID`() {
        assertEquals(listOf(Method.BROWSER), SetupState.microsoftMethods(browser = true))
        assertEquals(emptyList(), SetupState.microsoftMethods(browser = false))
    }

    private fun methods(device: Boolean, browser: Boolean, reauth: AuthKind?) =
        SetupState.gmailMethods(device, browser, reauth)

    @Test
    fun `a browser result completes only on the pages that can use it`() {
        assertTrue(start.acceptsBrowserResult(reauthMode = false))
        val google = detected(found(Provider.GMAIL, Providers.GMAIL_SERVERS), listOf(Method.BROWSER))
        assertTrue(google.acceptsBrowserResult(reauthMode = false))
        assertTrue(google.start(Busy.BROWSER).acceptsBrowserResult(reauthMode = false))
        assertFalse(google.start(Busy.TESTING).acceptsBrowserResult(reauthMode = false))
        assertFalse(google.tested(gmailAccount, false).acceptsBrowserResult(reauthMode = false))
        val microsoft = detected(found(Provider.MICROSOFT, Providers.OUTLOOK_SERVERS), listOf(Method.BROWSER))
        assertTrue(microsoft.acceptsBrowserResult(reauthMode = false))
        assertTrue(microsoft.ownClient(OAuthIssuer.MICROSOFT).acceptsBrowserResult(reauthMode = false))
        assertFalse(detected(found(Provider.OTHER, other)).acceptsBrowserResult(reauthMode = false))
        assertFalse(start.manual(other, Source.GUESS, emptySet()).acceptsBrowserResult(reauthMode = false))
        assertFalse(start.detecting().acceptsBrowserResult(reauthMode = false))
        assertFalse(start.finished().acceptsBrowserResult(reauthMode = false))
        // Sign in again: only an OAuth account, and not before it has loaded.
        assertFalse(start.acceptsBrowserResult(reauthMode = true))
        assertTrue(SetupState.signInAgain(account(AuthKind.OAUTH), emptyList()).acceptsBrowserResult(reauthMode = true))
        val microsoftAccount = account(AuthKind.OAUTH, Provider.MICROSOFT, Providers.OUTLOOK_SERVERS)
        assertTrue(SetupState.signInAgain(microsoftAccount, emptyList()).acceptsBrowserResult(reauthMode = true))
        val password = SetupState.signInAgain(account(AuthKind.PASSWORD), emptyList())
        assertFalse(password.acceptsBrowserResult(reauthMode = true))
    }

    @Test
    fun `the user name retry is only for SRV servers without a user name, and only once`() {
        val srv = detected(found(Provider.OTHER, other, Source.SRV))
        assertTrue(srv.retriesLogin)
        val used = srv.loginRetryUsed()
        assertFalse(used.retriesLogin)
        // Once per setup session: also after back to welcome and a new detection.
        assertFalse(used.back()!!.found(found(Provider.OTHER, other, Source.SRV), emptyList()).retriesLogin)
        for (source in Source.entries.filter { it != Source.SRV }) {
            assertFalse(detected(found(Provider.OTHER, other, source)).retriesLogin, "$source")
        }
        assertFalse(detected(found(Provider.OTHER, other.copy(login = "jo"), Source.SRV)).retriesLogin)
        assertFalse(detected(found(Provider.GMAIL, Providers.GMAIL_SERVERS, Source.SRV)).retriesLogin)
        // Servers changed by the user: no retry.
        assertFalse(srv.changeServers().serversEntered(other, "jo@example.org", noMethods).retriesLogin)
    }

    @Test
    fun `signed in hides its secret`() {
        assertEquals("SignedIn(RISEUP, PASSWORD)", riseupAccount.toString())
    }

    private fun account(
        auth: AuthKind,
        provider: Provider = Provider.GMAIL,
        servers: Servers = Providers.GMAIL_SERVERS,
    ) = Account(
        id = 7, provider = provider, email = "jane@example.org", senderName = "", label = "Mail", color = 0,
        auth = auth, deviceAccount = null, bodyDays = 30, pushMode = PushMode.INSTANT, pollMinutes = 15,
        gmailCategories = setOf("primary"), archiveFolder = null, signature = "",
        state = AccountState.SIGN_IN_NEEDED, stateText = null, serverKeepaliveS = null, quotaUsed = null,
        quotaLimit = null, sort = 0, servers = servers,
    )
}
