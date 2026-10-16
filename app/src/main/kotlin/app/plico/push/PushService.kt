package app.plico.push

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** specialUse foreground service that keeps the IDLE connections (DESIGN.md §8.1). */
class PushService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
