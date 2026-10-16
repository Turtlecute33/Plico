package app.plico.ui.message

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.plico.R
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.nav.Navigator

/** One message (DESIGN §13 Message). Placeholder until the message screen exists. */
@Composable
fun MessageScreen(messageId: Long, nav: Navigator) {
    EmptyState(
        icon = Icons.MarkRead,
        title = stringResource(R.string.ui_message),
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    )
}
