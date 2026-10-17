package app.plico.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plico.R
import app.plico.ui.components.Banner
import app.plico.ui.components.BannerKind
import app.plico.ui.components.Icons
import app.plico.ui.components.fileSize

/**
 * The draft's files (DESIGN §13): cards with remove, a progress card for each file being copied, the
 * metadata note (DESIGN §10, privacy PS17) and the size warning.
 */
@Composable
internal fun AttachmentSection(vm: ComposeViewModel, modifier: Modifier = Modifier) {
    val limit = vm.sizeLimit
    if (vm.attachments.isEmpty() && vm.adding.isEmpty() && !vm.tooLarge) return
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.attachments.forEach { a ->
            val busy = a.file in vm.replacing
            AttachmentCard(
                name = a.name,
                icon = if (a.mime.startsWith("image/")) Icons.Image else Icons.Attach,
                detail = fileSize(a.size) +
                    if (a.metadataKept) " · " + stringResource(R.string.compose_attachment_metadata_kept) else "",
                busy = busy,
                onRemove = if (vm.editing && !busy) ({ vm.removeAttachment(a) }) else null,
            ) {
                if (vm.canChooseQuality(a)) {
                    val original = vm.isOriginal(a)
                    FilterChip(
                        selected = original,
                        onClick = { vm.setOriginalQuality(a, !original) },
                        enabled = vm.editing && !busy,
                        label = { Text(stringResource(R.string.compose_attachment_original)) },
                        leadingIcon = if (original) {
                            { Icon(Icons.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        vm.adding.forEach { pending ->
            AttachmentCard(
                name = pending.name ?: stringResource(R.string.compose_attachment_adding),
                icon = Icons.Attach,
                detail = null,
                busy = true,
                onRemove = null,
            )
        }
        if (vm.attachments.isNotEmpty()) {
            Text(
                text = stringResource(R.string.compose_attachment_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (vm.tooLarge && limit != null) {
            Banner(
                text = stringResource(R.string.compose_too_large, fileSize(vm.encodedSize), fileSize(limit)),
                kind = BannerKind.WARN,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AttachmentCard(
    name: String,
    icon: ImageVector,
    detail: String?,
    busy: Boolean,
    onRemove: (() -> Unit)?,
    extra: @Composable () -> Unit = {},
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp))
                } else if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                extra()
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Close, contentDescription = stringResource(R.string.compose_attachment_remove, name))
                }
            }
        }
    }
}
