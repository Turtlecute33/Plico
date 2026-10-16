package app.plico.ui.setup

import android.app.Activity
import android.database.sqlite.SQLiteConstraintException
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.plico.PlicoApp
import app.plico.account.AccountProbe
import app.plico.account.DeviceCredentials
import app.plico.account.DeviceGoogleAuth
import app.plico.account.GoogleOAuth
import app.plico.account.MailError
import app.plico.account.OAuthCredentials
import app.plico.account.PasswordCredentials
import app.plico.account.forgetCredentials
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.NewAccount
import app.plico.data.Provider
import app.plico.ui.components.AccountColors
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
 * Runs setup (DESIGN §13, §6): the connection test, the account insert, and "Sign in again" to
 * account [reauthId]. One instance per screen entry; secrets stay in memory only, as Vault ciphertext
 * once the test has passed.
 */
class SetupViewModel(private val app: PlicoApp, private val reauthId: Long?) : ViewModel() {
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    // Text fields live here and not in the saved instance state, so that a password never leaves memory.
    val email = TextFieldState()
    val password = TextFieldState()
    val label = TextFieldState()
    val senderName = TextFieldState()
    val clientId = TextFieldState()

    private var work: Job? = null

    init {
        viewModelScope.launch { OAuthResume.signals.collect { resumeBrowser() } }
        if (reauthId == null) {
            if (OAuthResume.hasResult(app)) resumeBrowser()
        } else {
            viewModelScope.launch { loadReauth(reauthId) }
        }
    }

    fun choose(provider: Provider) {
        if (_state.value.provider != provider) email.clearText()
        _state.update { it.choose(provider, if (provider == Provider.GMAIL) gmailMethods(null) else emptyList()) }
    }

    fun openOwnClient() {
        clientId.setTextAndPlaceCursorAtEnd(app.prefs.googleClientId)
        _state.update { it.ownClient() }
    }

    /** Writes Prefs.googleClientId; an empty field removes it. False when the value is not a client ID. */
    fun saveClientId(): Boolean {
        val id = clientId.text.trim().toString()
        if (!SetupInput.isValidClientId(id)) {
            _state.update { it.copy(clientIdError = true) }
            return false
        }
        app.prefs.googleClientId = id
        _state.update { it.clientSaved(gmailMethods(it.reauth?.auth)) }
        return true
    }

    /** Riseup password or Gmail app password. The password field is cleared after the test. */
    fun signInWithPassword() {
        val current = _state.value
        val provider = current.provider ?: return
        if (current.busy != null) return
        val reauth = current.reauth
        val address = reauth?.email ?: SetupInput.normalizeEmail(provider, email.text.toString())
        val emailError = if (reauth == null) SetupInput.checkEmail(provider, address) else null
        val passwordError = SetupInput.checkPassword(provider, password.text)
        if (emailError != null || passwordError != null) {
            _state.update { it.invalid(emailError, passwordError) }
            return
        }
        if (reauth == null) email.setTextAndPlaceCursorAtEnd(address)
        val typed = password.text.toString()
        test(provider, Method.PASSWORD) {
            checkIdentity(address)
            val result = AccountProbe.probe(app, provider, PasswordCredentials(provider, address, typed))
            val secret = encrypt(PasswordCredentials.normalize(provider, typed))
            SignedIn(provider, address, AuthKind.PASSWORD, secret, null, result.notes)
        }
    }

