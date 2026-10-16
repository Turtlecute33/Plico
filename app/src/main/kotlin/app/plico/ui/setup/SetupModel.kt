package app.plico.ui.setup

import app.plico.account.MailError
import app.plico.account.MailErrors
import app.plico.account.PasswordCredentials
import app.plico.data.Account
import app.plico.data.AuthKind
import app.plico.data.Provider
import app.plico.mail.mime.Addresses
import java.util.Locale

/** The pages of the setup flow (DESIGN §13 Setup). Their order gives the direction of the page animation. */
enum class Page { WELCOME, RISEUP, GMAIL, OWN_CLIENT, OPTIONS, PERMISSIONS, SIGNED_IN }

/** Sign-in methods (DESIGN §6). */
enum class Method { PASSWORD, DEVICE, BROWSER }

/** Work that covers the page. The user can cancel all but [SAVING]. */
enum class Busy { TESTING, BROWSER, DEVICE, SAVING }

/** Errors of a single text field. */
enum class InputError { REQUIRED, INVALID, NOT_RISEUP }

/**
 * An account that passed the connection test. [secret] is Vault ciphertext of the password or refresh
 * token (null for the device account); plain secrets never stay in the state.
 */
class SignedIn(
    val provider: Provider,
    val email: String,
    val auth: AuthKind,
    val secret: ByteArray?,
    val deviceAccount: String?,
    val notes: List<String> = emptyList(),
) {
    override fun toString() = "SignedIn($provider, $auth)"
}

/** A failure shown on the page. [detail] is a server or account-module text for some kinds. */
data class Problem(val kind: Kind, val detail: String? = null) {
    enum class Kind {
        /** Riseup refused the address or password. */
        WRONG_PASSWORD,

        /** Gmail wants an app password: the user typed the Google password. */
        APP_PASSWORD_REQUIRED,

        /** Gmail refused the app password: wrong, revoked, or 2-Step Verification is off. */
        APP_PASSWORD_REFUSED,

        /** Google refused the browser or device sign-in; [detail] tells why. */
        SIGN_IN_REFUSED,

        /** The user must change a setting (Show in IMAP, OAuth client); [detail] tells what. */
        SETUP,
        NETWORK,
        TLS,
        THROTTLED,

        /** Any other refusal; [detail] is the server text. */
        SERVER,
        ALREADY_ADDED,

        /** The user signed in to another Google account than the one to repair; [detail] is that address. */
        WRONG_ACCOUNT,
        SAVE_FAILED,
    }

    companion object {
        // Gmail IMAP "[ALERT] Application-specific password required", SMTP "534-5.7.9 Application-specific ...".
        private val APP_PASSWORD_TEXT = Regex("application-specific password", RegexOption.IGNORE_CASE)

        /** The message for a failed connection test of [provider] with [method] (DESIGN §14). */
        fun of(error: Throwable, provider: Provider, method: Method): Problem =
            when (val e = MailErrors.classify(error, networkValidated = true)) {
                is MailError.AuthRequired -> when {
                    method != Method.PASSWORD -> Problem(Kind.SIGN_IN_REFUSED, e.message?.ifBlank { null })
                    provider == Provider.RISEUP -> Problem(Kind.WRONG_PASSWORD)
                    APP_PASSWORD_TEXT.containsMatchIn(e.message.orEmpty()) -> Problem(Kind.APP_PASSWORD_REQUIRED)
                    else -> Problem(Kind.APP_PASSWORD_REFUSED)
                }
                is MailError.Setup -> Problem(Kind.SETUP, e.message?.ifBlank { null })
                is MailError.Network -> Problem(Kind.NETWORK)
                is MailError.Tls -> Problem(Kind.TLS)
                is MailError.Throttled -> Problem(Kind.THROTTLED)
                // MailError.Server, with the server's text.
                else -> Problem(Kind.SERVER, e.message?.ifBlank { null })
            }
    }
}

/**
 * The state of the setup flow, also for "Sign in again" ([reauth] set; the flow is then limited to that
 * account's provider and sign-in method). Every transition returns a new state; the ViewModel does the work.
 */
