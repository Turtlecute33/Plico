package app.plico.ui.setup

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.plico.R
import app.plico.ui.components.Banner
import app.plico.ui.components.BannerKind
import app.plico.ui.components.Icons
import kotlinx.coroutines.launch

private const val MAX_ADDRESS = 254
private const val MAX_SECRET = 1024

/** A scrolling page column, at most 560 dp wide and centred on large screens. */
@Composable
internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
internal fun Headline(title: String, text: String?) {
    Column {
        Text(text = title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (text != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** An outlined card with an icon, a title, a text and [content] below. */
@Composable
internal fun SetupCard(icon: ImageVector, title: String, text: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(icon, title, text)
            content()
        }
    }
}

@Composable
internal fun CardHeader(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie6Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One numbered step of a guide; [extra] goes below its text. */
@Composable
internal fun Step(number: Int, text: String, extra: @Composable () -> Unit = {}) {
    Row {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
            extra()
        }
    }
}

/** Email keyboard without autocorrect; autofill as email address and user name (privacy PS19). */
@Composable
internal fun AddressField(state: TextFieldState, error: InputError?, placeholder: String?) {
    OutlinedTextField(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.EmailAddress + ContentType.Username },
        label = { Text(stringResource(R.string.setup_field_address)) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = error?.let { { Text(inputErrorText(it)) } },
        isError = error != null,
        lineLimits = TextFieldLineLimits.SingleLine,
        inputTransformation = InputTransformation.maxLength(MAX_ADDRESS),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
    )
}

/** Password keyboard without autocorrect, autofill as a password, and a visibility toggle (privacy PS19). */
@Composable
internal fun PasswordField(
    state: TextFieldState,
    label: String,
    error: InputError?,
    onDone: () -> Unit,
    hint: String? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val supporting = error?.let { inputErrorText(it) } ?: hint
    OutlinedSecureTextField(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Password },
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = error != null,
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) SetupIcons.VisibilityOff else SetupIcons.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.setup_hide_password else R.string.setup_show_password,
                    ),
                )
            }
        },
        inputTransformation = InputTransformation.maxLength(MAX_SECRET),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        onKeyboardAction = { onDone() },
    )
}

@Composable
internal fun WideButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) { Text(text) }
}

/** Opens [url] in the browser; TalkBack hears that it opens the browser. */
@Composable
internal fun LinkButton(text: String, url: String) {
    val context = LocalContext.current
    val description = stringResource(R.string.setup_open_in_browser, text)
    TextButton(
        onClick = { openLink(context, url) },
        contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(text)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** A label and a value that the user copies into the Google Cloud console. */
@Composable
internal fun CopyRow(label: String, value: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        val copy = ClipEntry(ClipData.newPlainText(label, value))
        IconButton(onClick = { scope.launch { clipboard.setClipEntry(copy) } }) {
            Icon(Icons.Copy, contentDescription = stringResource(R.string.setup_copy, label))
        }
    }
}

@Composable
internal fun ProblemBanner(state: SetupState) {
    val problem = state.problem ?: return
    Banner(
        text = problemText(problem, expected = state.reauth?.let { it.deviceAccount ?: it.email }),
        kind = BannerKind.ERROR,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun inputErrorText(error: InputError): String = stringResource(
    when (error) {
        InputError.REQUIRED -> R.string.setup_error_required
        InputError.INVALID -> R.string.setup_error_address
        InputError.NOT_RISEUP -> R.string.setup_error_not_riseup
    },
)

/** The text for [problem]; [expected] is the address of the account that signs in again. */
@Composable
private fun problemText(problem: Problem, expected: String?): String {
    val detail = problem.detail
    return when (problem.kind) {
        Problem.Kind.WRONG_PASSWORD -> stringResource(R.string.setup_error_wrong_password)
        Problem.Kind.APP_PASSWORD_REQUIRED -> stringResource(R.string.setup_error_app_password_required)
        Problem.Kind.APP_PASSWORD_REFUSED -> stringResource(R.string.setup_error_app_password_refused)
        Problem.Kind.SIGN_IN_REFUSED -> if (detail != null) {
            stringResource(R.string.setup_error_sign_in_refused, detail)
        } else {
            stringResource(R.string.setup_error_sign_in_refused_plain)
        }
        Problem.Kind.SETUP -> detail ?: stringResource(R.string.setup_error_setup)
        Problem.Kind.NETWORK -> stringResource(R.string.setup_error_network)
        Problem.Kind.TLS -> stringResource(R.string.setup_error_tls)
        Problem.Kind.THROTTLED -> stringResource(R.string.setup_error_throttled)
        Problem.Kind.SERVER -> if (detail != null) {
            stringResource(R.string.setup_error_server, detail)
        } else {
            stringResource(R.string.setup_error_server_plain)
        }
        Problem.Kind.ALREADY_ADDED -> stringResource(R.string.setup_error_already_added)
        Problem.Kind.WRONG_ACCOUNT ->
            stringResource(R.string.setup_error_wrong_account, detail.orEmpty(), expected.orEmpty())
        Problem.Kind.SAVE_FAILED -> stringResource(R.string.setup_error_save_failed)
    }
}
