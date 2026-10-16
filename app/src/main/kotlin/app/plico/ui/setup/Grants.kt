package app.plico.ui.setup

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager

/** The power grants that push needs (DESIGN §8.2), read from the platform. */
data class Grants(val notifications: Boolean, val battery: Boolean, val exactAlarms: Boolean) {
    /** Nothing left to ask: notifications, plus the battery exemption or its fallback, exact alarms. */
    val complete: Boolean get() = notifications && (battery || exactAlarms)

    /** Exact alarms are offered only after the user refused the battery exemption (§8.2 step 3). */
    fun offersExactAlarms(batteryRefused: Boolean): Boolean = !battery && (batteryRefused || exactAlarms)

    companion object {
        fun of(context: Context) = Grants(
            notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            battery = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        )
    }
}
