package app.plico.ui.settings

import androidx.annotation.StringRes
import app.plico.R
import app.plico.account.OAuthIssuer
import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Prefs
import app.plico.data.Provider
import app.plico.data.PushMode
import app.plico.html.Idn
import app.plico.html.LogoRules
import app.plico.mail.mime.Addresses
import app.plico.mail.mime.HeaderSafety
import app.plico.mail.net.TlsMode
import app.plico.push.PushStatus
import app.plico.sync.GmailSync
import java.net.URI
import java.util.Locale

/** The values that the settings offer (DESIGN §7.5, §8.3, §12, §13). */
internal object Choices {
    val UNDO_SEND_SECONDS = listOf(0, 5, 10, 20)

    /** 0 = lock at once. */
    val LOCK_TIMEOUT_MINUTES = listOf(0, 1, 5, 30)
    val POLL_MINUTES = listOf(15, 30, 60)

    /** 0 = all mail offline. */
    val BODY_DAYS = listOf(30, 90, 365, 0)

    const val LABEL_MAX = 40
    const val NAME_MAX = 100
    const val SIGNATURE_MAX = 2_000
}

@StringRes
internal fun PushMode.title(): Int = when (this) {
    PushMode.INSTANT -> R.string.settings_push_mode_instant
    PushMode.INSTANT_ON_WIFI -> R.string.settings_push_mode_wifi
    PushMode.POLL -> R.string.settings_push_mode_poll
}

@StringRes
internal fun PushMode.description(): Int = when (this) {
    PushMode.INSTANT -> R.string.settings_push_mode_instant_text
    PushMode.INSTANT_ON_WIFI -> R.string.settings_push_mode_wifi_text
    PushMode.POLL -> R.string.settings_push_mode_poll_text
}

@StringRes
internal fun Prefs.Swipe.title(): Int = when (this) {
    Prefs.Swipe.ARCHIVE -> R.string.settings_swipe_archive
    Prefs.Swipe.DELETE -> R.string.settings_swipe_delete
    Prefs.Swipe.READ -> R.string.settings_swipe_read
    Prefs.Swipe.STAR -> R.string.settings_swipe_star
    Prefs.Swipe.NONE -> R.string.settings_swipe_none
}

/** The name of a Gmail category from [GmailSync.CATEGORIES]. */
@StringRes
internal fun gmailCategoryTitle(category: String): Int = when (category) {
    "primary" -> R.string.settings_category_primary
    "social" -> R.string.settings_category_social
    "promotions" -> R.string.settings_category_promotions
    "updates" -> R.string.settings_category_updates
    "forums" -> R.string.settings_category_forums
    else -> throw IllegalArgumentException("Unknown Gmail category")
}

/**
 * [current] with [category] turned on or off, in the order of [GmailSync.CATEGORIES]. The last
 * category stays on: the category search needs at least one (DESIGN §7.3).
 */
internal fun withCategory(current: Set<String>, category: String, on: Boolean): Set<String> {
    val next = if (on) current + category else current - category
    val ordered = GmailSync.CATEGORIES.filter { it in next }
    return if (ordered.isEmpty()) current else ordered.toSet()
}

/** Short problem of an account for the accounts list, or null when it syncs. */
@StringRes
internal fun AccountState.problemTitle(): Int? = when (this) {
    AccountState.OK -> null
    AccountState.SIGN_IN_NEEDED -> R.string.settings_state_sign_in
    AccountState.TLS_PROBLEM -> R.string.settings_state_tls
    AccountState.SETUP_PROBLEM -> R.string.settings_state_setup
}

/** How the account signs in, for the account header. */
@StringRes
internal fun signInTitle(provider: Provider, auth: AuthKind): Int = when (auth) {
    AuthKind.PASSWORD ->
        if (provider == Provider.GMAIL) R.string.settings_auth_app_password else R.string.settings_auth_password
    AuthKind.OAUTH ->
        if (provider == Provider.MICROSOFT) R.string.settings_auth_browser_microsoft else R.string.settings_auth_browser
    AuthKind.DEVICE -> R.string.settings_auth_device
}

