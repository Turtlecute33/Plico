package app.plico.ui.message

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.plico.R
import app.plico.data.Attachment
import app.plico.ui.components.Icons
import app.plico.ui.components.formatFileSize
import java.io.File

/**
 * One attachment (DESIGN §11.5): icon, name, size and type; a tap opens it, Save stores a copy. [progress] is
 * the download progress (0..1) while it loads, else null.
 */
@Composable
fun AttachmentCard(
    attachment: Attachment,
    progress: Float?,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = revealBidi(attachment.name).ifBlank { stringResource(R.string.message_attachment) }
    val size = formatFileSize(decodedSize(attachment.size, attachment.encoding))
    val meta = listOf(size, typeLabel(attachment.name, attachment.mime)).filter { it.isNotEmpty() }.joinToString(" · ")
    val image = attachment.mime.startsWith("image/", ignoreCase = true)
    OutlinedCard(onClick = onOpen, enabled = progress == null, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (image) Icons.Image else Icons.Attach,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSave, enabled = progress == null) { Text(stringResource(R.string.message_save)) }
        }
        if (progress != null) {
            if (progress > 0f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Opens a downloaded attachment with the type from [app.plico.html.AttachmentSafety.check] (DESIGN §11.5): a
 * FileProvider URI of the attachment cache with a read grant only. False when no app takes it.
 */
fun openFile(context: Context, file: File, mimeType: String): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** ACTION_CREATE_DOCUMENT for Save; [name] is already a safe file name. */
fun createDocument(name: String, mimeType: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE)
    .setType(mimeType)
    .putExtra(Intent.EXTRA_TITLE, name)
