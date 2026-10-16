package app.plico.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import app.plico.PlicoApp
import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Provider
import app.plico.data.PushMode
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Channels and alert notifications on the device. No account is added to the database. */
class NotifierTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as PlicoApp
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val notifier = Notifier(app)

    private fun account(id: Long, label: String) = Account(
        id = id, provider = Provider.RISEUP, email = "test$id@example.invalid", senderName = "", label = label,
        color = 0, auth = AuthKind.PASSWORD, deviceAccount = null, bodyDays = 30, pushMode = PushMode.INSTANT,
        pollMinutes = 15, gmailCategories = emptySet(), archiveFolder = null, signature = "", state = AccountState.OK,
        stateText = null, serverKeepaliveS = null, quotaUsed = null, quotaLimit = null, sort = 0,
    )

    @Before
    fun allowNotifications() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun cleanUp() {
        nm.cancelAll()
        nm.notificationChannels.forEach { nm.deleteNotificationChannel(it.id) }
    }

    @Test
    fun channelsHaveTheirImportanceAndFollowTheLabel() {
        notifier.ensureChannels(listOf(account(9001, "Work")))
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(Notifier.SERVICE_CHANNEL).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Notifier.ALERTS_CHANNEL).importance)
        val mail = nm.getNotificationChannel(Notifier.mailChannel(9001))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, mail.importance)
        assertEquals("Work", mail.name)

        notifier.ensureChannels(listOf(account(9001, "Home")))
        assertEquals("Home", nm.getNotificationChannel(Notifier.mailChannel(9001)).name)

        notifier.removeStaleChannels(keep = emptySet())
        assertNull(nm.getNotificationChannel(Notifier.mailChannel(9001)))
        assertNotNull(nm.getNotificationChannel(Notifier.SERVICE_CHANNEL))
    }

    @Test
    fun serviceNotificationIsQuietAndNeutral() {
        val n = notifier.serviceNotification("Connected")
        assertEquals(Notifier.SERVICE_CHANNEL, n.channelId)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Connected", n.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun signInAlertHidesTheAccountOnTheLockScreen() {
        notifier.signInNeeded(account(9002, "Riseup"), intent = null)
        val n = awaitPosted { it.tag == "signin:9002" }.notification
        assertEquals(Notifier.ALERTS_CHANNEL, n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = assertNotNull(n.publicVersion)
        // A default label is not shown in public; no address anywhere.
        assertNull(public.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val texts = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT)
            .flatMap { key -> listOf(n, public).mapNotNull { it.extras.getCharSequence(key)?.toString() } }
        assertEquals("Riseup: sign in again to get new mail.", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(texts.none { '@' in it })

        notifier.clearProblem(9002)
        awaitGone { it.tag == "signin:9002" }
    }

    @Test
    fun pushStoppedOffersResume() {
        notifier.pushStopped(silent = true)
        val n = awaitPosted { it.notification.channelId == Notifier.ALERTS_CHANNEL && it.tag == null }.notification
        assertEquals(1, n.actions.size)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        notifier.clearPushStopped()
        awaitGone { it.notification.channelId == Notifier.ALERTS_CHANNEL && it.tag == null }
    }

    // The system lists a new notification after a short delay.
    private fun awaitPosted(match: (StatusBarNotification) -> Boolean): StatusBarNotification {
        val until = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < until) {
            nm.activeNotifications.firstOrNull(match)?.let { return it }
            Thread.sleep(50)
        }
        error("notification not posted")
    }

    private fun awaitGone(match: (StatusBarNotification) -> Boolean) {
        val until = System.currentTimeMillis() + 3_000
        while (nm.activeNotifications.any(match)) {
            check(System.currentTimeMillis() < until) { "notification still shown" }
            Thread.sleep(50)
        }
    }
}
