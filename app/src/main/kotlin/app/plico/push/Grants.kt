package app.plico.push

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager

/**
 * The grants push depends on (DESIGN §8.2; push PB23, apis AF15). Read them again at app start and when the
 * "Push & battery" screen opens.
 */
data class Grants(
    /** POST_NOTIFICATIONS granted and notifications not turned off for the app. */
    val notifications: Boolean,
    /** REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: unrestricted alarms, FGS restarts from the background. */
    val batteryUnrestricted: Boolean,
    /** SCHEDULE_EXACT_ALARM. */
    val exactAlarms: Boolean,
    /** Battery usage "Restricted": no alarms and no FGS start from the background. */
    val backgroundRestricted: Boolean,
) {
    /** Exact alarms are possible: the alarm can also restart push after Android closed the app. */
    val reliable: Boolean get() = (batteryUnrestricted || exactAlarms) && !backgroundRestricted

    companion object {
        /** Reads every grant now (binder calls; fast). */
        fun read(context: Context) = Grants(
            notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            backgroundRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
        )
    }
}
