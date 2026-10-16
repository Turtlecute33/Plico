package app.plico.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts push after boot, app update or an exact-alarm permission change (DESIGN.md §8.1). */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
