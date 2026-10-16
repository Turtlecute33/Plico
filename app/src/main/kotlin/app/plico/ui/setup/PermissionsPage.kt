package app.plico.ui.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.plico.R
import app.plico.ui.components.Icons

/**
 * The power grants of DESIGN §8.2: notifications, the battery exemption and, only after the user refused
 * it, exact alarms. Granted items show as done; the state is read again when the user comes back.
 */
@Composable
internal fun PermissionsPage(onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var grants by remember { mutableStateOf(Grants.of(context)) }
    var notificationsBlocked by rememberSaveable { mutableStateOf(false) }
    var batteryRefused by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        grants = Grants.of(context)
        onPauseOrDispose {}
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // After the second refusal Android shows no dialog; only the app's notification settings remain.
        notificationsBlocked = !granted &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
        grants = Grants.of(context)
    }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        grants = Grants.of(context)
        batteryRefused = !grants.battery
    }

    PageColumn {
        Headline(stringResource(R.string.setup_permissions_title), stringResource(R.string.setup_permissions_text))
        GrantCard(
            icon = Icons.Notifications,
            title = stringResource(R.string.setup_permission_notifications),
            text = stringResource(R.string.setup_permission_notifications_text),
            granted = grants.notifications,
            action = stringResource(
                if (notificationsBlocked) R.string.setup_permission_settings else R.string.setup_permission_allow,
            ),
        ) {
            if (notificationsBlocked) {
                start(context, notificationSettings(context))
            } else {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        GrantCard(
            icon = Icons.Battery,
            title = stringResource(R.string.setup_permission_battery),
            text = stringResource(R.string.setup_permission_battery_text),
            granted = grants.battery,
            action = stringResource(R.string.setup_permission_allow),
        ) {
            try {
                battery.launch(batteryExemption(context))
            } catch (_: ActivityNotFoundException) {
                batteryRefused = true
            }
        }
        if (grants.offersExactAlarms(batteryRefused)) {
            GrantCard(
                icon = SetupIcons.Alarm,
                title = stringResource(R.string.setup_permission_alarms),
                text = stringResource(R.string.setup_permission_alarms_text),
                granted = grants.exactAlarms,
                action = stringResource(R.string.setup_permission_allow),
            ) { start(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))) }
        }
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.setup_done)) }
    }
}

@Composable
private fun GrantCard(
    icon: ImageVector,
    title: String,
    text: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    SetupCard(icon = icon, title = title, text = text) {
        if (granted) {
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = SetupIcons.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.setup_permission_granted),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            FilledTonalButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

// Push needs a background connection (DESIGN §8.2 step 2); Plico is sideloaded, the manifest declares the permission.
@SuppressLint("BatteryLife")
private fun batteryExemption(context: Context) =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

private fun notificationSettings(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)
