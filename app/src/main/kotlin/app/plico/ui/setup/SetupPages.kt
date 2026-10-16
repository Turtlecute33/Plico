package app.plico.ui.setup

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.plico.R
import app.plico.account.Providers
import app.plico.data.AuthKind
import app.plico.data.Provider
import app.plico.ui.components.Avatar
import app.plico.ui.components.Banner
import app.plico.ui.components.BannerKind
import app.plico.ui.components.Icons

private const val MAX_LABEL = 40
private const val MAX_NAME = 100
private const val MAX_CLIENT_ID = 200
private const val CLOUD_CONSOLE_URL = "https://console.cloud.google.com/apis/credentials"

/** The one-line privacy promise and the two providers. */
@Composable
internal fun WelcomePage(onChoose: (Provider) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp),
                    )
                }
                Spacer(Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.ui_welcome),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.setup_welcome_promise),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        }
        Column(Modifier.widthIn(max = 420.dp)) {
            Button(
                onClick = { onChoose(Provider.RISEUP) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.setup_add_riseup)) }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { onChoose(Provider.GMAIL) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.setup_add_gmail)) }
        }
    }
}

/** Riseup address and password; when signing in again, the password only. */
@Composable
internal fun RiseupPage(vm: SetupViewModel, state: SetupState) {
    val reauth = state.reauth
    PageColumn {
        if (reauth == null) {
            Headline(stringResource(R.string.setup_riseup_title), stringResource(R.string.setup_riseup_text))
        } else {
            Headline(
                stringResource(R.string.setup_reauth_title),
                stringResource(R.string.setup_reauth_password, reauth.email),
            )
        }
        ProblemBanner(state)
        if (reauth == null) {
            AddressField(vm.email, state.emailError, stringResource(R.string.setup_riseup_placeholder))
        }
        PasswordField(
            state = vm.password,
            label = stringResource(R.string.setup_field_password),
            error = state.passwordError,
            onDone = vm::signInWithPassword,
        )
        WideButton(stringResource(R.string.setup_sign_in), onClick = vm::signInWithPassword)
    }
}

