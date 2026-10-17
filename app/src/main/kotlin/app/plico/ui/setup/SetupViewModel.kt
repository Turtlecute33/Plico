package app.plico.ui.setup

import android.app.Activity
import android.database.sqlite.SQLiteConstraintException
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.plico.PlicoApp
import app.plico.R
import app.plico.account.AccountProbe
import app.plico.account.BrowserOAuth
import app.plico.account.DeviceCredentials
import app.plico.account.DeviceGoogleAuth
import app.plico.account.MailError
import app.plico.account.OAuthCredentials
import app.plico.account.OAuthIssuer
import app.plico.account.PasswordCredentials
import app.plico.account.ProbeException
import app.plico.account.ProviderTable
import app.plico.account.Providers
import app.plico.account.discovery.Discovery
import app.plico.account.discovery.Outcome
import app.plico.account.forgetCredentials
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.NewAccount
import app.plico.data.Provider
import app.plico.data.ServerUpdate
import app.plico.data.Servers
import app.plico.mail.net.TlsMode
import app.plico.push.Grants
import app.plico.ui.components.AccountColors
import app.plico.ui.settings.Choices
import app.plico.ui.settings.ClientIds
import app.plico.ui.settings.cleanLabel
import app.plico.ui.settings.cleanLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Runs setup (DESIGN §13, §6): server discovery, the connection test, the account insert, and "Sign in again" to
 * account [reauthId] ([editServers]: starting on the server settings). One instance per screen entry; secrets stay
 * in memory only, as Vault ciphertext once the test has passed.
 */
