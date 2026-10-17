package app.plico.ui.setup

import androidx.annotation.StringRes
import app.plico.R
import app.plico.account.MailError
import app.plico.account.MailErrors
import app.plico.account.OAuthIssuer
import app.plico.account.PasswordCredentials
import app.plico.account.ProviderTable
import app.plico.account.discovery.Discovery
import app.plico.account.discovery.Outcome
import app.plico.account.discovery.Source
import app.plico.data.Account
import app.plico.data.AuthKind
import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.html.Idn
import app.plico.mail.mime.Addresses
import app.plico.mail.net.Endpoint
import app.plico.mail.net.StartTlsException
import app.plico.mail.net.TlsHandshakeException
import app.plico.mail.net.TlsMode
import java.net.SocketTimeoutException
import java.util.Locale

/** The pages of the setup flow (DESIGN §13 Setup). Their order gives the direction of the page animation. */
enum class Page { WELCOME, SERVERS, PASSWORD, GOOGLE, MICROSOFT, OWN_CLIENT, OPTIONS, PERMISSIONS, SIGNED_IN }

/** Sign-in methods (DESIGN §6). */
enum class Method { PASSWORD, DEVICE, BROWSER }

/** Work that covers the page. The user can cancel all but [SAVING]. */
enum class Busy { DETECTING, TESTING, BROWSER, DEVICE, SAVING }

/** Errors of a single text field. */
enum class InputError { REQUIRED, INVALID, IP_ADDRESS, PORT }

/** Field errors of the server settings page; null = no error in that field. */
data class ServerErrors(
    val imapHost: InputError? = null,
    val imapPort: InputError? = null,
    val smtpHost: InputError? = null,
    val smtpPort: InputError? = null,
)

/**
 * An account that passed the connection test. [secret] is Vault ciphertext of the password or refresh
 * token (null for the device account); plain secrets never stay in the state. [servers] are the tested servers
 * (with the user name that worked); [idle] is false when the server has no IDLE.
 */
class SignedIn(
    val provider: Provider,
    val email: String,
    val auth: AuthKind,
    val secret: ByteArray?,
    val deviceAccount: String?,
    val servers: Servers,
    val idle: Boolean,
    val notes: List<String> = emptyList(),
) {
    override fun toString() = "SignedIn($provider, $auth)"
}

/**
 * A failure shown on the page. [detail] is a server or account-module text, a provider name or an address,
 * by [kind]. [issuer] is the sign-in issuer of [Kind.SIGN_IN_REFUSED].
 */
data class Problem(val kind: Kind, val detail: String? = null, val issuer: OAuthIssuer? = null) {
    enum class Kind {
        /** The server refused the user name or the password (every provider except Gmail). */
        WRONG_PASSWORD,

        /** Gmail wants an app password: the user typed the Google password. */
        APP_PASSWORD_REQUIRED,

        /** Gmail refused the app password: wrong, revoked, or 2-Step Verification is off. */
        APP_PASSWORD_REFUSED,

        /** [issuer] refused the browser or device sign-in; [detail] tells why. */
        SIGN_IN_REFUSED,

        /** The user must change a setting (Show in IMAP, OAuth client); [detail] tells what. */
        SETUP,
        NETWORK,
        TLS,

        /** No encrypted connection: STARTTLS refused, or the security setting does not fit the port. */
        NO_TLS,
        THROTTLED,

        /** Any other refusal; [detail] is the server text. */
        SERVER,
        ALREADY_ADDED,

        /** The user signed in to another account than the one typed or to repair; [detail] is that address. */
        WRONG_ACCOUNT,
        SAVE_FAILED,

        /** The provider has no IMAP; [detail] is its name. */
        UNSUPPORTED,

        /** The provider allows IMAP only through its Bridge app; [detail] is its name. */
        BRIDGE,

        /** The domain is probably a typo; [detail] is the table domain that the user probably meant. */
        TYPO,

        /** One server is of a built-in provider and the other is not; [detail] is that provider's name. */
        MIXED_SERVERS,

        /** Sign in again with servers of another provider; [detail] is that provider's name. */
        WRONG_SERVERS,
    }