/** The note below the From addresses: Gmail and Riseup have their own rules. */
@StringRes
internal fun identitiesNote(provider: Provider): Int = when (provider) {
    Provider.GMAIL -> R.string.settings_identities_gmail
    Provider.RISEUP -> R.string.settings_identities_riseup
    else -> R.string.settings_identities_other
}

/** The name of a security setting (the same words as on setup's server settings page). */
@StringRes
internal fun securityTitle(mode: TlsMode): Int = when (mode) {
    TlsMode.IMPLICIT -> R.string.setup_security_tls
    TlsMode.STARTTLS -> R.string.setup_security_starttls
}

/** "Change server settings": only accounts on other servers that sign in with a password. */
internal fun canChangeServers(account: Account): Boolean =
    account.provider == Provider.OTHER && account.auth == AuthKind.PASSWORD

/** Wakes per day from a server keepalive every [intervalSeconds]; null when there is no measured value. */
internal fun wakesPerDay(intervalSeconds: Int?): Int? =
    intervalSeconds?.takeIf { it > 0 }?.let { (SECONDS_PER_DAY + it / 2) / it }

/** Checks per day when polling every [minutes]. */
internal fun pollsPerDay(minutes: Int): Int = if (minutes > 0) MINUTES_PER_DAY / minutes else 0

/**
 * The background cost of an account's push mode (DESIGN §8.3 "Keepalive cost"). An account whose server has no
 * IDLE polls: its estimate is the one of [PushMode.POLL]. [keepaliveWakes] are
 * the wakes from server keepalives while the connection is instant (on Wi-Fi only for
 * [PushMode.INSTANT_ON_WIFI]); null when the mode is not instant or nothing was measured.
 * [polls] are the checks per day while polling (on mobile data for [PushMode.INSTANT_ON_WIFI]).
 */
internal data class WakeEstimate(val keepaliveWakes: Int?, val polls: Int?)

internal fun wakeEstimate(mode: PushMode, serverKeepaliveS: Int?, pollMinutes: Int): WakeEstimate = when (mode) {
    PushMode.INSTANT -> WakeEstimate(wakesPerDay(serverKeepaliveS), null)
    PushMode.INSTANT_ON_WIFI -> WakeEstimate(wakesPerDay(serverKeepaliveS), pollsPerDay(pollMinutes))
    PushMode.POLL -> WakeEstimate(null, pollsPerDay(pollMinutes))
}

/** A measured keepalive interval as whole minutes from 90 s on (Dovecot sends about every 2 min), else null. */
internal fun roundedMinutes(seconds: Int): Int? = if (seconds >= 90) (seconds + 30) / 60 else null

/** Used share (0..1) of the server quota; null when the server reported no limit. */
internal fun quotaFraction(used: Long?, limit: Long?): Float? {
    if (used == null || limit == null || limit <= 0) return null
    return (used.toDouble() / limit).coerceIn(0.0, 1.0).toFloat()
}

/** One line of user text for labels and names: controls become spaces (privacy PS3), at most [max] chars. */
internal fun cleanLine(input: String, max: Int): String = HeaderSafety.clean(input).take(max).trim()

/** The account label, or null when it is empty. It also names the notification channel. */
internal fun cleanLabel(input: String): String? = cleanLine(input, Choices.LABEL_MAX).ifEmpty { null }

/** The signature as plain text with Unix line ends, without trailing white space. */
internal fun cleanSignature(input: String): String =
    input.replace("\r\n", "\n").replace('\r', '\n').take(Choices.SIGNATURE_MAX).trimEnd()

/**
 * OAuth client IDs (DESIGN §6). Google: `<project number>-<id>.apps.googleusercontent.com`. Microsoft (Entra): a
 * GUID, stored in lower case.
 */
internal object ClientIds {
    private val pattern = Regex("""\d+-[a-z0-9]+\.apps\.googleusercontent\.com""")
    private val guid = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