/** The Gmail method cards (DESIGN §13); [onChooseAccount] opens the system account chooser. */
@Composable
internal fun GmailPage(vm: SetupViewModel, state: SetupState, onChooseAccount: () -> Unit) {
    val activity = LocalActivity.current
    val reauth = state.reauth
    PageColumn {
        if (reauth == null) {
            Headline(stringResource(R.string.setup_gmail_title), stringResource(R.string.setup_gmail_text))
        } else {
            val text = when (reauth.auth) {
                AuthKind.PASSWORD -> R.string.setup_reauth_app_password
                AuthKind.OAUTH -> R.string.setup_reauth_browser
                AuthKind.DEVICE -> R.string.setup_reauth_device
            }
            Headline(stringResource(R.string.setup_reauth_title), stringResource(text, reauth.email))
        }
        ProblemBanner(state)
        for (method in state.methods) {
            when (method) {
                Method.DEVICE -> SetupCard(
                    icon = SetupIcons.Smartphone,
                    title = stringResource(R.string.setup_method_device),
                    text = stringResource(R.string.setup_method_device_text),
                ) {
                    FilledTonalButton(onClick = onChooseAccount, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.setup_method_device_action))
                    }
                }
                Method.BROWSER -> SetupCard(
                    icon = SetupIcons.Browser,
                    title = stringResource(R.string.setup_method_browser),
                    text = stringResource(R.string.setup_method_browser_text),
                ) {
                    FilledTonalButton(
                        onClick = { activity?.let(vm::signInWithBrowser) },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.setup_method_browser_action)) }
                }
                Method.PASSWORD -> AppPasswordCard(vm, state)
            }
        }
        if (state.methods.isEmpty()) {
            Text(text = stringResource(R.string.setup_reauth_no_client), style = MaterialTheme.typography.bodyLarge)
        }
        if (state.offersOwnClient) {
            OutlinedCard(onClick = vm::openOwnClient, modifier = Modifier.fillMaxWidth()) {
                CardHeader(
                    icon = Icons.Settings,
                    title = stringResource(R.string.setup_own_client),
                    text = stringResource(R.string.setup_own_client_text),
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

/** The steps to an app password, the link to Google's page and the fields. */
@Composable
private fun AppPasswordCard(vm: SetupViewModel, state: SetupState) {
    SetupCard(
        icon = SetupIcons.Key,
        title = stringResource(R.string.setup_method_password),
        text = stringResource(R.string.setup_method_password_text),
    ) {
        Step(1, stringResource(R.string.setup_app_password_step1))
        Step(2, stringResource(R.string.setup_app_password_step2)) {
            LinkButton(stringResource(R.string.setup_app_password_link), Providers.APP_PASSWORDS_URL)
        }
        Step(3, stringResource(R.string.setup_app_password_step3))
        if (state.reauth == null) {
            AddressField(vm.email, state.emailError, placeholder = null)
        }
        PasswordField(
            state = vm.password,
            label = stringResource(R.string.setup_field_app_password),
            error = state.passwordError,
            hint = stringResource(R.string.setup_app_password_hint),
            onDone = vm::signInWithPassword,
        )
        WideButton(stringResource(R.string.setup_sign_in), onClick = vm::signInWithPassword)
    }
}

/** The guide for the user's own Google OAuth client (DESIGN §6) and the field for Prefs.googleClientId. */
@Composable
internal fun OwnClientPage(vm: SetupViewModel, state: SetupState) {
    val context = LocalContext.current
    val sha1 = remember { signingSha1(context) }
    PageColumn {
        Headline(stringResource(R.string.setup_client_title), stringResource(R.string.setup_client_text))
        Step(1, stringResource(R.string.setup_client_step1)) {
            LinkButton(stringResource(R.string.setup_client_console), CLOUD_CONSOLE_URL)
        }
        Step(2, stringResource(R.string.setup_client_step2))
        Step(3, stringResource(R.string.setup_client_step3)) {
            CopyRow(stringResource(R.string.setup_client_package), context.packageName)
            if (sha1 != null) CopyRow(stringResource(R.string.setup_client_sha1), sha1)
        }
        Step(4, stringResource(R.string.setup_client_step4))
        Text(
            text = stringResource(R.string.setup_client_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val supporting = if (state.clientIdError) R.string.setup_error_client_id else R.string.setup_client_id_hint
        OutlinedTextField(
            state = vm.clientId,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.setup_field_client_id)) },
            supportingText = { Text(stringResource(supporting)) },
            isError = state.clientIdError,
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation.maxLength(MAX_CLIENT_ID),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            onKeyboardAction = { vm.saveClientId() },
        )
        WideButton(stringResource(R.string.setup_client_save), onClick = { vm.saveClientId() })
    }
}

/** Label, sender name and offline window of the tested account, then "Add account". */
@Composable
internal fun OptionsPage(vm: SetupViewModel, state: SetupState) {
    val account = state.signedIn ?: return
    val saving = state.busy == Busy.SAVING
    PageColumn {
        Headline(stringResource(R.string.setup_options_title), null)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = account.email)
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.setup_signed_in_as, account.email),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        ProblemBanner(state)
        for (note in account.notes) {
            Banner(text = note, kind = BannerKind.WARN, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(
            state = vm.label,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving,
            label = { Text(stringResource(R.string.setup_field_label)) },
            supportingText = { Text(stringResource(R.string.setup_label_hint)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation.maxLength(MAX_LABEL),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
        )
        OutlinedTextField(
            state = vm.senderName,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.PersonFullName },
            enabled = !saving,
            label = { Text(stringResource(R.string.setup_field_sender_name)) },
            supportingText = { Text(stringResource(R.string.setup_sender_name_hint)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation.maxLength(MAX_NAME),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
        )
        OfflineSwitch(on = state.allOffline, enabled = !saving, onChange = vm::setAllOffline)
        if (state.showIpNote) {
            Banner(text = stringResource(R.string.setup_gmail_ip_note), modifier = Modifier.fillMaxWidth())
        }
        Button(
            onClick = vm::addAccount,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(stringResource(R.string.setup_add_account))
        }
    }
}

/** "Keep all mail offline" with its storage note (body_days 0, else 30). The whole row toggles. */
@Composable
private fun OfflineSwitch(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.setup_offline), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(if (on) R.string.setup_offline_on else R.string.setup_offline_off),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = on, onCheckedChange = null, enabled = enabled)
    }
}
