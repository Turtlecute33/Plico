package app.plico.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * Owns the single coalesced alarm (DESIGN §8.3; push PB3, PB23, apis AF2): one broadcast PendingIntent to
 * [AlarmReceiver], so AlarmManager holds its wake lock until the receiver has taken its own. Times are
 * elapsedRealtime. Thread-safe.
 */
class Scheduler internal constructor(private val alarms: Alarms) {
    constructor(context: Context) : this(AndroidAlarms(context))

    /** How the alarm was armed: exact while idle, or inexact (may be late, cannot restart push). */
    enum class Kind { EXACT, INEXACT }

    /** The platform calls, replaced in unit tests. */
    internal interface Alarms {
        fun canScheduleExact(): Boolean
        fun batteryExempt(): Boolean
        fun setExact(at: Long)
        fun setInexact(at: Long)
        fun cancel()
    }

    /**
     * Arms the alarm at [at], or cancels it when [at] is null. Exact while idle when exact alarms are
     * allowed or the battery exemption holds (exempt apps may set exact while-idle alarms); else, or when
     * the grant was revoked meanwhile, inexact. Returns the kind armed.
     */
    @Synchronized
    fun arm(at: Long?): Kind? {
        if (at == null) {
            alarms.cancel()
            return null
        }
        if (alarms.canScheduleExact() || alarms.batteryExempt()) {
            try {
                alarms.setExact(at)
                return Kind.EXACT
            } catch (_: SecurityException) {
                // Grant revoked since the check: fall through to inexact.
            }
        }
        alarms.setInexact(at)
        return Kind.INEXACT
    }

    /** No alarm (push paused or stopped). */
    @Synchronized
    fun cancel() = alarms.cancel()

    private class AndroidAlarms(private val context: Context) : Alarms {
        private val manager = context.getSystemService(AlarmManager::class.java)
        private val power = context.getSystemService(PowerManager::class.java)
        private val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )

        override fun canScheduleExact() = manager.canScheduleExactAlarms()
        override fun batteryExempt() = power.isIgnoringBatteryOptimizations(context.packageName)
        override fun setExact(at: Long) =
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)

        override fun setInexact(at: Long) =
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)

        override fun cancel() = manager.cancel(intent)
    }
}
