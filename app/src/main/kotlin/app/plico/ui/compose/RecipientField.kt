package app.plico.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plico.R
import app.plico.data.Contact
import app.plico.ui.components.Avatar
import app.plico.ui.components.Icons

/** Chips and the text being typed of one recipient field. Changes go through [ComposeViewModel]. */
@Stable
internal class RecipientField {
    val chips = mutableStateListOf<Recipient>()
    var text by mutableStateOf("")
        private set

    /** The chips plus what the text would become. */
    fun all(): List<Recipient> = chips + Recipients.newOnes(chips, Recipients.parse(text))

    fun set(list: List<Recipient>) {
        chips.clear()
        chips += list
        text = ""
    }

    /** Finished entries of [value] become chips. True when anything changed. */
    fun type(value: String): Boolean {
        if (value == text) return false
        val (done, pending) = Recipients.takeFinished(value)
        chips += Recipients.newOnes(chips, done)
        text = pending
        return true
    }

    fun commit() {
        chips += Recipients.newOnes(chips, Recipients.parse(text))
        text = ""
    }

    fun pick(recipient: Recipient) {
        chips += Recipients.newOnes(chips, listOf(recipient))
        text = ""
    }

    fun remove(recipient: Recipient) {
        chips.remove(recipient)
    }

    fun edit(recipient: Recipient) {
        commit()
        chips.remove(recipient)
        text = recipient.text
    }

    /** Backspace in the empty text removes the last chip. */
    fun removeLast(): Boolean {
        if (text.isNotEmpty() || chips.isEmpty()) return false
        chips.removeAt(chips.lastIndex)
        return true
    }
}

/**
 * A To, Cc or Bcc row: label, chips, the text field, and local contact suggestions below it
 * (DESIGN §13; contacts are only those learned on this phone, DESIGN §5).
 */
@Composable
internal fun RecipientRow(
    label: String,
    field: RecipientField,
    vm: ComposeViewModel,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    trailing: @Composable () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val enabled = vm.editing
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            FieldLabel(label, decorative = true)
            FlowRow(
                // Chips take 48 dp with their touch area; 4 dp above and below make the 56 dp row of the label.
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                field.chips.forEach { recipient ->
                    RecipientChip(
                        recipient = recipient,
                        enabled = enabled,
                        onEdit = {
                            vm.edit(field, recipient)
                            focusRequester.requestFocus()
                        },
                        onRemove = { vm.remove(field, recipient) },
                    )
                }
                val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                BasicTextField(
                    value = field.text,
                    onValueChange = { vm.type(field, it) },
                    readOnly = !enabled,
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = {
                            if (field.text.isBlank()) defaultKeyboardAction(ImeAction.Next) else vm.commit(field)
                        },
                    ),
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (focused && !it.isFocused) vm.commit(field)
                            focused = it.isFocused
                        }
                        .onPreviewKeyEvent {
                            val backspace = it.type == KeyEventType.KeyDown && it.key == Key.Backspace
                            if (backspace && field.text.isEmpty()) vm.removeLast(field)
                            backspace && field.text.isEmpty()
                        }
                        .semantics { contentDescription = label },
                    decorationBox = { inner ->
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) { inner() }
                    },
                )
            }
            trailing()
        }
        val suggestions = vm.suggestions
        if (focused && suggestions.field === field && suggestions.contacts.isNotEmpty()) {
            SuggestionList(suggestions.contacts, onPick = { vm.pick(field, it) })
        }
    }
}

@Composable
private fun RecipientChip(recipient: Recipient, enabled: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val invalid = recipient is Recipient.Invalid
    val scheme = MaterialTheme.colorScheme
    Box {
        InputChip(
            selected = false,
            onClick = { menu = true },
            enabled = enabled,
            label = { Text(recipient.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.widthIn(max = 280.dp),
            leadingIcon = if (invalid) {
                {
                    Icon(
                        imageVector = Icons.Warning,
                        contentDescription = stringResource(R.string.compose_recipient_invalid),
                        modifier = Modifier.size(InputChipDefaults.IconSize),
                    )
                }
            } else {
                null
            },
            colors = if (invalid) {
                InputChipDefaults.inputChipColors(
                    containerColor = scheme.errorContainer,
                    labelColor = scheme.onErrorContainer,
                    leadingIconColor = scheme.onErrorContainer,
                )
            } else {
                InputChipDefaults.inputChipColors()
            },
            border = InputChipDefaults.inputChipBorder(
                enabled = enabled,
                selected = false,
                borderColor = if (invalid) scheme.error else scheme.outlineVariant,
            ),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // The full address, so that a display name cannot hide where the mail goes.
            val address = (recipient as? Recipient.Valid)?.address
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 280.dp)) {
                address?.name?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(
                    text = address?.email ?: stringResource(R.string.compose_recipient_invalid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (address == null) scheme.error else scheme.onSurfaceVariant,
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.compose_recipient_edit)) },
                leadingIcon = { Icon(Icons.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.compose_recipient_remove)) },
                leadingIcon = { Icon(Icons.Close, contentDescription = null) },
                onClick = {
                    menu = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
private fun SuggestionList(contacts: List<Contact>, onPick: (Contact) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 64.dp, end = 8.dp, bottom = 8.dp)) {
        contacts.forEach { contact ->
            val name = contact.name.ifBlank { null }
            ListItem(
                headlineContent = { Text(name ?: contact.email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = name?.let {
                    { Text(contact.email, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                leadingContent = { Avatar(name ?: contact.email, size = 32.dp) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onPick(contact) },
            )
        }
    }
}