    /** The Google ID without white space; "" for an empty input (no client ID); null when it has the wrong form. */
    fun normalize(input: String): String? {
        val id = input.filterNot(Char::isWhitespace)
        return id.takeIf { it.isEmpty() || pattern.matches(it) }
    }

    /** The Microsoft ID without white space in lower case; "" for an empty input; null when it is not a GUID. */
    fun normalizeMicrosoft(input: String): String? {
        val id = input.filterNot(Char::isWhitespace).lowercase(Locale.ROOT)
        return id.takeIf { it.isEmpty() || guid.matches(it) }
    }
}

/** True when an account uses the Google client: a Gmail account that signs in with OAuth or the device account. */
internal fun usesGoogleClient(accounts: List<Account>): Boolean =
    accounts.any { it.isGmail && it.auth != AuthKind.PASSWORD }

/** True when an account uses the Microsoft client. */
internal fun usesMicrosoftClient(accounts: List<Account>): Boolean = accounts.any { it.provider == Provider.MICROSOFT }

/**
 * Settings → Sign-in clients shows when an account uses a client or a client ID is set. The first client ID is
 * entered on setup's own-client page.
 */
internal fun showsSignInClients(accounts: List<Account>, googleClientId: String, microsoftClientId: String): Boolean =
    usesGoogleClient(accounts) || usesMicrosoftClient(accounts) ||
        googleClientId.isNotBlank() || microsoftClientId.isNotBlank()

internal enum class IdentityProblem(@StringRes val text: Int) {
    INVALID(R.string.settings_identity_invalid),
    OWN_ADDRESS(R.string.settings_identity_own),
    EXISTS(R.string.settings_identity_exists),
}

/** Why [email] cannot be added as another From address of the account, or null when it can. */
internal fun identityProblem(email: String, accountEmail: String, existing: Collection<String>): IdentityProblem? {
    val address = email.trim()
    return when {
        !Addresses.isValidAddrSpec(address) -> IdentityProblem.INVALID
        address.equals(accountEmail, ignoreCase = true) -> IdentityProblem.OWN_ADDRESS
        existing.any { it.equals(address, ignoreCase = true) } -> IdentityProblem.EXISTS
        else -> null
    }
}

/** A row of Settings → Push & battery (DESIGN §8.2). */
internal enum class PushCheck {
    /** Battery usage is Restricted: push is off. */
    RESTRICTED,
    NOTIFICATIONS,

    /** Battery optimization exemption (direct prompt). */
    BATTERY,

    /** Asked only when the exemption is refused. */
    EXACT_ALARMS,

    /** Neither the exemption nor exact alarms: Android may stop push. No fix of its own. */
    MAY_STOP,
}

/** The rows to show for [status], most severe first, with true when the grant holds. */
internal fun pushChecks(status: PushStatus): List<Pair<PushCheck, Boolean>> = buildList {
    val grants = status.grants
    if (grants.backgroundRestricted) add(PushCheck.RESTRICTED to false)
    add(PushCheck.NOTIFICATIONS to grants.notifications)
    add(PushCheck.BATTERY to grants.batteryUnrestricted)
    if (!grants.batteryUnrestricted) {
        add(PushCheck.EXACT_ALARMS to grants.exactAlarms)
        if (!grants.exactAlarms) add(PushCheck.MAY_STOP to false)
    }
}

/** Title and summary string resources of a push check row. */
internal fun PushCheck.texts(ok: Boolean): Pair<Int, Int> = when (this) {
    PushCheck.RESTRICTED -> R.string.settings_check_restricted to R.string.settings_check_restricted_text
    PushCheck.NOTIFICATIONS -> if (ok) {
        R.string.settings_check_notifications to R.string.settings_check_notifications_ok
    } else {
        R.string.settings_check_notifications_off to R.string.settings_check_notifications_off_text
    }
    PushCheck.BATTERY -> if (ok) {
        R.string.settings_check_battery to R.string.settings_check_battery_ok
    } else {
        R.string.settings_check_battery_off to R.string.settings_check_battery_off_text
    }
    PushCheck.EXACT_ALARMS -> if (ok) {
        R.string.settings_check_alarms to R.string.settings_check_alarms_ok
    } else {
        R.string.settings_check_alarms_off to R.string.settings_check_alarms_off_text
    }
    PushCheck.MAY_STOP -> R.string.settings_check_may_stop to R.string.settings_check_may_stop_text
}

