package app.plico.push

import android.app.ActivityManager
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import app.plico.PlicoApp
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The service lifecycle on the device (DESIGN §8.1, push PB6): without an account, a start enters the
 * foreground and then stops itself. A stop before startForeground would crash this process.
 */
class PushServiceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PlicoApp

    @Test
    fun startWithoutAccountsStopsCleanly() {
        check(app.accounts.all().isEmpty()) { "the test device must have no account" }
        assertTrue(PushService.start(app, StartPolicy.Source.UI))

        val until = System.currentTimeMillis() + 5_000
        while (serviceRuns()) {
            check(System.currentTimeMillis() < until) { "the service did not stop" }
            Thread.sleep(50)
        }
        // The system would end the app soon after a stop without startForeground.
        Thread.sleep(2_000)
        assertFalse(serviceRuns())
        assertFalse(app.push.running)
        // The start was allowed: no "Push stopped" notification.
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Suppress("DEPRECATION") // still returns the caller's own services
    private fun serviceRuns() = app.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == PushService::class.java.name }
}
