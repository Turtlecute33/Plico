package app.plico.ui.mailbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plico.R
import app.plico.app
import app.plico.data.Folder
import app.plico.ui.components.ErrorText
import app.plico.ui.components.Icons
import app.plico.ui.components.PrivateTextInput
import app.plico.ui.components.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What [FolderPicker] does. */
enum class FolderPickerMode {
    /** Pick one target: a Riseup mailbox, or Inbox or a label on Gmail (MailActions.moveTo). */
    MOVE,

    /** Gmail: tick the labels that the messages carry (MailActions.setLabels). */
    LABELS,
}

/**
 * Bottom sheet for "Move to" and the Gmail "Labels" (DESIGN §13, simplicity EM19), used by the mailbox
 * selection and the message screen. It lists the folders of [accountId]. "New…" creates a folder or
 * label on the server (MailActions.createFolder, needs a connection), then moves there or ticks it.
 *
 * @param exclude folders that are not a move target, e.g. the open folder.
 * @param labels LABELS mode: the labels that every message has ([ToggleableState.On]) or some have
 *   ([ToggleableState.Indeterminate]).
 * @param onMove MOVE mode: the chosen folder. The sheet closes itself after the callback.
 * @param onLabels LABELS mode: label folder ids to add and to remove.
 */
@Composable
fun FolderPicker(
    accountId: Long,
    mode: FolderPickerMode,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    exclude: Set<Long> = emptySet(),
    labels: Map<Long, ToggleableState> = emptyMap(),
    onMove: (Folder) -> Unit = {},
    onLabels: (add: Set<Long>, remove: Set<Long>) -> Unit = { _, _ -> },
) {
    val app = LocalContext.current.app
    val account by remember(accountId) { app.accounts.observe(accountId) }.collectAsStateWithLifecycle(null)
    val folders by remember(accountId) { app.folders.observeVisible(accountId) }.collectAsStateWithLifecycle(emptyList())
    val sheet = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    var states by rememberSaveable(labels, stateSaver = LabelStatesSaver) { mutableStateOf(labels) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val gmail = account?.isGmail == true

    fun close() {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun move(folder: Folder) {
        onMove(folder)
        close()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier, sheetState = sheet) {
        Text(
            text = stringResource(if (mode == FolderPickerMode.MOVE) R.string.mailbox_move_to else R.string.mailbox_labels),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        val current = account
        val targets = remember(current, folders, mode, exclude) {
            when {
                current == null -> emptyList()
                mode == FolderPickerMode.MOVE -> MailboxFolders.moveTargets(current, folders, exclude)
                else -> MailboxFolders.labelTargets(current, folders)
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(targets, key = { it.id }) { folder ->
                if (mode == FolderPickerMode.MOVE) {
                    ListItem(
                        onClick = { move(folder) },
                        leadingContent = { Icon(folderIcon(folder), contentDescription = null) },
                    ) { Text(folderName(folder)) }
                } else {
                    val state = states[folder.id] ?: ToggleableState.Off
                    ListItem(
                        modifier = Modifier.triStateToggleable(state = state, role = Role.Checkbox) {
                            states = states + (folder.id to RowActions.nextLabelState(state))
                        },
                        leadingContent = { Icon(folderIcon(folder), contentDescription = null) },
                        trailingContent = { TriStateCheckbox(state = state, onClick = null) },
                    ) { Text(folderName(folder)) }
                }
            }
            if (targets.isEmpty() && current != null && mode == FolderPickerMode.MOVE) {
                item {
                    Text(
                        text = stringResource(R.string.mailbox_no_targets),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
            if (current != null) {
                item {
                    ListItem(
                        onClick = { creating = true },
                        leadingContent = { Icon(Icons.Add, contentDescription = null) },
                    ) { Text(stringResource(if (gmail) R.string.mailbox_new_label else R.string.mailbox_new_folder)) }
                }
            }
        }
        if (mode == FolderPickerMode.LABELS) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                TextButton(onClick = ::close) { Text(stringResource(R.string.ui_cancel)) }
                Button(
                    onClick = {
                        val (add, remove) = RowActions.labelChanges(labels, states)
                        onLabels(add, remove)
                        close()
                    },
                ) { Text(stringResource(R.string.mailbox_apply)) }
            }
        }
    }

    if (creating) {
        NewFolderDialog(
            gmail = gmail,
            create = { name -> app.mail.createFolder(accountId, name) },
            onCreated = { folder ->
                creating = false
                if (mode == FolderPickerMode.MOVE) move(folder) else states = states + (folder.id to ToggleableState.On)
            },
            onDismiss = { creating = false },
        )
    }
}

/** Asks for the name of a new folder or label and creates it on the server. */
@Composable
private fun NewFolderDialog(
    gmail: Boolean,
    create: suspend (String) -> Folder,
    onCreated: (Folder) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = rememberTextFieldState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ErrorText?>(null) }
    val scope = rememberCoroutineScope()
    val valid = name.text.isNotBlank()

    fun submit() {
        if (busy || !valid) return
        busy = true
        error = null
        scope.launch {
            try {
                onCreated(withContext(Dispatchers.IO) { create(name.text.toString().trim()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = actionError(e)
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (gmail) R.string.mailbox_new_label_title else R.string.mailbox_new_folder_title)) },
        text = {
            PrivateTextInput {
                OutlinedTextField(
                    state = name,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.mailbox_folder_name)) },
                    supportingText = error?.let { { Text(it.text()) } },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { submit() },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = valid && !busy) {
                if (busy) {
                    LoadingIndicator(Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.mailbox_create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ui_cancel)) }
        },
    )
}

/** Keeps the label ticks across a configuration change, as ids and ordinals (types that a Bundle holds). */
private val LabelStatesSaver = Saver<Map<Long, ToggleableState>, List<Any>>(
    save = { listOf(it.keys.toLongArray(), it.values.map(ToggleableState::ordinal).toIntArray()) },
    restore = { (ids, ordinals) ->
        (ids as LongArray).zip((ordinals as IntArray).map(ToggleableState.entries::get)).toMap()
    },
)
