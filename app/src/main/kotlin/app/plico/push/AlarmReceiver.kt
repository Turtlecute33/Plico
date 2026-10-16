package app.plico.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.plico.app
import app.plico.push.StartPolicy.Source

/**
 * Target of the single coalesced alarm (DESIGN §8.3; push PB3, apis AF2). AlarmManager holds its wake lock
 * until this returns, so the `alarm` lock is taken first. While push runs, the engine releases it once the
 * sessions took theirs and the next alarm is armed; the timeout covers a service start.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        app.power.holder("alarm").renew(LOCK_MS)
        val engine = app.push
        try {
            if (app.prefs.pushPaused || app.prefs.pushStoppedBySystemUi) return
            if (engine.running) {
                engine.onAlarm()
            } else if (!PushService.start(context, Source.BACKGROUND)) {
                app.notifier.pushStopped(silent = false)
            }
        } finally {
            engine.reschedule()
        }
    }

    private companion object {
        const val LOCK_MS = 10_000L
    }
}
