package app.plico.ui.setup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.plico.R
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.nav.Navigator

/** Add an account (DESIGN §13 Setup). Placeholder until the setup screens exist. */
@Composable
fun SetupScreen(nav: Navigator) {
    EmptyState(
        icon = Icons.Account,
        title = stringResource(R.string.ui_welcome),
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    )
}
