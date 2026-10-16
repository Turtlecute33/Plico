package app.plico.ui.mailbox

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plico.R
import app.plico.app
import app.plico.data.Draft
import app.plico.data.DraftState
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.components.rememberDateFormats
import app.plico.ui.nav.Navigator
import app.plico.ui.nav.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The local drafts of account [accountId] while it has no Drafts mailbox (Riseup creates it on the first
 * upload): the drawer's Drafts entry opens this list, so that queued and failed drafts stay reachable
 * (DESIGN §7.5). A Drafts mailbox shows the same rows on top of its list.
 */
@Composable
fun DraftsScreen(accountId: Long, nav: Navigator) {
    val app = LocalContext.current.app
    val drafts by remember(app, accountId) {
        app.drafts.observeByAccount(accountId).map { list -> list.filter { it.state != DraftState.SENT } }
    }.collectAsStateWithLifecycle(null)
    val account by remember(app, accountId) { app.accounts.observe(accountId) }.collectAsStateWithLifecycle(null)
    val dates = rememberDateFormats()
    val scope = rememberCoroutineScope()
    var confirmRetry by remember { mutableStateOf<Draft?>(null) }
    val retry = { draft: Draft -> scope.launch(Dispatchers.IO) { app.mail.retrySend(draft.id) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mailbox_folder_drafts)) },
                subtitle = { account?.let { Text(it.label, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = {
                    IconButton(onClick = nav::back) { Icon(Icons.Back, stringResource(R.string.ui_back)) }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        val list = drafts ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyState(
                icon = Icons.Draft,
                title = stringResource(R.string.mailbox_empty_drafts),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { draft ->
                    LocalDraftRow(
                        draft = draft,
                        date = dates.list(draft.updated),
                        onOpen = { nav.go(Route.Compose(draft.id)) },
                        onRetry = { if (draft.mayHaveBeenSent) confirmRetry = draft else retry(draft) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    confirmRetry?.let { draft ->
        SendAgainDialog(draft, onConfirm = { retry(draft) }, onDismiss = { confirmRetry = null })
    }
}
