package app.plico.ui.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plico.R
import app.plico.data.Folder
import app.plico.data.MailView
import app.plico.ui.components.Icons
import app.plico.ui.components.SectionHeader

/**
 * The drawer (DESIGN §13): All inboxes and Starred, then per account its fixed folders and the others
 * under "More", unread counts on every entry, Settings at the end. A long press on a folder offers
 * "Notify for new mail".
 */
@Composable
internal fun MailDrawer(
    drawerState: DrawerState,
    current: MailView,
    sections: List<DrawerSection>,
    unifiedUnread: Int,
    onOpen: (MailView) -> Unit,
    onNotify: (Folder, Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val currentFolder = (current as? MailView.InFolder)?.folderId
    // Accounts whose "More" the user toggled; the one with the open folder starts expanded.
    var toggled by rememberSaveable { mutableStateOf(listOf<Long>()) }
    ModalDrawerSheet(drawerState) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            item {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
                )
            }
            item {
                DrawerEntry(
                    icon = Icons.Inbox,
                    label = stringResource(R.string.ui_all_inboxes),
                    selected = current == MailView.Unified,
                    badge = unifiedUnread,
                    onClick = { onOpen(MailView.Unified) },
                )
            }
            item {
                DrawerEntry(
                    icon = Icons.Star,
                    label = stringResource(R.string.mailbox_starred),
                    selected = current == MailView.Starred(null),
                    onClick = { onOpen(MailView.Starred(null)) },
                )
            }
            for (section in sections) {
                val accountId = section.account.id
                val gmail = section.account.isGmail
                item(key = "account:$accountId") {
                    Column {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        SectionHeader(section.account.label)
                    }
                }
                items(section.main, key = { "folder:${it.folder.id}" }) { entry ->
                    FolderEntry(entry, gmail, entry.folder.id == currentFolder, onOpen, onNotify)
                }
                if (section.more.isNotEmpty()) {
                    val holdsCurrent = section.more.any { it.folder.id == currentFolder }
                    val open = holdsCurrent != (accountId in toggled)
                    item(key = "more:$accountId") {
                        DrawerEntry(
                            icon = ExpandMore,
                            iconRotation = if (open) 180f else 0f,
                            label = stringResource(if (open) R.string.mailbox_less else R.string.mailbox_more),
                            selected = false,
                            onClick = { toggled = if (accountId in toggled) toggled - accountId else toggled + accountId },
                        )
                    }
                    if (open) {
                        items(section.more, key = { "folder:${it.folder.id}" }) { entry ->
                            FolderEntry(entry, gmail, entry.folder.id == currentFolder, onOpen, onNotify)
                        }
                    }
                }
            }
            item {
                Column {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    DrawerEntry(
                        icon = Icons.Settings,
                        label = stringResource(R.string.ui_settings),
                        selected = false,
                        onClick = onSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderEntry(
    entry: DrawerFolder,
    gmail: Boolean,
    selected: Boolean,
    onOpen: (MailView) -> Unit,
    onNotify: (Folder, Boolean) -> Unit,
) {
    val folder = entry.folder
    var menu by remember { mutableStateOf(false) }
    Box {
        DrawerEntry(
            icon = folderIcon(folder, entry.role),
            label = folderName(folder, entry.role),
            selected = selected,
            badge = entry.unread,
            notify = folder.notify,
            onClick = { onOpen(MailView.InFolder(folder.id)) },
            onLongClick = if (MailboxFolders.canNotify(folder, gmail)) ({ menu = true }) else null,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mailbox_notify_new_mail)) },
                onClick = {
                    menu = false
                    onNotify(folder, !folder.notify)
                },
                leadingIcon = { Icon(Icons.Notifications, contentDescription = null) },
                trailingIcon = { Checkbox(checked = folder.notify, onCheckedChange = null) },
                modifier = Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(folder.notify)
                },
            )
        }
    }
}

/** A drawer row like NavigationDrawerItem, plus a long-press action and an unread count. */
@Composable
private fun DrawerEntry(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int = 0,
    notify: Boolean = false,
    iconRotation: Float = 0f,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    val unread = if (badge > 0) pluralStringResource(R.plurals.mailbox_unread_count, badge, badge) else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .combinedClickable(
                onLongClickLabel = onLongClick?.let { stringResource(R.string.mailbox_folder_options) },
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics {
                this.selected = selected
                unread?.let { stateDescription = it }
            }
            .padding(start = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.rotate(iconRotation))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (notify) {
            Icon(
                imageVector = Icons.Notifications,
                contentDescription = null,
                tint = content,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(16.dp),
            )
        }
        if (badge > 0) {
            Text(
                text = badge.toString(),
                color = content,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}