internal enum class PushState(@StringRes val text: Int) {
    NO_ACCOUNTS(R.string.settings_push_state_no_accounts),
    PAUSED(R.string.settings_push_state_paused),
    STOPPED(R.string.settings_push_state_stopped),
    RESTRICTED(R.string.settings_push_state_restricted),
    NO_NETWORK(R.string.settings_push_state_no_network),
    NOT_RUNNING(R.string.settings_push_state_not_running),
    RUNNING(R.string.settings_push_state_running),
}

internal fun pushState(status: PushStatus, hasAccounts: Boolean): PushState = when {
    !hasAccounts -> PushState.NO_ACCOUNTS
    status.pausedByUser -> PushState.PAUSED
    status.stoppedBySystem -> PushState.STOPPED
    status.grants.backgroundRestricted -> PushState.RESTRICTED
    !status.running -> PushState.NOT_RUNNING
    !status.hasNetwork -> PushState.NO_NETWORK
    else -> PushState.RUNNING
}

/**
 * A group of hosts that the app connects to (About, DESIGN §12). [text] describes hosts that vary (setup, the
 * websites of senders); it is shown in place of [hosts].
 */
internal data class Connection(@StringRes val title: Int, val hosts: List<String>, @StringRes val text: Int? = null)

/**
 * Every host the app itself contacts, from the [accounts] and the settings: the mail servers, the sign-in hosts of
 * an issuer whose client is used or set, setup's discovery, and the logo source when [logos] is on (null = off).
 * Remote content is listed as text in About, because its hosts vary.
 */
internal fun connections(
    accounts: List<Account>,
    googleClientId: String,
    microsoftClientId: String,
    logos: Prefs.LogoSource?,
): List<Connection> = buildList {
    val servers = accounts.flatMap { listOf(it.servers.imap, it.servers.smtp) }
        .filter { it.host.isNotEmpty() }
        .map { "${Idn.toAscii(it.host) ?: it.host}:${it.port}" }
        .distinct()
    if (servers.isNotEmpty()) add(Connection(R.string.settings_about_mail_servers, servers))
    if (usesGoogleClient(accounts) || googleClientId.isNotBlank()) {
        add(Connection(R.string.settings_about_google_sign_in, OAuthIssuer.GOOGLE.signInHosts()))
    }
    val microsoftOAuth = accounts.any { it.provider == Provider.MICROSOFT && it.auth == AuthKind.OAUTH }
    if (microsoftOAuth || microsoftClientId.isNotBlank()) {
        add(Connection(R.string.settings_about_microsoft_sign_in, OAuthIssuer.MICROSOFT.signInHosts()))
    }
    add(Connection(R.string.settings_about_setup, emptyList(), R.string.settings_about_setup_text))
    if (logos != null) {
        val hosts = LogoRules.hosts(logos)
        val text = if (hosts.isEmpty()) R.string.settings_about_logos_websites else null
        add(Connection(R.string.settings_about_logos, hosts, text))
    }
}

/** [connections] with the values of [prefs]. */
internal fun connections(accounts: List<Account>, prefs: Prefs): List<Connection> = connections(
    accounts,
    prefs.googleClientId,
    prefs.microsoftClientId,
    prefs.logoSource.takeIf { prefs.senderLogos },
)

private fun OAuthIssuer.signInHosts() = listOfNotNull(authUrl, tokenUrl, revokeUrl).map { URI(it).host }.distinct()

/** Certificate Transparency checks of the network security config work from Android 16 (API 36; apis AF10). */
internal fun certificateTransparency(sdkInt: Int): Boolean = sdkInt >= 36

private const val SECONDS_PER_DAY = 86_400
private const val MINUTES_PER_DAY = 1_440
