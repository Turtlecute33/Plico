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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.plico.ui.components.PrivateTextInput
import app.plico.ui.components.rejectObscuredTouches

/** An entry of an overflow menu. */
internal data class MenuEntry(val label: String, val onClick: () -> Unit)

/**
 * The search-bar style top bar (DESIGN §13): drawer button, search field and account avatar. Tapping the
 * field expands [searchBar] (search mode); the results show in the list below, not in a dialog, so that
 * opening a hit animates like any other screen change. In search mode the leading button ends the search.
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
            PrivateTextInput {
                SearchBarDefaults.InputField(
                    textFieldState = query,
                    searchBarState = searchBar,
                    onSearch = { focus.clearFocus() },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.mailbox_search_hint)) },
                    leadingIcon = {
                        if (searching) {
                            IconButton(onClick = onEndSearch) {
                                Icon(Icons.Back, contentDescription = stringResource(R.string.mailbox_close_search))
                            }
                        } else {
                            IconButton(onClick = onMenu) {
                                Icon(Icons.Menu, contentDescription = stringResource(R.string.mailbox_open_menu))
                            }
                        }
                    },
                    trailingIcon = {
                        when {
                            searching && query.text.isNotEmpty() -> IconButton(onClick = { query.clearText() }) {
                                Icon(Icons.Close, contentDescription = stringResource(R.string.mailbox_clear_search))
                            }
                            searching -> Unit
                            else -> Row {
                                if (menu.isNotEmpty()) OverflowMenu(menu)
                                AccountButton(account, onAccount)
                            }
                        }
                    },
                )
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

/** The ⋮ button with [entries]. */
@Composable
internal fun OverflowMenu(entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.More, contentDescription = stringResource(R.string.mailbox_more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        open = false
                        entry.onClick()
                    },
                )
            }
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
            if (actions.archive) ActionButton(RowAction.ARCHIVE, onAction)
            actions.delete?.let { ActionButton(it, onAction, Modifier.rejectObscuredTouches()) }
            ActionButton(actions.read, onAction)
            OverflowMenu(
                buildList {
                    add(MenuEntry(stringResource(R.string.mailbox_action_select_all), onSelectAll))
                    add(MenuEntry(stringResource(actionLabel(actions.star))) { onAction(actions.star) })
                    if (actions.moveAccount != null) add(MenuEntry(stringResource(R.string.mailbox_action_move), onMove))
                    if (actions.labels) add(MenuEntry(stringResource(R.string.mailbox_action_labels), onLabels))
                    actions.spam?.let { add(MenuEntry(stringResource(actionLabel(it))) { onAction(it) }) }
                },
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun ActionButton(action: RowAction, onAction: (RowAction) -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = { onAction(action) }, modifier = modifier) {
        Icon(actionIcon(action), contentDescription = stringResource(actionLabel(action)))
    }
}
