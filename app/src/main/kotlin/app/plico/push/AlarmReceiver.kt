package app.plico.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Target of the single coalesced alarm (DESIGN.md §8.3). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
