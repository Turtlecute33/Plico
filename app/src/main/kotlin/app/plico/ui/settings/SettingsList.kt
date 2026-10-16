package app.plico.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plico.R
import app.plico.ui.components.Icons
import app.plico.ui.components.PrivateTextInput
import app.plico.ui.components.SectionHeader

/**
 * The frame of every settings screen: a large flexible top bar with Back that collapses on scroll, and
 * a scrolling column inside the safe drawing area (edge-to-edge).
 */
@Composable
internal fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Back, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            content = content,
        )
    }
}

/** Collects the rows of one [SettingsGroup]; each row gets the segmented shape of its position. */
internal class SettingsGroupScope {
    val rows = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun item(row: @Composable (shapes: ListItemShapes) -> Unit) {
        rows += row
    }
}

/** A titled group of rows drawn as one segmented block (Material 3 expressive lists). */
@Composable
internal fun SettingsGroup(title: String?, content: SettingsGroupScope.() -> Unit) {
    val rows = SettingsGroupScope().apply(content).rows
    if (rows.isEmpty()) return
    if (title != null) SectionHeader(title) else Spacer(Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { index, row -> row(ListItemDefaults.segmentedShapes(index, rows.size)) }
    }
}

/** A row that opens something: a screen, a dialog or a system page. */
@Composable
internal fun ClickRow(
    shapes: ListItemShapes,
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        enabled = enabled,
        leadingContent = leading ?: icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = trailing,
        supportingContent = summary?.let { { Text(it) } },
    ) { Text(title, color = color) }
}

/** An on/off setting. The whole row toggles; TalkBack reads it as a switch. */
@Composable
internal fun SwitchRow(
    shapes: ListItemShapes,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        shapes = shapes,
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        enabled = enabled,
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    ) { Text(title) }
}

/** A row that only shows information. */
@Composable
internal fun InfoRow(
    shapes: ListItemShapes,
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    SegmentedListItem(
        shapes = shapes,
        leadingContent = leading ?: icon?.let { { Icon(it, contentDescription = null) } },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = trailing,
    ) { Text(title) }
}

/** Adds a [SwitchRow] with string resources, for the many on/off settings. */
internal fun SettingsGroupScope.switch(
    @StringRes title: Int,
    @StringRes summary: Int,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) = item { SwitchRow(it, stringResource(title), checked, onCheckedChange, stringResource(summary), enabled) }

/** A check box row, e.g. one Gmail category. */
@Composable
internal fun CheckboxRow(
    shapes: ListItemShapes,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = shapes,
        enabled = enabled,
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
    ) { Text(title) }
}

/** A short explanation inside a group, in the quieter body style. */
@Composable
internal fun NoteRow(shapes: ListItemShapes, text: String) {
    SegmentedListItem(
        shapes = shapes,
        leadingContent = { Icon(Icons.Info, contentDescription = null) },
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One option of a [ChoiceDialog]. */
internal data class Choice<T>(val value: T, val title: String, val description: String? = null)

/** A single choice; a tap selects and closes. */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .selectableGroup()
                    .verticalScroll(rememberScrollState()),
            ) {
                choices.forEach { choice ->
                    val isSelected = choice.value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = isSelected, role = Role.RadioButton) {
                                onSelect(choice.value)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(choice.title, style = MaterialTheme.typography.bodyLarge)
                            if (choice.description != null) {
                                Text(
                                    choice.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

/**
 * Edits one text value. [check] returns an error text for an input that cannot be saved. [message]
 * is shown above the field. The keyboard follows the incognito setting.
 */
@Composable
internal fun TextFieldDialog(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String? = null,
    message: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    check: (String) -> String? = { null },
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var showError by remember { mutableStateOf(false) }
    val error = check(text)
    val focus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                }
                PrivateTextInput {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            showError = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                        label = label?.let { { Text(it) } },
                        isError = showError && error != null,
                        supportingText = if (showError && error != null) ({ Text(error) }) else null,
                        singleLine = singleLine,
                        minLines = if (singleLine) 1 else 4,
                        keyboardOptions = keyboardOptions,
                    )
                }
                // In the dialog's own composition, so that the field is attached when focus is requested.
                LaunchedEffect(focus) { focus.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (error == null) {
                        onSave(text)
                        onDismiss()
                    } else {
                        showError = true
                    }
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}
