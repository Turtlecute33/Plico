package app.plico.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.plico.R

/** An entry of a [Menu]; [icon] is shown before the label. */
data class MenuEntry(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/** An icon button that opens [entries]: the ⋮ overflow menu unless [icon] and [description] say otherwise. */
@Composable
fun Menu(
    entries: List<MenuEntry>,
    icon: ImageVector = Icons.More,
    description: String = stringResource(R.string.ui_more_options),
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) { Icon(icon, contentDescription = description) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    leadingIcon = entry.icon?.let { { Icon(it, contentDescription = null) } },
                    onClick = {
                        open = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}