    /** Opens Google's consent page; [resumeBrowser] continues when the browser comes back. */
    fun signInWithBrowser(activity: Activity) {
        val current = _state.value
        if (current.busy != null) return
        val typed = SetupInput.normalizeEmail(Provider.GMAIL, email.text.toString())
        val hint = current.reauth?.email ?: typed.takeIf { SetupInput.checkEmail(Provider.GMAIL, it) == null }.orEmpty()
        try {
            GoogleOAuth(app).start(activity, hint)
            _state.update { it.start(Busy.BROWSER) }
        } catch (e: MailError) {
            _state.update { it.failed(Problem.of(e, Provider.GMAIL, Method.BROWSER)) }
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
        test(Provider.GMAIL, Method.DEVICE, Busy.DEVICE) {
            val address = accountName.lowercase(Locale.ROOT)
            checkIdentity(address)
            DeviceGoogleAuth.consent(activity, accountName)
            _state.update { it.copy(busy = Busy.TESTING) }
            val result = AccountProbe.probe(app, Provider.GMAIL, DeviceCredentials(app, accountName))
            SignedIn(Provider.GMAIL, address, AuthKind.DEVICE, null, accountName, result.notes)
        }
    }

    fun setAllOffline(on: Boolean) = _state.update { it.copy(allOffline = on) }

    /** Options page: inserts the tested account (DESIGN §6 setup usage), then asks for the power grants. */
    fun addAccount() {
        val current = _state.value
        val account = current.signedIn ?: return
        if (current.busy != null || current.reauth != null) return
        val typedLabel = label.text.trim().toString()
        val typedName = senderName.text.trim().toString()
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
                            label = typedLabel.ifEmpty { accounts.defaultLabel(account.provider) },
                            color = AccountColors.next(accounts.all().map { it.color }),
                            auth = account.auth,
                            secret = account.secret,
                            deviceAccount = account.deviceAccount,
                            bodyDays = if (current.allOffline) 0 else DEFAULT_BODY_DAYS,
                        ),
                    )
                }
                if (current.showIpNote) app.prefs.gmailIpNoteShown = true
                _state.update { it.added() }
                if (Grants.of(app).complete) finish()
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

    /** Back inside the flow; cancels a running test. False when Back should leave the screen. */
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
        _state.value = next
        return true
    }

    private suspend fun loadReauth(id: Long) {
        val account = withContext(Dispatchers.IO) { app.accounts.get(id) }
        if (account == null) {
            _state.update { it.finished() }
            return
        }
        _state.value = SetupState.signInAgain(account, gmailMethods(account.auth))
        if (account.auth == AuthKind.OAUTH && OAuthResume.hasResult(app)) resumeBrowser()
    }

    /** The browser came back: exchanges the code, then tests. Ignored while other work runs. */
    private fun resumeBrowser() {
        val current = _state.value
        if (!current.acceptsBrowserResult(reauthMode = reauthId != null)) return
        val waiting = current.busy
        if (current.reauth == null) choose(Provider.GMAIL)
        test(Provider.GMAIL, Method.BROWSER) {
            val signIn = GoogleOAuth(app).completePending()
            if (signIn == null) {
                _state.update { it.copy(busy = waiting) }
                return@test null
            }
            val address = signIn.email.lowercase(Locale.ROOT)
            checkIdentity(address)
            val token = signIn.refreshToken
            val credentials = OAuthCredentials(signIn.email, { token }, GoogleOAuth(app), signIn.accessToken)
            val result = AccountProbe.probe(app, Provider.GMAIL, credentials)
            SignedIn(Provider.GMAIL, address, AuthKind.OAUTH, encrypt(token), null, result.notes)
        }
    }

    /**
     * Runs one connection test as [busy]. [block] returns the tested account, or null when there is nothing
     * to do; it throws [Rejected] for a specific problem. The password field is cleared afterwards (privacy PS19).
     */
    private fun test(provider: Provider, method: Method, busy: Busy = Busy.TESTING, block: suspend () -> SignedIn?) {
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
        // Sign in again: overwrite the secret; never revoke after the new consent (privacy PS16).
        _state.update { it.tested(account, showIpNote = false) }
        try {
            withContext(Dispatchers.IO) {
                app.accounts.setAuth(reauth.id, account.auth, account.secret, account.deviceAccount)
                forgetCredentials(reauth.id)
                app.accounts.setState(reauth.id, AccountState.OK, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw Rejected(Problem(Problem.Kind.SAVE_FAILED))
        }
        app.sessions[reauth.id].signedIn()
        _state.update { it.reauthSaved() }
    }

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

    private suspend fun encrypt(secret: String): ByteArray =
        withContext(Dispatchers.IO) { app.vault.encryptString(secret) }

    private fun gmailMethods(reauth: AuthKind?) = SetupState.gmailMethods(
        device = DeviceGoogleAuth.isAvailable(app),
        browser = app.prefs.googleClientId.isNotBlank(),
        reauth = reauth,
    )

    /** Ends a test with a specific [problem]. */
    private class Rejected(val problem: Problem) : Exception()

    private companion object {
        const val DEFAULT_BODY_DAYS = 30
    }
}
