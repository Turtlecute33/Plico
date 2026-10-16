package app.plico.ui.components

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import app.plico.R
import app.plico.notify.Notifier

/**
 * Android settings pages and the browser: every intent to another app's screen is built here. Each function
 * returns false when no app takes the intent; with [toast] the user is told so.
 */
internal object SystemPages {
    /** The system page of an account's new-mail channel (sound, importance, off). */
    fun mailChannel(context: Context, accountId: Long) = open(
        context,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.mailChannel(accountId)),
        fallback = appNotificationsIntent(context),
    )

    fun appNotifications(context: Context) = open(context, appNotificationsIntent(context))

    /**
     * The direct prompt for the battery optimization exemption (DESIGN §8.2 step 2). Plico keeps an IDLE
     * connection in a foreground service, which is an accepted use of this prompt (sideloaded; the manifest
     * declares the permission).
     */
    @SuppressLint("BatteryLife")
    fun batteryExemptionIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

    fun batteryExemption(context: Context) = open(
        context,
        batteryExemptionIntent(context),
        fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun exactAlarms(context: Context) = open(
        context,
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)),
        fallback = appDetailsIntent(context),
    )

    /** App info, where the user changes the battery usage from Restricted (apis AF15). */
    fun appDetails(context: Context) = open(context, appDetailsIntent(context))

    /** Enrol a screen lock or biometric for the app lock, else the security settings. */
    fun screenLock(context: Context) = open(
        context,
        Intent(Settings.ACTION_BIOMETRIC_ENROLL)
            .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG or DEVICE_CREDENTIAL),
        fallback = Intent(Settings.ACTION_SECURITY_SETTINGS),
    )

    /**
     * Opens [url] in the browser the user chose (DESIGN §11.3): ACTION_VIEW with CATEGORY_BROWSABLE, no component
     * and no selector.
     */
    @SuppressLint("UseKtx") // core-ktx is not a dependency (DESIGN §2)
    fun browser(context: Context, url: String, toast: Boolean = true) =
        open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE), toast = toast)

    private fun appNotificationsIntent(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

    private fun open(context: Context, intent: Intent, fallback: Intent? = null, toast: Boolean = true): Boolean {
        for (candidate in listOfNotNull(intent, fallback)) {
            try {
                context.startActivity(candidate)
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next page.
            }
        }
        if (toast) Toast.makeText(context, R.string.settings_no_app, Toast.LENGTH_SHORT).show()
        return false
    }
}
