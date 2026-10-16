package app.plico.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.plico.BuildConfig
import app.plico.R
import app.plico.ui.components.Icons
import app.plico.ui.nav.Navigator

/** Version, licences, and every host the app connects to (DESIGN §12, §13). */
@Composable
fun AboutScreen(nav: Navigator) {
    SettingsScaffold(title = stringResource(R.string.ui_about), onBack = nav::back) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.settings_about_tagline),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        SettingsGroup(stringResource(R.string.settings_about_privacy)) {
            item {
                InfoRow(
                    it, stringResource(R.string.settings_about_no_tracking),
                    summary = stringResource(R.string.settings_about_no_tracking_text), icon = Icons.Check,
                )
            }
            item {
                InfoRow(
                    it, stringResource(R.string.settings_about_storage),
                    summary = stringResource(R.string.settings_about_storage_text), icon = Icons.Lock,
                )
            }
        }

        SettingsGroup(stringResource(R.string.settings_about_connections)) {
            connections().forEach { connection ->
                item { InfoRow(it, stringResource(connection.title), summary = connection.hosts.joinToString("\n")) }
            }
            item {
                InfoRow(
                    it, stringResource(R.string.settings_about_remote),
                    summary = stringResource(R.string.settings_about_remote_text),
                )
            }
            item {
                InfoRow(
                    it, stringResource(R.string.settings_about_browser),
                    summary = stringResource(R.string.settings_about_browser_text),
                )
            }
        }

        SettingsGroup(stringResource(R.string.settings_about_security)) {
            item {
                InfoRow(
                    it, stringResource(R.string.settings_about_tls),
                    summary = stringResource(R.string.settings_about_tls_text),
                )
            }
            item {
                val ct = certificateTransparency(Build.VERSION.SDK_INT)
                InfoRow(
                    it, stringResource(R.string.settings_about_ct),
                    summary = stringResource(if (ct) R.string.settings_about_ct_on else R.string.settings_about_ct_off),
                )
            }
        }

        SettingsGroup(stringResource(R.string.settings_about_licences)) {
            LICENCES.forEach { (name, licence) ->
                item { InfoRow(it, stringResource(name), summary = stringResource(licence)) }
            }
        }
    }
}

/** Plico's own licence (the LICENSE file), then the bundled third-party code and data (DESIGN §2). */
private val LICENCES = listOf(
    R.string.app_name to R.string.settings_licence_gpl,
    R.string.settings_licence_kotlin to R.string.settings_licence_apache,
    R.string.settings_licence_androidx to R.string.settings_licence_apache,
    R.string.settings_licence_jsoup to R.string.settings_licence_mit,
    R.string.settings_licence_symbols to R.string.settings_licence_apache,
    R.string.settings_licence_psl to R.string.settings_licence_mpl,
)
