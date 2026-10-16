package app.plico.push

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import app.plico.notify.Notifier

/**
 * The grants push depends on (DESIGN §8.2; push PB23, apis AF15), and the system screens that change
 * them. Read them again at app start and when the "Push & battery" screen opens.
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
    companion object {
        /** Reads every grant now (binder calls; fast). */
        fun read(context: Context) = Grants(
            notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            backgroundRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
        )

        /** The direct "Allow background use" prompt (sideloaded app, DESIGN §8.2 step 2). */
        @SuppressLint("BatteryLife")
        fun batteryIntent(context: Context) =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

        /** "Alarms & reminders" for this app (step 3, when the battery prompt was refused). */
        fun exactAlarmIntent(context: Context) =
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))

        /** App info, where the user changes a "Restricted" battery setting. */
        fun appDetailsIntent(context: Context) =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

        /** The app's notification settings (all channels). */
        fun notificationSettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        /** The new-mail channel of one account (Settings → account → Notifications). */
        fun channelSettingsIntent(context: Context, accountId: Long): Intent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.mailChannel(accountId))

        @SuppressLint("UseKtx") // core-ktx is not a dependency (DESIGN §2)
        private fun packageUri(context: Context) = Uri.parse("package:" + context.packageName)
    }
}
