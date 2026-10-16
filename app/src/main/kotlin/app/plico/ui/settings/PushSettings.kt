package app.plico.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plico.R
import app.plico.app
import app.plico.ui.components.Icons

/**
 * Settings → Push & battery (DESIGN §8.2): push on or paused, and every grant that push needs, each
 * with a button to the system page that fixes it. The grants are read again at every resume.
 */
@Composable
internal fun PushGroup(hasAccounts: Boolean) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val push = context.app.push
    val status by push.status.collectAsStateWithLifecycle()
    LifecycleResumeEffect(push) {
        push.refreshStatus()
        onPauseOrDispose {}
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // No dialog after two refusals: only the system page can allow notifications then.
        val blocked = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
        if (!granted && blocked) SystemPages.appNotifications(context)
        push.refreshStatus()
    }
    val fixNotifications = {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            // Allowed, but notifications were turned off on the system page.
            SystemPages.appNotifications(context)
        } else {
            askNotifications.launch(permission)
        }
    }

    SettingsGroup(stringResource(R.string.settings_push)) {
        item {
            SwitchRow(
                shapes = it,
                title = stringResource(R.string.settings_push_on),
                checked = !status.pausedByUser && !status.stoppedBySystem,
                onCheckedChange = { on -> if (on) push.resume() else push.pauseByUser() },
                summary = stringResource(pushState(status, hasAccounts).text),
                enabled = hasAccounts,
            )
        }
        pushChecks(status).forEach { (check, ok) ->
            item {
                val (title, summary) = check.texts(ok)
                val fix: (() -> Unit)? = when {
                    ok -> null
                    check == PushCheck.RESTRICTED -> ({ SystemPages.appDetails(context) })
                    check == PushCheck.NOTIFICATIONS -> fixNotifications
                    check == PushCheck.BATTERY -> ({ SystemPages.batteryExemption(context) })
                    check == PushCheck.EXACT_ALARMS -> ({ SystemPages.exactAlarms(context) })
                    else -> null
                }
                val fixLabel = if (check == PushCheck.RESTRICTED) R.string.settings_change else R.string.settings_allow
                InfoRow(
                    shapes = it,
                    title = stringResource(title),
                    summary = stringResource(summary),
                    leading = {
                        Icon(
                            imageVector = if (ok) Icons.Check else Icons.Warning,
                            contentDescription = null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    },
                    trailing = fix?.let { action ->
                        { FilledTonalButton(onClick = action) { Text(stringResource(fixLabel)) } }
                    },
                )
            }
        }
    }
}