    companion object {
        // Gmail IMAP "[ALERT] Application-specific password required", SMTP "534-5.7.9 Application-specific ...".
        private val APP_PASSWORD_TEXT = Regex("application-specific password", RegexOption.IGNORE_CASE)
        private const val MAX_CAUSES = 16

        /**
         * The message for a failed connection test of [provider] with [method] (DESIGN §14). [starttls] is true
         * when a tested server uses STARTTLS: a read timeout then means the port expects implicit TLS.
         */
        fun of(error: Throwable, provider: Provider, method: Method, starttls: Boolean = false): Problem {
            if (noTls(error, starttls)) return Problem(Kind.NO_TLS)
            return when (val e = MailErrors.classify(error, networkValidated = true)) {
                is MailError.AuthRequired -> when {
                    method != Method.PASSWORD -> Problem(
                        Kind.SIGN_IN_REFUSED,
                        e.message?.ifBlank { null },
                        OAuthIssuer.of(provider) ?: OAuthIssuer.GOOGLE,
                    )
                    provider != Provider.GMAIL -> Problem(Kind.WRONG_PASSWORD)
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

        /**
         * STARTTLS failed, the TLS handshake met a server that does not speak TLS on this port, or (with STARTTLS)
         * the greeting did not come: the server waits for a TLS handshake. A connect timeout is a network problem.
         */
        private fun noTls(error: Throwable, starttls: Boolean): Boolean =
            generateSequence(error) { it.cause }.take(MAX_CAUSES).any {
                it is StartTlsException || it is TlsHandshakeException ||
                    (starttls && it is SocketTimeoutException && !it.message.orEmpty().contains("connect", true))
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
    /** The servers that were found or entered; shown on the password page in ASCII form. */
    val servers: Servers? = null,
    val source: Source? = null,
    val notes: Set<ProviderTable.Note> = emptySet(),
    /** The current sign-in page was reached from the server settings page. */
    val fromServers: Boolean = false,
    /** The chance of the RFC 6186 retry with the local part as user name was used (once per setup session). */
    val loginRetried: Boolean = false,
    /** The address for which "Did you mean" was shown; Next with the same address continues. */
    val typoChecked: String? = null,
    /** The issuer of the own-client page. */
    val ownClient: OAuthIssuer = OAuthIssuer.GOOGLE,
    val reauth: Account? = null,
    /** "Change server settings" of an account on other servers: sign in again starts on the server settings. */
    val editServers: Boolean = false,
    /** Sign-in methods on the Google or Microsoft page, in display order. */
    val methods: List<Method> = emptyList(),
    val busy: Busy? = null,
    val problem: Problem? = null,
    val emailError: InputError? = null,
    val passwordError: InputError? = null,
    val serverErrors: ServerErrors? = null,
    val clientIdError: Boolean = false,
    val signedIn: SignedIn? = null,
    /** Gmail's one-time note about the sender IP in sent mail (DESIGN §12). */
    val showIpNote: Boolean = false,
    val allOffline: Boolean = false,
    /** The flow is over: the screen closes. */
    val done: Boolean = false,
) {
    /** The first page: Welcome when adding an account; when signing in again, the servers or the provider's page. */
    val rootPage: Page
        get() = when {
            reauth == null -> Page.WELCOME
            editServers -> Page.SERVERS
            else -> pageFor(reauth.provider)
        }

    /** True when Back stays in the flow (or is blocked) instead of closing the screen. */
    val handlesBack: Boolean get() = busy != null || page != rootPage || (fromServers && page in SIGN_IN_PAGES)

    /** The own-client entry: always when adding, else only for the OAuth methods. */
    val offersOwnClient: Boolean get() = reauth == null || reauth.auth != AuthKind.PASSWORD

    /** "Change server settings" on the password page: only for accounts on other servers (never Riseup). */
    val offersServerChange: Boolean get() = provider == Provider.OTHER

    /** The issuer of the browser sign-in for this flow. */
    val issuer: OAuthIssuer
        get() = if (page == Page.OWN_CLIENT) ownClient else provider?.let(OAuthIssuer::of) ?: OAuthIssuer.GOOGLE

    /**
     * True when a refused password may be tested once more with the local part as user name (RFC 6186 §4): only
     * for servers found by DNS SRV, without a user name, and only for the first password test of the session.
     */
    val retriesLogin: Boolean
        get() = source == Source.SRV && provider == Provider.OTHER && servers?.login == null && !loginRetried

    /**
     * True when a browser sign-in may complete now: no other work runs, and the flow still wants an OAuth
     * account. [reauthMode] is true for "Sign in again", also while its account loads.
     */
    fun acceptsBrowserResult(reauthMode: Boolean): Boolean = when {
        done || (busy != null && busy != Busy.BROWSER) -> false
        reauthMode -> reauth?.auth == AuthKind.OAUTH
        else -> page in BROWSER_PAGES
    }

    fun detecting() = start(Busy.DETECTING)

    /**
     * The state after Next ([manual] false) or "Enter the server settings yourself" ([manual] true) on Welcome for
     * the checked [address] at [domain], before any network request. [known]: an account with this address exists.
     * [suggestion]: the typo check of Next. Busy [Busy.DETECTING] means: start discovery now.
     */
    fun welcomed(address: String, domain: String, known: Boolean, suggestion: String?, manual: Boolean): SetupState =
        when {
            known -> failed(Problem(Problem.Kind.ALREADY_ADDED))
            manual -> manual(Discovery.guess(domain), Source.MANUAL, emptySet())
            suggestion != null && typoChecked != address -> typo(suggestion, address)
            else -> detecting()
        }

    /** Discovery found the servers: the page of the provider. */
    fun found(found: Outcome.Found, methods: List<Method>) = copy(
        page = pageFor(found.provider),
        provider = found.provider,
        servers = found.servers,
        source = found.source,
        notes = found.notes,
        methods = methods,
        fromServers = false,
        busy = null,
        problem = null,
        emailError = null,
        passwordError = null,
    )

    /** The provider of the address cannot be added; Welcome says why. */
    fun refused(refused: Outcome.Refused) = copy(
        page = Page.WELCOME,
        busy = null,
        problem = Problem(
            if (refused.reason == ProviderTable.Refusal.BRIDGE) Problem.Kind.BRIDGE else Problem.Kind.UNSUPPORTED,
            refused.name,
        ),
    )

    /**
     * The server settings page, filled in with [guess]. [source] is MANUAL for "Enter the server settings
     * yourself", else the source of Discovery's NotFound (GUESS, SRV or MX).
     */
    fun manual(guess: Servers, source: Source, notes: Set<ProviderTable.Note>) = copy(
        page = Page.SERVERS,
        provider = Provider.OTHER,
        servers = guess,
        source = source,
        notes = notes,
        fromServers = false,
        busy = null,
        problem = null,
        emailError = null,
        serverErrors = null,
    )

    /** "Change server settings" on the password page; the fields show the current servers. */
    fun changeServers() = copy(page = Page.SERVERS, problem = null, passwordError = null, serverErrors = null)

    fun invalidServers(errors: ServerErrors) = copy(serverErrors = errors, problem = null)

    /**
     * The user entered [servers] for [email]. A pair with one built-in host (Google, Microsoft, Riseup) and one other
     * host is refused; built-in hosts lead to that provider's page with its built-in servers (D18). [methods] gives
     * the sign-in methods of a provider.
     */
    fun serversEntered(servers: Servers, email: String, methods: (Provider) -> List<Method>): SetupState {
        val provider = ProviderTable.providerOf(servers)
            ?: return failed(Problem(Problem.Kind.MIXED_SERVERS, serverBrand(knownProvider(servers))))
        if (reauth != null && provider != reauth.provider) {
            return failed(Problem(Problem.Kind.WRONG_SERVERS, serverBrand(provider)))
        }
        val pinned = ProviderTable.pinned(provider, email)
        val tableNotes = ProviderTable.byDomain(email.substringAfterLast('@'))
            ?.takeIf { it.provider == provider }?.notes.orEmpty()
        return copy(
            page = pageFor(provider),
            provider = provider,
            servers = pinned ?: servers,
            source = Source.MANUAL,
            notes = if (pinned == null) notes else tableNotes,
            methods = methods(provider),
            fromServers = true,
            busy = null,
            problem = null,
            serverErrors = null,
        )
    }

    /** "Did you mean" for the typed [address]; Next with the same address continues. */
    fun typo(suggestion: String, address: String) =
        copy(page = Page.WELCOME, busy = null, problem = Problem(Problem.Kind.TYPO, suggestion), typoChecked = address)

    fun ownClient(issuer: OAuthIssuer) =
        copy(page = Page.OWN_CLIENT, ownClient = issuer, problem = null, clientIdError = false)

    fun clientSaved(methods: List<Method>) =
        copy(page = pageFor(ownClient), methods = methods, clientIdError = false, problem = null)

    fun invalid(email: InputError?, password: InputError?) =
        copy(emailError = email, passwordError = password, problem = null)

    fun start(busy: Busy) = copy(busy = busy, problem = null, emailError = null, passwordError = null)

    fun failed(problem: Problem) = copy(busy = null, problem = problem)

    /** The first password test of the session used the chance of the user-name retry. */
    fun loginRetryUsed() = copy(loginRetried = true)

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

    /**
     * The state after Back, or null when Back leaves the screen. Saving cannot be interrupted; other work is
     * cancelled (detecting returns to Welcome). The typed address stays in the ViewModel.
     */
    fun back(): SetupState? = when {
        busy == Busy.SAVING -> this
        busy != null -> copy(busy = null)
        page == Page.PERMISSIONS || page == Page.SIGNED_IN -> finished()
        fromServers && page in SIGN_IN_PAGES ->
            copy(page = Page.SERVERS, fromServers = false, problem = null, passwordError = null)
        page == rootPage -> null
        page == Page.OWN_CLIENT -> copy(page = pageFor(ownClient), problem = null, clientIdError = false)
        page == Page.OPTIONS -> copy(page = provider?.let(::pageFor) ?: Page.WELCOME, signedIn = null, problem = null)
        reauth != null -> copy(
            page = rootPage,
            provider = reauth.provider,
            servers = reauth.servers,
            source = Source.MANUAL,
            fromServers = false,
            problem = null,
            passwordError = null,
            serverErrors = null,
        )
        else -> SetupState(loginRetried = loginRetried, typoChecked = typoChecked)
    }

    companion object {
        private val BROWSER_PAGES = setOf(Page.WELCOME, Page.GOOGLE, Page.MICROSOFT, Page.OWN_CLIENT)

        /** The pages that [serversEntered] opens. */
        private val SIGN_IN_PAGES = setOf(Page.PASSWORD, Page.GOOGLE, Page.MICROSOFT)

        /**
         * The state for "Sign in again" to [account]. [editServers] starts on the server settings; only accounts
         * on other servers with a password have them.
         */
        fun signInAgain(account: Account, methods: List<Method>, editServers: Boolean = false): SetupState {
            val edit = editServers && account.provider == Provider.OTHER && account.auth == AuthKind.PASSWORD
            val state = SetupState(
                provider = account.provider,
                servers = account.servers,
                source = Source.MANUAL,
                reauth = account,
                editServers = edit,
                methods = methods,
            )
            return state.copy(page = state.rootPage)
        }

        /** The sign-in page of [provider]. */
        fun pageFor(provider: Provider) = when (provider) {
            Provider.GMAIL -> Page.GOOGLE
            Provider.MICROSOFT -> Page.MICROSOFT
            Provider.RISEUP, Provider.OTHER -> Page.PASSWORD
        }

        fun pageFor(issuer: OAuthIssuer) = when (issuer) {
            OAuthIssuer.GOOGLE -> Page.GOOGLE
            OAuthIssuer.MICROSOFT -> Page.MICROSOFT
        }

        /** The provider whose accounts sign in with [issuer]. */
        fun providerOf(issuer: OAuthIssuer) = when (issuer) {
            OAuthIssuer.GOOGLE -> Provider.GMAIL
            OAuthIssuer.MICROSOFT -> Provider.MICROSOFT
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

        /** Microsoft signs in only in the browser, with the user's own client ID ([browser]). */
        fun microsoftMethods(browser: Boolean): List<Method> = listOfNotNull(Method.BROWSER.takeIf { browser })

        /** The name of a built-in provider in the server texts ("a Microsoft server", not "a Outlook server"). */
        internal fun serverBrand(provider: Provider?): String = when (provider) {
            Provider.MICROSOFT -> OAuthIssuer.MICROSOFT.brand
            null -> Provider.OTHER.brand
            else -> provider.brand
        }

        /** The built-in provider of one host of a mixed pair. */
        private fun knownProvider(servers: Servers): Provider? = sequenceOf(servers.imap, servers.smtp)
            .mapNotNull { ProviderTable.providerOf(Servers(it, it)) }
            .firstOrNull { it != Provider.OTHER }
    }
}

/** The text of the server settings page: nothing found, values from DNS to check, or the help pages. */
@StringRes
internal fun serversText(source: Source?): Int = when (source) {
    Source.GUESS -> R.string.setup_servers_not_found
    Source.SRV, Source.MX -> R.string.setup_servers_dns
    else -> R.string.setup_servers_text
}

/** Table notes on the server settings page, in this order: the user name, app passwords, IMAP access. */
internal val SERVER_NOTES = listOf(ProviderTable.Note.ICLOUD_LOGIN, ProviderTable.Note.APP_PASSWORD, ProviderTable.Note.ENABLE_IMAP)

/** Table notes before the password field. */
internal val PASSWORD_NOTES = listOf(ProviderTable.Note.APP_PASSWORD, ProviderTable.Note.ENABLE_IMAP)

@StringRes
internal fun noteText(note: ProviderTable.Note): Int = when (note) {
    ProviderTable.Note.APP_PASSWORD -> R.string.setup_note_app_password
    ProviderTable.Note.ENABLE_IMAP -> R.string.setup_note_enable_imap
    ProviderTable.Note.ICLOUD_LOGIN -> R.string.setup_note_icloud_login
}

/** Checks and normalizes what the user types during setup. */
object SetupInput {
    private const val MAX_USER = 254

    /** Trimmed and lower-case (account.email is UNIQUE with case). */
    fun normalizeEmail(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    /** Checks a [normalizeEmail] result: a full address whose domain is a usable host name (no bare name). */
    fun checkEmail(address: String): InputError? {
        val domain = address.substringAfterLast('@', "")
        return when {
            address.isEmpty() -> InputError.REQUIRED
            !Addresses.isValidAddrSpec(address) || '.' !in domain || Discovery.normalizeHost(domain) == null ->
                InputError.INVALID
            else -> null
        }
    }

    /** A password is required; Gmail app passwords count without their spaces. */
    fun checkPassword(provider: Provider, password: CharSequence): InputError? =
        if (PasswordCredentials.normalize(provider, password.toString()).isEmpty()) InputError.REQUIRED else null

    /**
     * A server name in ASCII lower case (IDN to punycode), or the error: empty, an IP address (its certificate
     * cannot be checked), a `host:port` form, or a name that is not usable (single label, `.local` and the like).
     */
    fun checkHost(raw: String): Pair<String?, InputError?> {
        val host = raw.trim()
        val hostAndPort = host.count { it == ':' } == 1 && !host.startsWith('[')
        return when {
            host.isEmpty() -> null to InputError.REQUIRED
            hostAndPort -> null to InputError.INVALID
            Idn.isIpAddress(host) -> null to InputError.IP_ADDRESS
            else -> Discovery.normalizeHost(host)?.let { it to null } ?: (null to InputError.INVALID)
        }
    }

    /** A port from 1 to 65535. */
    fun checkPort(raw: String): Pair<Int?, InputError?> {
        val digits = raw.trim()
        val port = digits.takeIf { it.length in 1..5 && it.all { c -> c in '0'..'9' } }?.toInt()
        return if (port != null && port in 1..65_535) port to null else null to InputError.PORT
    }

    /** The user name without control characters; null when empty (the email address is used). */
    fun cleanUser(raw: String): String? = raw.filterNot { it.isISOControl() }.trim().take(MAX_USER).ifEmpty { null }

    /** The usual port of [tls] for IMAP ([imap]) or SMTP submission. */
    fun defaultPort(imap: Boolean, tls: TlsMode): Int = when (tls) {
        TlsMode.IMPLICIT -> if (imap) 993 else 465
        TlsMode.STARTTLS -> if (imap) 143 else 587
    }

    /** After a change of the security to [to]: the usual port of [to] when [port] is the usual port of the other mode. */
    fun switchPort(port: String, imap: Boolean, to: TlsMode): String {
        val other = if (to == TlsMode.IMPLICIT) TlsMode.STARTTLS else TlsMode.IMPLICIT
        return if (port.trim() == defaultPort(imap, other).toString()) defaultPort(imap, to).toString() else port
    }

    /** The fields of the server settings page as [Servers], or the errors of the fields. */
    fun servers(
        imapHost: String,
        imapPort: String,
        imapTls: TlsMode,
        smtpHost: String,
        smtpPort: String,
        smtpTls: TlsMode,
        user: String,
    ): Pair<Servers?, ServerErrors?> {
        val (inHost, inHostError) = checkHost(imapHost)
        val (inPort, inPortError) = checkPort(imapPort)
        val (outHost, outHostError) = checkHost(smtpHost)
        val (outPort, outPortError) = checkPort(smtpPort)
        if (inHost == null || inPort == null || outHost == null || outPort == null) {
            return null to ServerErrors(inHostError, inPortError, outHostError, outPortError)
        }
        return Servers(Endpoint(inHost, inPort, imapTls), Endpoint(outHost, outPort, smtpTls), cleanUser(user)) to null
    }

    /**
     * True when [a] and [b] are the same mailbox: case is not important, and for Gmail the dots in the local part
     * are not important and googlemail.com is gmail.com.
     */
    fun sameMailbox(a: String, b: String): Boolean = canonical(a) == canonical(b)

    private fun canonical(address: String): String {
        val lower = address.trim().lowercase(Locale.ROOT)
        val domain = lower.substringAfterLast('@', "")
        if (domain != "gmail.com" && domain != "googlemail.com") return lower
        return lower.substringBeforeLast('@').replace(".", "") + "@gmail.com"
    }
}

/** True when one of the servers uses STARTTLS. */
internal val Servers.usesStartTls: Boolean get() = imap.tls == TlsMode.STARTTLS || smtp.tls == TlsMode.STARTTLS
