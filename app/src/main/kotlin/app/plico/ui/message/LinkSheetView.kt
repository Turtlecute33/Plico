package app.plico.ui.message

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.plico.R
import app.plico.ui.components.Icons

/**
 * The link sheet (DESIGN §11.3): the registrable domain in bold, the full cleaned URL with that domain in bold,
 * the Unicode form of punycode hosts, the warnings, then Open, Copy and Open original.
 */
@Composable
fun LinkSheetView(sheet: LinkSheet, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scheme = MaterialTheme.colorScheme
    val url = remember(sheet, scheme) {
        buildAnnotatedString {
            append(sheet.url)
            sheet.titleRange?.let {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurface), it.first, it.last + 1)
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            if (sheet.title != null) {
                Text(sheet.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                sheet.unicodeTitle?.let {
                    Text(
                        stringResource(R.string.message_link_unicode, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(stringResource(R.string.message_link_unsupported), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                )
            }
            for (warning in sheet.warnings) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Warning, contentDescription = null, tint = scheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(
                        stringResource(warning.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.error,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sheet.openable) {
                    val open: @Composable RowScope.() -> Unit = {
                        Icon(Icons.OpenInNew, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.message_link_open))
                    }
                    // A link with warnings gets no filled button that invites the tap.
                    if (sheet.warnings.isEmpty()) {
                        Button(onClick = { onOpen(sheet.url) }, content = open)
                    } else {
                        OutlinedButton(onClick = { onOpen(sheet.url) }, content = open)
                    }
                }
                OutlinedButton(onClick = { copy(context, sheet.url) }) {
                    Icon(Icons.Copy, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.message_link_copy))
                }
                if (sheet.openable && sheet.original != null) {
                    TextButton(onClick = { onOpen(sheet.original) }) {
                        Text(stringResource(R.string.message_link_open_original))
                    }
                }
            }
        }
    }
}

/**
 * Opens [url] in the browser the user chose (DESIGN §11.3): ACTION_VIEW with CATEGORY_BROWSABLE, no component
 * and no selector. False when no app takes it.
 */
@SuppressLint("UseKtx") // core-ktx is not a dependency (DESIGN §2)
fun openUrl(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Links can hold tokens: marked sensitive, so that the clipboard preview hides them. */
private fun copy(context: Context, text: String) {
    val clip = ClipData.newPlainText(null, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
