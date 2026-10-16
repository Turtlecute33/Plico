package app.plico.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.plico.R
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.nav.Navigator

/** App settings (DESIGN §13 Settings). Placeholder until the settings screens exist. */
@Composable
fun SettingsScreen(nav: Navigator) {
    EmptyState(
        icon = Icons.Settings,
        title = stringResource(R.string.ui_settings),
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    )
}

/** Settings of one account. Placeholder. */
@Composable
fun AccountSettingsScreen(accountId: Long, nav: Navigator) {
    EmptyState(
        icon = Icons.Account,
        title = stringResource(R.string.ui_account),
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    )
}

/** Version, licence, what the app connects to. Placeholder. */
@Composable
fun AboutScreen(nav: Navigator) {
    EmptyState(
        icon = Icons.Info,
        title = stringResource(R.string.ui_about),
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    )
}
