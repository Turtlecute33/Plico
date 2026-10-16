package app.plico.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.plico.app
import app.plico.push.StartPolicy.Source

/**
 * Target of the single coalesced alarm (DESIGN §8.3; push PB3, apis AF2). AlarmManager holds its wake lock
 * until this returns, so the `alarm` lock is taken first; its timeout releases it after the sessions took
 * theirs. The next alarm is armed before returning.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        app.power.holder("alarm").renew(LOCK_MS)
        val engine = app.push
        try {
            if (app.prefs.pushPaused || app.prefs.pushStoppedBySystemUi) return
            if (engine.running) {
                engine.onWake()
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
