package app.plico.ui.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import app.plico.R

/** Android settings pages and the browser, opened from the settings screens. */
internal object SystemPages {
    /** The system page of an account's new-mail channel (sound, importance, off). */
    fun mailChannel(context: Context, accountId: Long) = open(
        context,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, mailChannelId(accountId)),
        fallback = appNotificationsIntent(context),
    )

    fun appNotifications(context: Context) = open(context, appNotificationsIntent(context))

    /**
     * The direct prompt for the battery optimization exemption (DESIGN §8.2 step 2). Plico keeps an
     * IDLE connection in a foreground service, which is an accepted use of this prompt.
     */
    @SuppressLint("BatteryLife")
    fun batteryExemption(context: Context) = open(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun exactAlarms(context: Context) = open(
        context,
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)),
        fallback = appDetailsIntent(context),
    )

    /** App info, where the user changes the battery usage from Restricted (apis AF15). */
    fun appDetails(context: Context) = open(context, appDetailsIntent(context))

    /** Opens an https page in the default browser, like a link from a message (DESIGN §11.3). */
    @SuppressLint("UseKtx") // core-ktx is not a dependency (DESIGN §2)
    fun browser(context: Context, url: String) = open(
        context,
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
    )

    private fun appNotificationsIntent(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

    private fun open(context: Context, intent: Intent, fallback: Intent? = null) {
        for (candidate in listOfNotNull(intent, fallback)) {
            try {
                context.startActivity(candidate)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next page.
            }
        }
        Toast.makeText(context, R.string.settings_no_app, Toast.LENGTH_SHORT).show()
    }
}