class SetupViewModel(
    private val app: PlicoApp,
    private val reauthId: Long?,
    private val editServers: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    // Text fields live here and not in the saved instance state, so that a password never leaves memory.
    val email = TextFieldState()
    val password = TextFieldState()
    val label = TextFieldState()
    val senderName = TextFieldState()
    val clientId = TextFieldState()

    // The server settings page. Hosts are shown and edited in ASCII form.
    val imapHost = TextFieldState()
    val imapPort = TextFieldState()
    val smtpHost = TextFieldState()
    val smtpPort = TextFieldState()
    val user = TextFieldState()
    var imapTls by mutableStateOf(TlsMode.IMPLICIT)
        private set
    var smtpTls by mutableStateOf(TlsMode.IMPLICIT)
        private set

    private var work: Job? = null

    init {
        viewModelScope.launch { OAuthResume.signals.collect { resumeBrowser() } }
        if (reauthId == null) {
            if (OAuthResume.hasResult(app)) resumeBrowser()
        } else {
            viewModelScope.launch { loadReauth(reauthId) }
        }
    }

    /** Welcome → Next: the address must be new, then the typo check, then discovery (DESIGN §6). */
    fun next() = welcome(manual = false)

    /** Welcome → "Enter the server settings yourself". */
    fun manual() = welcome(manual = true)

    /** "Use <domain>" of the typo note: replaces the domain of the typed address and continues. */
    fun useSuggestion(domain: String) {
        val address = SetupInput.normalizeEmail(email.text.toString())
        email.setTextAndPlaceCursorAtEnd(address.substringBeforeLast('@') + "@" + domain)
        next()
    }

    private fun welcome(manual: Boolean) {
        val current = _state.value
        if (current.busy != null || current.reauth != null || work?.isActive == true) return
        val address = SetupInput.normalizeEmail(email.text.toString())
        val error = SetupInput.checkEmail(address)
        val domain = Discovery.normalizeHost(address.substringAfterLast('@'))
        if (error != null || domain == null) {
            _state.update { it.invalid(error ?: InputError.INVALID, null) }
            return
        }
        email.setTextAndPlaceCursorAtEnd(address)
        work = viewModelScope.launch {
            // Before any network request.
            val known = withContext(Dispatchers.IO) { app.accounts.byEmail(address) } != null
            val suggestion = if (manual) null else ProviderTable.suggest(domain)
            val next = _state.value.welcomed(address, domain, known, suggestion, manual)
            _state.value = next
            if (next.page == Page.SERVERS) next.servers?.let(::fillServers)
            if (next.busy != Busy.DETECTING) return@launch
            val outcome = try {
                withContext(Dispatchers.IO) { Discovery.of(app).discover(address) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Outcome.NotFound(Discovery.guess(domain))
            }
            when (outcome) {
                is Outcome.Found -> _state.update { it.found(outcome, methodsFor(outcome.provider, null)) }
                is Outcome.Refused -> _state.update { it.refused(outcome) }
                is Outcome.NotFound -> {
                    fillServers(outcome.guess)
                    _state.update { it.manual(outcome.guess, outcome.source, outcome.notes) }
                }
            }
        }
    }

    /** "Change server settings" on the password page. */
    fun changeServers() {
        val current = _state.value
        if (current.busy != null) return
        current.servers?.let(::fillServers)
        password.clearText()
        _state.update { it.changeServers() }
    }

    fun chooseImapTls(mode: TlsMode) {
        imapPort.setTextAndPlaceCursorAtEnd(SetupInput.switchPort(imapPort.text.toString(), imap = true, to = mode))
        imapTls = mode
    }

    fun chooseSmtpTls(mode: TlsMode) {
        smtpPort.setTextAndPlaceCursorAtEnd(SetupInput.switchPort(smtpPort.text.toString(), imap = false, to = mode))
        smtpTls = mode
    }

    /** Server settings → Next: checks the fields, then opens the sign-in page of the servers' provider. */
    fun serversNext() {
        val current = _state.value
        if (current.busy != null) return
        val (servers, errors) = SetupInput.servers(
            imapHost.text.toString(), imapPort.text.toString(), imapTls,
            smtpHost.text.toString(), smtpPort.text.toString(), smtpTls,
            user.text.toString(),
        )
        if (servers == null) {
            _state.update { it.invalidServers(errors ?: ServerErrors()) }
            return
        }
        fillServers(servers)
        val address = current.reauth?.email ?: SetupInput.normalizeEmail(email.text.toString())
        _state.update { it.serversEntered(servers, address) { provider -> methodsFor(provider, it.reauth?.auth) } }
    }

    fun openOwnClient(issuer: OAuthIssuer) {
        clientId.setTextAndPlaceCursorAtEnd(issuer.clientId(app.prefs))
        _state.update { it.ownClient(issuer) }
    }

    /** Writes the client ID of the own-client page's issuer; an empty field removes it. False when it is not valid. */
    fun saveClientId(): Boolean {
        // The same rules as Settings → Sign-in clients, so that an ID saved here is valid there too.
        val issuer = _state.value.ownClient
        val text = clientId.text.toString()
        val id = when (issuer) {
            OAuthIssuer.GOOGLE -> ClientIds.normalize(text)
            OAuthIssuer.MICROSOFT -> ClientIds.normalizeMicrosoft(text)
        }
        if (id == null) {
            _state.update { it.copy(clientIdError = true) }
            return false
        }
        when (issuer) {
            OAuthIssuer.GOOGLE -> app.prefs.googleClientId = id
            OAuthIssuer.MICROSOFT -> app.prefs.microsoftClientId = id
        }
        _state.update { it.clientSaved(methodsFor(SetupState.providerOf(issuer), it.reauth?.auth)) }
        return true
    }

    /**
     * Password or app password for the servers of the state. The password field is cleared after the test. For
     * servers found by DNS SRV, a refused first test runs once more with the local part as the user name.
     */
    fun signInWithPassword() {
        val current = _state.value
        val provider = current.provider ?: return
        val reauth = current.reauth
        val address = reauth?.email ?: SetupInput.normalizeEmail(email.text.toString())
        // Google, Microsoft and Riseup accounts use only their built-in servers (DESIGN §6.2).
        val servers = ProviderTable.pinned(provider, address) ?: current.servers ?: return
        if (current.busy != null) return
        val emailError = if (reauth == null) SetupInput.checkEmail(address) else null
        val passwordError = SetupInput.checkPassword(provider, password.text)
        if (emailError != null || passwordError != null) {
            _state.update { it.invalid(emailError, passwordError) }
            return
        }
        val typed = password.text.toString()
        val retry = current.retriesLogin
        test(provider, Method.PASSWORD) {
            if (retry) _state.update { it.loginRetryUsed() }
            checkIdentity(address)
            var tested = servers
            val result = try {
                probePassword(provider, tested, address, typed)
            } catch (e: ProbeException) {
                if (!retry || e.error !is MailError.AuthRequired) throw e
                // RFC 6186 §4: some servers want only the local part as the user name.
                tested = servers.copy(login = address.substringBeforeLast('@'))
                probePassword(provider, tested, address, typed)
            }
            val secret = encrypt(PasswordCredentials.normalize(provider, typed))
            SignedIn(provider, address, AuthKind.PASSWORD, secret, null, tested, result.idle, result.notes)
        }
    }

    private suspend fun probePassword(provider: Provider, servers: Servers, address: String, password: String) =
        AccountProbe.probe(app, provider, servers, PasswordCredentials(provider, servers.login ?: address, password))

    /** Opens the consent page of the flow's issuer; [resumeBrowser] continues when the browser comes back. */
    fun signInWithBrowser(activity: Activity) {
        val current = _state.value
        if (current.busy != null) return
        val issuer = current.issuer
        val typed = SetupInput.normalizeEmail(email.text.toString())
        val hint = current.reauth?.email ?: typed.takeIf { SetupInput.checkEmail(it) == null }.orEmpty()
        try {
            BrowserOAuth(app, issuer).start(activity, hint)
            _state.update { it.start(Busy.BROWSER) }
        } catch (e: MailError) {
            _state.update { it.failed(Problem.of(e, SetupState.providerOf(issuer), Method.BROWSER)) }
        }
    }

    /** Call before the account chooser opens. */
    fun beginDevice() {
        if (_state.value.busy == null) _state.update { it.start(Busy.DEVICE) }
    }

    /** The chooser closed with [accountName] (null: cancelled). Shows Google's consent once, then tests. */
    fun onAccountChosen(activity: Activity, accountName: String?) {
        val busy = _state.value.busy
        if (busy != null && busy != Busy.DEVICE) return
        if (accountName == null) {
            _state.update { it.copy(busy = null) }
            return
        }
        val typed = typedAddress()
        test(Provider.GMAIL, Method.DEVICE, Busy.DEVICE) {
            val address = accountName.lowercase(Locale.ROOT)
            checkTyped(address, typed)
            checkIdentity(address)
            DeviceGoogleAuth.consent(activity, accountName)
            _state.update { it.copy(busy = Busy.TESTING) }
            val credentials = DeviceCredentials(app, accountName)
            val servers = Providers.GMAIL_SERVERS
            val result = AccountProbe.probe(app, Provider.GMAIL, servers, credentials)
            SignedIn(Provider.GMAIL, address, AuthKind.DEVICE, null, accountName, servers, result.idle, result.notes)
        }
    }

    fun setAllOffline(on: Boolean) = _state.update { it.copy(allOffline = on) }

    /** Options page: inserts the tested account (DESIGN §6 setup usage), then asks for the power grants. */
    fun addAccount() {
        val current = _state.value
        val account = current.signedIn ?: return
        if (current.busy != null || current.reauth != null) return
        // The rules of Settings: controls become spaces (privacy PS3), the same length limits.
        val typedLabel = cleanLabel(label.text.toString())
        val typedName = cleanLine(senderName.text.toString(), Choices.NAME_MAX)
        _state.update { it.start(Busy.SAVING) }
        work = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val accounts = app.accounts
                    app.mail.addAccount(
                        NewAccount(
                            provider = account.provider,
                            email = account.email,
                            senderName = typedName,
                            label = typedLabel ?: accounts.defaultLabel(account.provider),
                            color = AccountColors.next(accounts.all().map { it.color }),
                            auth = account.auth,
                            secret = account.secret,
                            deviceAccount = account.deviceAccount,
                            servers = account.servers,
                            bodyDays = if (current.allOffline) 0 else NewAccount.DEFAULT_BODY_DAYS,
                            idle = account.idle,
                        ),
                    )
                }
                if (current.showIpNote) app.prefs.gmailIpNoteShown = true
                _state.update { it.added() }
                if (Grants.read(app).complete) finish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: SQLiteConstraintException) {
                _state.update { it.failed(Problem(Problem.Kind.ALREADY_ADDED)) }
            } catch (_: Exception) {
                _state.update { it.failed(Problem(Problem.Kind.SAVE_FAILED)) }
            }
        }
    }

    /** Ends the flow; a new account also starts push (DESIGN §8.2 after the grants). */
    fun finish() {
        if (reauthId == null) app.push.ensureStarted()
        _state.update { it.finished() }
    }

    /** Back inside the flow; cancels running work. False when Back should leave the screen. */
    fun back(): Boolean {
        val current = _state.value
        val next = current.back() ?: return false
        if (next.done) {
            finish()
            return true
        }
        if (current.busy != null && current.busy != Busy.SAVING) {
            work?.cancel()
            work = null
        }
        if (next.page != current.page) password.clearText()
        // Back to the root of "Sign in again": the fields show the stored servers again.
        if (next.page == Page.SERVERS && next.servers != current.servers) next.servers?.let(::fillServers)
        _state.value = next
        return true
    }

    private suspend fun loadReauth(id: Long) {
        val account = withContext(Dispatchers.IO) { app.accounts.get(id) }
        if (account == null) {
            _state.update { it.finished() }
            return
        }
        fillServers(account.servers)
        _state.value = SetupState.signInAgain(account, methodsFor(account.provider, account.auth), editServers)
        if (account.auth == AuthKind.OAUTH && OAuthResume.hasResult(app)) resumeBrowser()
    }

    /** The browser came back: exchanges the code, then tests. Ignored while other work runs. */
    private fun resumeBrowser() {
        val current = _state.value
        if (!current.acceptsBrowserResult(reauthMode = reauthId != null)) return
        val issuer = BrowserOAuth.pending(app.prefs)?.issuer ?: return
        val provider = SetupState.providerOf(issuer)
        val reauth = current.reauth
        // Sign in again completes only a sign-in of the account's own issuer.
        if (reauth != null && OAuthIssuer.of(reauth.provider) != issuer) return
        val waiting = current.busy
        val typed = typedAddress()
        if (reauth == null) {
            _state.update {
                it.copy(
                    page = SetupState.pageFor(provider),
                    provider = provider,
                    methods = methodsFor(provider, null),
                    problem = null,
                    emailError = null,
                    passwordError = null,
                )
            }
        }
        test(provider, Method.BROWSER) {
            val endpoint = BrowserOAuth(app, issuer)
            val signIn = endpoint.completePending()
            if (signIn == null) {
                _state.update { it.copy(busy = waiting) }
                return@test null
            }
            val address = signIn.email.lowercase(Locale.ROOT)
            checkTyped(address, typed)
            checkIdentity(address)
            reauth?.let { SetupState.wrongBrowserAccount(signIn, it) }?.let { throw Rejected(it) }
            val token = signIn.refreshToken
            // Always the built-in servers of the issuer, never servers from the state (DESIGN §6.5).
            val builtIn = if (issuer == OAuthIssuer.MICROSOFT) {
                ProviderTable.microsoftServers(address)
            } else {
                Providers.GMAIL_SERVERS
            }
            val servers = builtIn.copy(login = signIn.login)
            val smtpOff = if (issuer == OAuthIssuer.MICROSOFT) app.getString(R.string.account_note_smtp_off) else null
            val credentials = OAuthCredentials(
                signIn.login ?: signIn.email, { token }, endpoint, signIn.accessToken,
                hosts = issuer.hosts, smtpOff = smtpOff,
            )
            val result = AccountProbe.probe(app, provider, servers, credentials)
            SignedIn(provider, address, AuthKind.OAUTH, encrypt(token), null, servers, result.idle, result.notes)
        }
    }

    /**
     * Runs one connection test as [busy]. [block] returns the tested account, or null when there is nothing
     * to do; it throws [Rejected] for a specific problem. The password field is cleared afterwards (privacy PS19).
     */
    private fun test(
        provider: Provider,
        method: Method,
        busy: Busy = Busy.TESTING,
        block: suspend () -> SignedIn?,
    ) {
        work?.cancel()
        _state.update { it.start(busy) }
        work = viewModelScope.launch {
            try {
                val account = block() ?: return@launch
                onTested(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Rejected) {
                _state.update { it.failed(e.problem) }
            } catch (e: Exception) {
                _state.update { it.failed(Problem.of(e, provider, method)) }
            } finally {
                if (method == Method.PASSWORD) password.clearText()
            }
        }
    }

    private suspend fun onTested(account: SignedIn) {
        val reauth = _state.value.reauth
        if (reauth == null) {
            val defaultLabel = withContext(Dispatchers.IO) { app.accounts.defaultLabel(account.provider) }
            label.setTextAndPlaceCursorAtEnd(defaultLabel)
            senderName.clearText()
            val ipNote = account.provider == Provider.GMAIL && !app.prefs.gmailIpNoteShown
            _state.update { it.tested(account, ipNote) }
            return
        }
        if (account.provider != reauth.provider) {
            throw Rejected(Problem(Problem.Kind.WRONG_SERVERS, SetupState.serverBrand(account.provider)))
        }
        // Sign in again: overwrite the secret; never revoke after the new consent (privacy PS16).
        _state.update { it.tested(account, showIpNote = false) }
        val newServers = account.servers != reauth.servers
        val update = ServerUpdate(account.servers, account.idle).takeIf { newServers || account.idle != reauth.idle }
        try {
            withContext(Dispatchers.IO) {
                // The session of the old servers stops first, so that nothing writes rows while they are deleted.
                if (newServers) app.sessions.remove(reauth.id)
                app.accounts.setAuth(reauth.id, account.auth, account.secret, account.deviceAccount, update, app.files)
                forgetCredentials(reauth.id)
                app.accounts.setState(reauth.id, AccountState.OK, null)
                if (update != null) app.mail.accountChanged(reauth.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw Rejected(Problem(Problem.Kind.SAVE_FAILED))
        }
        app.sessions[reauth.id].signedIn()
        _state.update { it.reauthSaved() }
    }

    /** Fills the server settings page with [servers]. */
    private fun fillServers(servers: Servers) {
        imapHost.setTextAndPlaceCursorAtEnd(servers.imap.host)
        imapPort.setTextAndPlaceCursorAtEnd(servers.imap.port.toString())
        imapTls = servers.imap.tls
        smtpHost.setTextAndPlaceCursorAtEnd(servers.smtp.host)
        smtpPort.setTextAndPlaceCursorAtEnd(servers.smtp.port.toString())
        smtpTls = servers.smtp.tls
        user.setTextAndPlaceCursorAtEnd(servers.login.orEmpty())
    }

    /** The typed address when it is a full address, else null. */
    private fun typedAddress(): String? =
        SetupInput.normalizeEmail(email.text.toString()).takeIf { SetupInput.checkEmail(it) == null }

    /** Adding: the address must be new. Signing in again: it must be the account's own address. */
    private suspend fun checkIdentity(address: String) {
        val reauth = _state.value.reauth
        if (reauth == null) {
            if (withContext(Dispatchers.IO) { app.accounts.byEmail(address) } != null) {
                throw Rejected(Problem(Problem.Kind.ALREADY_ADDED))
            }
        } else if (!address.equals(reauth.deviceAccount ?: reauth.email, ignoreCase = true)) {
            throw Rejected(Problem(Problem.Kind.WRONG_ACCOUNT, address))
        }
    }

    /** Adding: the account that signed in must be the typed address ([typed] null: nothing typed, no check). */
    private fun checkTyped(address: String, typed: String?) {
        if (_state.value.reauth != null || typed == null) return
        if (!SetupInput.sameMailbox(address, typed)) throw Rejected(Problem(Problem.Kind.WRONG_ACCOUNT, address))
    }

    private suspend fun encrypt(secret: String): ByteArray =
        withContext(Dispatchers.IO) { app.vault.encryptString(secret) }

    /** The sign-in methods of [provider]'s page; [reauth] is the method of the account that signs in again. */
    private fun methodsFor(provider: Provider, reauth: AuthKind?): List<Method> = when (provider) {
        Provider.GMAIL -> SetupState.gmailMethods(
            device = DeviceGoogleAuth.isAvailable(app),
            browser = app.prefs.googleClientId.isNotBlank(),
            reauth = reauth,
        )
        Provider.MICROSOFT -> SetupState.microsoftMethods(browser = app.prefs.microsoftClientId.isNotBlank())
        else -> emptyList()
    }

    /** Ends a test with a specific [problem]. */
    private class Rejected(val problem: Problem) : Exception()
}
