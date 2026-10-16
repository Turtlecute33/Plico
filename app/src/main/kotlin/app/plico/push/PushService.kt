package app.plico.push

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import app.plico.app
import app.plico.notify.Notifier
import app.plico.push.StartPolicy.Decision
import app.plico.push.StartPolicy.Source

/**
 * The specialUse foreground service that keeps the IDLE connections (DESIGN §8.1; push PB6, apis AF1).
 * Every start enters the foreground first; only then does it decide whether push runs.
 */
class PushService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = app
        val engine = app.push
        try {
            val notification = app.notifier.serviceNotification(getString(engine.serviceText().text))
            startForeground(Notifier.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(LOG_TAG, "push start: ${e.javaClass.simpleName}")
            app.notifier.pushStopped(silent = false)
            engine.retryStartSoon()
            stopSelf()
            return START_NOT_STICKY
        }

        val source = sourceOf(intent)
        if (source == Source.RESUME) app.prefs.pushStoppedBySystemUi = false
        val decision = StartPolicy.decide(
            source = source,
            pausedByUser = app.prefs.pushPaused,
            stoppedBySystemUi = app.prefs.pushStoppedBySystemUi,
            firstStart = engine.takeFirstStart(),
            lastExitUserRequested = ::lastExitUserRequested,
        )
        when (decision) {
            Decision.RUN -> {
                engine.onServiceStarted()
                return START_STICKY
            }
            Decision.STOPPED_BY_USER -> {
                // The Task Manager stop is a pause; the surviving alarm must not undo it (push PB6).
                app.prefs.pushStoppedBySystemUi = true
                engine.cancelAlarm()
                app.notifier.pushStopped(silent = true)
            }
            Decision.STOP -> Unit
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    // specialUse has no time limit; this only guards against a future one.
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    override fun onDestroy() {
        app.push.onServiceStopped()
        super.onDestroy()
    }

    private fun lastExitUserRequested(): Boolean = getSystemService(ActivityManager::class.java)
        .getHistoricalProcessExitReasons(packageName, 0, 1)
        .firstOrNull()?.reason == ApplicationExitInfo.REASON_USER_REQUESTED

    internal companion object {
        private const val EXTRA_SOURCE = "app.plico.source"

        fun intent(context: Context, source: Source): Intent =
            Intent(context, PushService::class.java).putExtra(EXTRA_SOURCE, source.name)

        /** startForegroundService; false when Android refused the start from the background. */
        fun start(context: Context, source: Source): Boolean = try {
            context.startForegroundService(intent(context, source))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException.
            Log.w(LOG_TAG, "push start: ${e.javaClass.simpleName}")
            false
        }

        /** A sticky restart has no intent; it is a background start. */
        fun sourceOf(intent: Intent?): Source =
            intent?.getStringExtra(EXTRA_SOURCE)?.let { name -> Source.entries.firstOrNull { it.name == name } }
                ?: Source.BACKGROUND
    }
}
