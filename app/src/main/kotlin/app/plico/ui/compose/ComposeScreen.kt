package app.plico.ui.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.plico.R
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.nav.Navigator

/** Edit and send draft [draftId] (DESIGN §13 Compose). Placeholder until the compose screen exists. */
@Composable
fun ComposeScreen(draftId: Long, nav: Navigator) {
    EmptyState(
        icon = Icons.Edit,
        title = stringResource(R.string.ui_compose),
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    )
}
