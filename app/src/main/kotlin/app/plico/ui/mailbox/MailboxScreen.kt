package app.plico.ui.mailbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.plico.R
import app.plico.data.MailView
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.nav.Navigator

/** A message list for [view] (DESIGN §13 Mailbox). Placeholder until the mailbox screen exists. */
@Composable
fun MailboxScreen(view: MailView, nav: Navigator) {
    EmptyState(
        icon = Icons.Inbox,
        title = stringResource(R.string.ui_all_inboxes),
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    )
}
