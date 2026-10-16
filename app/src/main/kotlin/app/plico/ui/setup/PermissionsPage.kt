package app.plico.ui.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager.PERMISSION_GRANTED
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
import app.plico.push.Grants
import app.plico.ui.components.Icons
import app.plico.ui.components.SystemPages

/**
 * The power grants of DESIGN §8.2: notifications, the battery exemption and, only after the user refused
 * it, exact alarms. Granted items show as done; the state is read again when the user comes back.
 */
@Composable
internal fun PermissionsPage(onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var grants by remember { mutableStateOf(Grants.read(context)) }
    var notificationsBlocked by rememberSaveable { mutableStateOf(false) }
    var batteryRefused by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        grants = Grants.read(context)
        onPauseOrDispose {}
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // After the second refusal Android shows no dialog; only the app's notification settings remain.
        notificationsBlocked = !granted &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
        grants = Grants.read(context)
    }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        grants = Grants.read(context)
        batteryRefused = !grants.batteryUnrestricted
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
            // Allowed but turned off on the system page, or refused twice: only that page can turn them on.
            val allowed = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED
            if (notificationsBlocked || allowed) {
                SystemPages.appNotifications(context)
            } else {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        GrantCard(
            icon = Icons.Battery,
            title = stringResource(R.string.setup_permission_battery),
            text = stringResource(R.string.setup_permission_battery_text),
            granted = grants.batteryUnrestricted,
            action = stringResource(R.string.setup_permission_allow),
        ) {
            try {
                battery.launch(SystemPages.batteryExemptionIntent(context))
            } catch (_: ActivityNotFoundException) {
                batteryRefused = true
            }
        }
        if (grants.offersExactAlarms(batteryRefused)) {
            GrantCard(
                icon = Icons.Alarm,
                title = stringResource(R.string.setup_permission_alarms),
                text = stringResource(R.string.setup_permission_alarms_text),
                granted = grants.exactAlarms,
                action = stringResource(R.string.setup_permission_allow),
            ) { SystemPages.exactAlarms(context) }
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
                    imageVector = Icons.CheckCircle,
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

/** Nothing left to ask (DESIGN §8.2): notifications, plus the battery exemption or its fallback, exact alarms. */
internal val Grants.complete: Boolean get() = notifications && (batteryUnrestricted || exactAlarms)

/** Exact alarms are offered only after the user refused the battery exemption (§8.2 step 3). */
internal fun Grants.offersExactAlarms(batteryRefused: Boolean): Boolean =
    !batteryUnrestricted && (batteryRefused || exactAlarms)
