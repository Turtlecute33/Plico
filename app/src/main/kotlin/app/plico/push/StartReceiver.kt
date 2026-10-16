package app.plico.push

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.plico.app
import app.plico.push.StartPolicy.Source

/**
 * Starts push after a boot, an app update or an exact-alarm grant (DESIGN §8.1; push PB6, PB23). Disabled
 * while the user has paused push. A boot ends a Task Manager stop; the other actions respect it.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in ACTIONS) return
        val app = context.app
        val engine = app.push
        if (action == Intent.ACTION_BOOT_COMPLETED) app.prefs.pushStoppedBySystemUi = false
        if (action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) engine.refreshStatus()
        if (app.prefs.pushPaused || app.prefs.pushStoppedBySystemUi || engine.running) return

        val source = if (action == Intent.ACTION_BOOT_COMPLETED) Source.BOOT else Source.BACKGROUND
        runAsync(engine) {
            if (app.accounts.all().isNotEmpty() && !PushService.start(app, source)) {
                app.notifier.pushStopped(silent = false)
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
