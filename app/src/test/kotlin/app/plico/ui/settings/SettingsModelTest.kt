package app.plico.ui.settings

import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Prefs
import app.plico.data.Provider
import app.plico.data.PushMode
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
            ),
        )
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
    fun connectionsListEveryHost() {
        val hosts = connections().flatMap { it.hosts }
        assertEquals(
            listOf(
                "mail.riseup.net:993",
                "mail.riseup.net:465",
                "imap.gmail.com:993",
                "smtp.gmail.com:465",
                "accounts.google.com",
                "oauth2.googleapis.com",
            ),
            hosts,
        )
        assertDistinct(connections().map { it.title })
    }

    @Test
    fun certificateTransparencyFromAndroid16() {
        assertFalse(certificateTransparency(35))
        assertTrue(certificateTransparency(36))
        assertTrue(certificateTransparency(37))
    }

    private fun assertDistinct(values: List<Any?>) {
        assertTrue(values.none { it == null || it == 0 }, "missing text in $values")
        assertEquals(values.size, values.toSet().size, "duplicate in $values")
    }
}
