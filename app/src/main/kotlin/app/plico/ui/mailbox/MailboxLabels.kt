package app.plico.ui.mailbox

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.plico.R
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.ui.components.Icons

/** The name of a folder in the app's words: role folders get a translated name, others their path. */
@Composable
internal fun folderName(folder: Folder, role: FolderRole? = folder.role): String =
    roleName(role)?.let { stringResource(it) } ?: MailboxFolders.displayPath(folder)

internal fun roleName(role: FolderRole?): Int? = when (role) {
    FolderRole.INBOX -> R.string.mailbox_folder_inbox
    FolderRole.SENT -> R.string.mailbox_folder_sent
    FolderRole.DRAFTS -> R.string.mailbox_folder_drafts
    FolderRole.ARCHIVE -> R.string.mailbox_folder_archive
    FolderRole.ALL -> R.string.mailbox_folder_all
    FolderRole.IMPORTANT -> R.string.mailbox_folder_important
    FolderRole.JUNK -> R.string.mailbox_folder_spam
    FolderRole.TRASH -> R.string.mailbox_folder_trash
    null -> null
}

internal fun folderIcon(folder: Folder, role: FolderRole? = folder.role): ImageVector = when (role) {
    FolderRole.INBOX -> Icons.Inbox
    FolderRole.SENT -> Icons.Sent
    FolderRole.DRAFTS -> Icons.Draft
    FolderRole.ARCHIVE -> Icons.Archive
    FolderRole.ALL -> Icons.Folder
    FolderRole.IMPORTANT -> Icons.LabelImportant
    FolderRole.JUNK -> Icons.ReportSpam
    FolderRole.TRASH -> Icons.Delete
    null -> if (folder.kind == FolderKind.LABEL) Icons.Label else Icons.Folder
}

internal fun actionLabel(action: RowAction): Int = when (action) {
    RowAction.ARCHIVE -> R.string.mailbox_action_archive
    RowAction.DELETE -> R.string.mailbox_action_delete
    RowAction.DELETE_FOREVER -> R.string.mailbox_action_delete_forever
    RowAction.MARK_READ -> R.string.mailbox_action_mark_read
    RowAction.MARK_UNREAD -> R.string.mailbox_action_mark_unread
    RowAction.STAR -> R.string.mailbox_action_star
    RowAction.UNSTAR -> R.string.mailbox_action_unstar
    RowAction.SPAM -> R.string.mailbox_action_spam
    RowAction.NOT_SPAM -> R.string.mailbox_action_not_spam
}

internal fun actionIcon(action: RowAction): ImageVector = when (action) {
    RowAction.ARCHIVE -> Icons.Archive
    RowAction.DELETE -> Icons.Delete
    RowAction.DELETE_FOREVER -> Icons.DeleteForever
    RowAction.MARK_READ -> Icons.MarkRead
    RowAction.MARK_UNREAD -> Icons.MarkUnread
    RowAction.STAR -> Icons.StarFilled
    RowAction.UNSTAR -> Icons.Star
    RowAction.SPAM -> Icons.ReportSpam
    RowAction.NOT_SPAM -> Icons.NotSpam
}

internal fun ErrorText.text(resources: Resources): String =
    if (detail == null) resources.getString(res) else resources.getString(res, detail)

@Composable
internal fun ErrorText.text(): String = text(LocalResources.current)

/** Material Symbols Rounded "expand_more" (Apache-2.0), for the drawer's More entry. */
internal val ExpandMore: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).addGroup(translationY = 960f)
        .addPath(
            addPathNodes(
                "M480-361q-8 0-15-2.5t-13-8.5L268-556q-11-11-11-28t11-28q11-11 28-11t28 11l156 156 156-156" +
                    "q11-11 28-11t28 11q11 11 11 28t-11 28L508-372q-6 6-13 8.5t-15 2.5Z",
            ),
            fill = SolidColor(Color.Black),
        )
        .clearGroup()
        .build()
}
