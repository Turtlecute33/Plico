package app.plico.ui.mailbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.plico.R
import app.plico.data.Account
import app.plico.ui.components.Avatar
import app.plico.ui.components.Icons
import app.plico.ui.components.Menu
import app.plico.ui.components.MenuEntry
import app.plico.ui.components.PrivateTextInput
import app.plico.ui.components.rejectObscuredTouches

/**
 * The search-bar style top bar (DESIGN §13): drawer button, search field and account avatar. Tapping the
 * field expands [searchBar] (search mode); the results show in the list below, not in a dialog, so that
 * opening a hit animates like any other screen change. In search mode the leading button ends the search.
 * The buttons sit beside the field, not inside it: keyboard focus that enters the screen (after Back, for
 * example) lands on the drawer button, so the field gets focus and the keyboard only when the user taps it.
 */
@Composable
internal fun SearchTopBar(
    query: TextFieldState,
    searchBar: SearchBarState,
    account: Account?,
    menu: List<MenuEntry>,
    onEndSearch: () -> Unit,
    onMenu: () -> Unit,
    onAccount: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val searching = searchBar.targetValue == SearchBarValue.Expanded
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = SearchBarDefaults.inputFieldShape,
            color = SearchBarDefaults.colors().containerColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searching) {
                    IconButton(onClick = onEndSearch) {
                        Icon(Icons.Back, contentDescription = stringResource(R.string.mailbox_close_search))
                    }
                } else {
                    IconButton(onClick = onMenu) {
                        Icon(Icons.Menu, contentDescription = stringResource(R.string.mailbox_open_menu))
                    }
                }
                val field = Modifier.weight(1f)
                PrivateTextInput {
                    SearchBarDefaults.InputField(
                        textFieldState = query,
                        searchBarState = searchBar,
                        onSearch = { focus.clearFocus() },
                        modifier = field,
                        placeholder = { Text(stringResource(R.string.mailbox_search_hint)) },
                    )
                }
                when {
                    searching && query.text.isNotEmpty() -> IconButton(onClick = { query.clearText() }) {
                        Icon(Icons.Close, contentDescription = stringResource(R.string.mailbox_clear_search))
                    }
                    searching -> Unit
                    else -> {
                        if (menu.isNotEmpty()) Menu(menu)
                        AccountButton(account, onAccount)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountButton(account: Account?, onClick: () -> Unit) {
    val description = stringResource(R.string.mailbox_account_settings)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        if (account == null) {
            Icon(Icons.Account, contentDescription = null)
        } else {
            Avatar(account.senderName.ifBlank { account.email }, colorIndex = account.color, size = 32.dp)
        }
    }
}

/**
 * The contextual top bar of multi-select (DESIGN §13, simplicity EM19): Archive, Delete and Read/Unread
 * as buttons, the rest in the overflow menu.
 */
@Composable
internal fun SelectionTopBar(
    count: Int,
    actions: SelectionActions,
    onClear: () -> Unit,
    onAction: (RowAction) -> Unit,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onLabels: () -> Unit,
) {
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.mailbox_selected_count, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Close, contentDescription = stringResource(R.string.mailbox_action_clear_selection))
            }
        },
        actions = {
            if (actions.archive) ActionButton(RowAction.ARCHIVE, { onAction(RowAction.ARCHIVE) })
            actions.delete?.let { ActionButton(it, { onAction(it) }, Modifier.rejectObscuredTouches()) }
            ActionButton(actions.read, { onAction(actions.read) })
            Menu(
                buildList {
                    add(MenuEntry(stringResource(R.string.mailbox_action_select_all), onClick = onSelectAll))
                    add(MenuEntry(stringResource(actionLabel(actions.star))) { onAction(actions.star) })
                    if (actions.moveAccount != null) {
                        add(MenuEntry(stringResource(R.string.mailbox_action_move), onClick = onMove))
                    }
                    if (actions.labels) {
                        add(MenuEntry(stringResource(R.string.mailbox_action_labels), onClick = onLabels))
                    }
                    actions.spam?.let { add(MenuEntry(stringResource(actionLabel(it))) { onAction(it) }) }
                },
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}
