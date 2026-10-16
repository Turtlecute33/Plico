package app.plico.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class BannerKind { INFO, WARN, ERROR }

/**
 * A full-width notice inside a screen, e.g. "Sign-in needed", "Push paused", the mass-delete guard or
 * "Images blocked". [actionLabel] with [onAction] adds one text button at the end.
 */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    kind: BannerKind = BannerKind.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        BannerKind.INFO -> scheme.secondaryContainer to scheme.onSecondaryContainer
        BannerKind.WARN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        BannerKind.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (kind == BannerKind.INFO) Icons.Info else Icons.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                    Text(actionLabel)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}