data class SetupState(
    val page: Page = Page.WELCOME,
    val provider: Provider? = null,
    val reauth: Account? = null,
    /** Gmail methods on the page, in display order. */
    val methods: List<Method> = emptyList(),
    val busy: Busy? = null,
    val problem: Problem? = null,
    val emailError: InputError? = null,
    val passwordError: InputError? = null,
    val clientIdError: Boolean = false,
    val signedIn: SignedIn? = null,
    /** Gmail's one-time note about the sender IP in sent mail (DESIGN §12). */
    val showIpNote: Boolean = false,
    val allOffline: Boolean = false,
    /** The flow is over: the screen closes. */
    val done: Boolean = false,
) {
    /** The first page: Welcome when adding an account, the provider's page when signing in again. */
    val rootPage: Page get() = reauth?.let { pageOf(it.provider) } ?: Page.WELCOME

    /** True when Back stays in the flow (or is blocked) instead of closing the screen. */
    val handlesBack: Boolean get() = busy != null || page != rootPage

    /** The "Use your own Google OAuth client" entry: always when adding, else only for the OAuth methods. */
    val offersOwnClient: Boolean get() = reauth == null || reauth.auth != AuthKind.PASSWORD

    /**
     * True when a browser sign-in may complete now: no other work runs, and the flow still wants a Gmail
     * OAuth account. [reauthMode] is true for "Sign in again", also while its account loads.
     */
    fun acceptsBrowserResult(reauthMode: Boolean): Boolean = when {
        done || (busy != null && busy != Busy.BROWSER) -> false
        reauthMode -> reauth?.auth == AuthKind.OAUTH
        else -> page == Page.WELCOME || page == Page.GMAIL || page == Page.OWN_CLIENT
    }

    fun choose(provider: Provider, methods: List<Method>) = copy(
        page = pageOf(provider),
        provider = provider,
        methods = methods,
        problem = null,
        emailError = null,
        passwordError = null,
    )

    fun ownClient() = copy(page = Page.OWN_CLIENT, problem = null, clientIdError = false)

    fun clientSaved(methods: List<Method>) = copy(page = Page.GMAIL, methods = methods, clientIdError = false)

    fun invalid(email: InputError?, password: InputError?) =
        copy(emailError = email, passwordError = password, problem = null)

    fun start(busy: Busy) = copy(busy = busy, problem = null, emailError = null, passwordError = null)

    fun failed(problem: Problem) = copy(busy = null, problem = problem)

    /** The connection test passed: options when adding; when signing in again, the account is saved next. */
    fun tested(account: SignedIn, showIpNote: Boolean): SetupState = if (reauth == null) {
        copy(
            page = Page.OPTIONS,
            busy = null,
            problem = null,
            signedIn = account,
            showIpNote = showIpNote,
            allOffline = false,
        )
    } else {
        copy(busy = Busy.SAVING, problem = null, signedIn = account)
    }

    fun added() = copy(page = Page.PERMISSIONS, busy = null, problem = null, signedIn = null)

    fun reauthSaved() = copy(page = Page.SIGNED_IN, busy = null, problem = null)

    fun finished() = copy(busy = null, done = true)

    /** The state after Back, or null when Back leaves the screen. Saving cannot be interrupted. */
    fun back(): SetupState? = when {
        busy == Busy.SAVING -> this
        busy != null -> copy(busy = null)
        page == Page.PERMISSIONS || page == Page.SIGNED_IN -> finished()
        page == rootPage -> null
        page == Page.OWN_CLIENT -> copy(page = Page.GMAIL, problem = null, clientIdError = false)
        page == Page.OPTIONS -> copy(page = provider?.let(::pageOf) ?: Page.WELCOME, signedIn = null, problem = null)
        else -> SetupState()
    }

    companion object {
        /** The state for "Sign in again" to [account]. */
        fun signInAgain(account: Account, methods: List<Method>) =
            SetupState(pageOf(account.provider), account.provider, reauth = account, methods = methods)

        fun pageOf(provider: Provider) = when (provider) {
            Provider.RISEUP -> Page.RISEUP
            Provider.GMAIL -> Page.GMAIL
        }

        /**
         * The Gmail methods to show (DESIGN §6, §13). Adding: the two OAuth methods when available (both need
         * the user's own client ID), then the app password, which is always there. Signing in again: only the
         * account's own method. [device] = DeviceGoogleAuth.isAvailable; [browser] = a client ID is set.
         */
        fun gmailMethods(device: Boolean, browser: Boolean, reauth: AuthKind?): List<Method> = when (reauth) {
            null -> listOfNotNull(Method.DEVICE.takeIf { device }, Method.BROWSER.takeIf { browser }, Method.PASSWORD)
            AuthKind.PASSWORD -> listOf(Method.PASSWORD)
            AuthKind.OAUTH -> listOfNotNull(Method.BROWSER.takeIf { browser })
            AuthKind.DEVICE -> listOfNotNull(Method.DEVICE.takeIf { device })
        }
    }
}

/** Checks and normalizes what the user types during setup. */
object SetupInput {
    const val RISEUP_DOMAIN = "riseup.net"

    /** Trimmed and lower-case (account.email is UNIQUE with case); a bare Riseup user name gets "@riseup.net". */
    fun normalizeEmail(provider: Provider, raw: String): String {
        val address = raw.trim().lowercase(Locale.ROOT)
        val bareRiseupName = provider == Provider.RISEUP && address.isNotEmpty() && '@' !in address
        return if (bareRiseupName) "$address@$RISEUP_DOMAIN" else address
    }

    /** Checks a [normalizeEmail] result. Riseup logs in only with the riseup.net address, never with an alias. */
    fun checkEmail(provider: Provider, address: String): InputError? {
        val domain = address.substringAfterLast('@', "")
        return when {
            address.isEmpty() -> InputError.REQUIRED
            !Addresses.isValidAddrSpec(address) || '.' !in domain -> InputError.INVALID
            provider == Provider.RISEUP && domain != RISEUP_DOMAIN -> InputError.NOT_RISEUP
            else -> null
        }
    }

    /** A password is required; Gmail app passwords count without their spaces. */
    fun checkPassword(provider: Provider, password: CharSequence): InputError? =
        if (PasswordCredentials.normalize(provider, password.toString()).isEmpty()) InputError.REQUIRED else null
}
