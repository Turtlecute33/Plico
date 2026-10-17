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
import androidx.compose.material3.TextButton
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
import app.plico.account.OAuthIssuer
import app.plico.account.ProviderTable
import app.plico.account.Providers
import app.plico.data.AuthKind
import app.plico.mail.net.TlsMode
import app.plico.ui.components.Avatar
import app.plico.ui.components.Banner
import app.plico.ui.components.BannerKind
import app.plico.ui.components.Icons
import app.plico.ui.settings.Choices

private const val MAX_CLIENT_ID = 200
private const val CLOUD_CONSOLE_URL = "https://console.cloud.google.com/apis/credentials"
private const val ENTRA_URL = "https://entra.microsoft.com/"

/** The one-line privacy promise, one address field and Next. No provider is named before detection. */
@Composable
internal fun WelcomePage(vm: SetupViewModel, state: SetupState) {
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
            ProblemBanner(state, expected = null, onUseSuggestion = vm::useSuggestion)
            if (state.problem != null) Spacer(Modifier.height(12.dp))
            AddressField(
                vm.email,
                state.emailError,
                stringResource(R.string.setup_address_placeholder),
                onDone = vm::next,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = vm::next,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.setup_next)) }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = vm::manual,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.setup_manual)) }
        }
    }
}

/** Incoming and outgoing server, port and security, and the user name (DESIGN §13). Hosts in ASCII form. */
@Composable
internal fun ServersPage(vm: SetupViewModel, state: SetupState) {
    val errors = state.serverErrors
    PageColumn {
        Headline(stringResource(R.string.setup_servers_title), stringResource(serversText(state.source)))
        ProblemBanner(state, expected = expectedAddress(vm, state))
        NoteBanners(state.notes, SERVER_NOTES)
        ServerGroup(
            title = stringResource(R.string.setup_incoming),
            host = { modifier ->
                ServerField(vm.imapHost, stringResource(R.string.setup_field_host), InputKind.HOST, errors?.imapHost, modifier)
            },
            port = { modifier ->
                ServerField(vm.imapPort, stringResource(R.string.setup_field_port), InputKind.PORT, errors?.imapPort, modifier)
            },
            tls = vm.imapTls,
            onTls = vm::chooseImapTls,
        )
        ServerGroup(
            title = stringResource(R.string.setup_outgoing),
            host = { modifier ->
                ServerField(vm.smtpHost, stringResource(R.string.setup_field_host), InputKind.HOST, errors?.smtpHost, modifier)
            },
            port = { modifier ->
                ServerField(vm.smtpPort, stringResource(R.string.setup_field_port), InputKind.PORT, errors?.smtpPort, modifier)
            },
            tls = vm.smtpTls,
            onTls = vm::chooseSmtpTls,
        )
        ServerField(
            vm.user,
            stringResource(R.string.setup_field_user),
            InputKind.ADDRESS,
            error = null,
            modifier = Modifier.fillMaxWidth(),
            hint = stringResource(R.string.setup_user_hint),
            imeAction = ImeAction.Done,
        )
        Text(
            text = stringResource(R.string.setup_servers_tls_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WideButton(stringResource(R.string.setup_next), onClick = vm::serversNext)
    }
}

/** One server of the server settings page: a title, host and port in a row, and the security. */
@Composable
private fun ServerGroup(
    title: String,
    host: @Composable (Modifier) -> Unit,
    port: @Composable (Modifier) -> Unit,
    tls: TlsMode,
    onTls: (TlsMode) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .semantics { heading() },
        )
        Row(verticalAlignment = Alignment.Top) {
            host(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            port(Modifier.width(112.dp))
        }
        Spacer(Modifier.height(8.dp))
        SecurityChoice(tls, onTls)
    }
}

/**
 * The address, the servers that get the password (ASCII form, so that a lookalike name shows), table notes and
 * the password field (DESIGN §13). Riseup and other servers; Google and Microsoft have their own pages.
 */
@Composable
internal fun PasswordPage(vm: SetupViewModel, state: SetupState) {
    val reauth = state.reauth
    val servers = state.servers ?: return
    PageColumn {
        if (reauth == null) {
            Headline(stringResource(R.string.setup_password_title), null)
        } else {
            Headline(stringResource(R.string.setup_reauth_title), stringResource(R.string.setup_reauth_password, reauth.email))
        }
        val address = reauth?.email ?: vm.email.text.toString()
        Text(
            text = stringResource(R.string.setup_password_account, address),
            style = MaterialTheme.typography.titleMedium,
            // Password managers pair the password with this user name.
            modifier = Modifier.semantics { contentType = ContentType.Username },
        )
        val imap = servers.imap.host
        val smtp = servers.smtp.host
        Text(
            text = if (imap == smtp) {
                stringResource(R.string.setup_password_text_one, imap)
            } else {
                stringResource(R.string.setup_password_text, imap, smtp)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProblemBanner(state, expected = expectedAddress(vm, state))
        NoteBanners(state.notes, PASSWORD_NOTES)
        PasswordField(
            state = vm.password,
            label = stringResource(R.string.setup_field_password),
            error = state.passwordError,
            onDone = vm::signInWithPassword,
        )
        WideButton(stringResource(R.string.ui_sign_in), onClick = vm::signInWithPassword)
        if (state.offersServerChange) {
            TextButton(onClick = vm::changeServers, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.setup_change_servers))
            }
        }
    }
}

/** The Google method cards (DESIGN §13); [onChooseAccount] opens the system account chooser. */
@Composable
internal fun GooglePage(vm: SetupViewModel, state: SetupState, onChooseAccount: () -> Unit) {
    val activity = LocalActivity.current
    val reauth = state.reauth
    PageColumn {
        if (reauth == null) {
            Headline(stringResource(R.string.setup_gmail_title), stringResource(R.string.setup_gmail_text))
        } else {
            val text = when (reauth.auth) {
                AuthKind.PASSWORD -> stringResource(R.string.setup_reauth_app_password, reauth.email)
                AuthKind.OAUTH -> stringResource(R.string.setup_reauth_browser, OAuthIssuer.GOOGLE.brand, reauth.email)
                AuthKind.DEVICE -> stringResource(R.string.setup_reauth_device, reauth.email)
            }
            Headline(stringResource(R.string.setup_reauth_title), text)
        }
        ProblemBanner(state, expected = expectedAddress(vm, state))
        for (method in state.methods) {
            when (method) {
                Method.DEVICE -> SetupCard(
                    icon = Icons.Smartphone,
                    title = stringResource(R.string.setup_method_device),
                    text = stringResource(R.string.setup_method_device_text),
                ) {
                    FilledTonalButton(onClick = onChooseAccount, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.setup_method_device_action))
                    }
                }
                Method.BROWSER -> SetupCard(
                    icon = Icons.Browser,
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
            Text(
                text = stringResource(R.string.setup_reauth_no_client, OAuthIssuer.GOOGLE.brand),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (state.offersOwnClient) {
            OwnClientEntry(
                title = stringResource(R.string.setup_own_client),
                text = stringResource(R.string.setup_own_client_text),
                onClick = { vm.openOwnClient(OAuthIssuer.GOOGLE) },
            )
        }
    }
}

/** The steps to an app password, the link to Google's page and the field. The address was typed on Welcome. */
@Composable
private fun AppPasswordCard(vm: SetupViewModel, state: SetupState) {
    SetupCard(
        icon = Icons.Key,
        title = stringResource(R.string.setup_method_password),
        text = stringResource(R.string.setup_method_password_text),
    ) {
        Step(1, stringResource(R.string.setup_app_password_step1))
        Step(2, stringResource(R.string.setup_app_password_step2)) {
            LinkButton(stringResource(R.string.setup_app_password_link), Providers.APP_PASSWORDS_URL)
        }
        Step(3, stringResource(R.string.setup_app_password_step3))
        PasswordField(
            state = vm.password,
            label = stringResource(R.string.setup_field_app_password),
            error = state.passwordError,
            hint = stringResource(R.string.setup_app_password_hint),
            onDone = vm::signInWithPassword,
        )
        WideButton(stringResource(R.string.ui_sign_in), onClick = vm::signInWithPassword)
    }
}

/** Outlook.com and Microsoft 365: browser sign-in with the user's own client ID (DESIGN §6). */
@Composable
internal fun MicrosoftPage(vm: SetupViewModel, state: SetupState) {
    val activity = LocalActivity.current
    val reauth = state.reauth
    val brand = OAuthIssuer.MICROSOFT.brand
    PageColumn {
        if (reauth == null) {
            Headline(stringResource(R.string.setup_microsoft_title), stringResource(R.string.setup_microsoft_text))
        } else {
            Headline(stringResource(R.string.setup_reauth_title), stringResource(R.string.setup_reauth_browser, brand, reauth.email))
        }
        ProblemBanner(state, expected = expectedAddress(vm, state))
        if (ProviderTable.Note.ENABLE_IMAP in state.notes) {
            Banner(text = stringResource(R.string.setup_microsoft_imap_note), modifier = Modifier.fillMaxWidth())
        }
        if (Method.BROWSER in state.methods) {
            SetupCard(
                icon = Icons.Browser,
                title = stringResource(R.string.setup_method_browser),
                text = stringResource(R.string.setup_method_browser_microsoft_text),
            ) {
                FilledTonalButton(
                    onClick = { activity?.let(vm::signInWithBrowser) },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.setup_method_browser_action)) }
            }
        } else {
            Text(
                text = if (reauth == null) {
                    stringResource(R.string.setup_microsoft_no_client)
                } else {
                    stringResource(R.string.setup_reauth_no_client, brand)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (state.offersOwnClient) {
            OwnClientEntry(
                title = stringResource(R.string.setup_own_client_microsoft),
                text = stringResource(R.string.setup_own_client_microsoft_text),
                onClick = { vm.openOwnClient(OAuthIssuer.MICROSOFT) },
            )
        }
    }
}

@Composable
private fun OwnClientEntry(title: String, text: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        CardHeader(icon = Icons.Settings, title = title, text = text, modifier = Modifier.padding(20.dp))
    }
}

/** The guide for the user's own OAuth client of the page's issuer (DESIGN §6) and the field for its client ID. */
@Composable
internal fun OwnClientPage(vm: SetupViewModel, state: SetupState) {
    PageColumn {
        when (state.ownClient) {
            OAuthIssuer.GOOGLE -> GoogleClientGuide()
            OAuthIssuer.MICROSOFT -> MicrosoftClientGuide()
        }
        val microsoft = state.ownClient == OAuthIssuer.MICROSOFT
        val supporting = when {
            state.clientIdError && microsoft -> R.string.setup_error_ms_client_id
            state.clientIdError -> R.string.setup_error_client_id
            microsoft -> R.string.setup_ms_client_id_hint
            else -> R.string.setup_client_id_hint
        }
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
        WideButton(stringResource(R.string.ui_save), onClick = { vm.saveClientId() })
    }
}

@Composable
private fun GoogleClientGuide() {
    val context = LocalContext.current
    val sha1 = remember { signingSha1(context) }
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
}

@Composable
private fun MicrosoftClientGuide() {
    val context = LocalContext.current
    Headline(stringResource(R.string.setup_ms_client_title), stringResource(R.string.setup_ms_client_text))
    Step(1, stringResource(R.string.setup_ms_client_step1)) {
        LinkButton(stringResource(R.string.setup_ms_client_portal), ENTRA_URL)
    }
    Step(2, stringResource(R.string.setup_ms_client_step2))
    Step(3, stringResource(R.string.setup_ms_client_step3)) {
        CopyRow(stringResource(R.string.setup_ms_client_redirect), OAuthIssuer.MICROSOFT.redirectUri(context.packageName))
    }
    Step(4, stringResource(R.string.setup_ms_client_step4))
    Text(
        text = stringResource(R.string.setup_ms_client_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
        ProblemBanner(state, expected = expectedAddress(vm, state))
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
            inputTransformation = InputTransformation.maxLength(Choices.LABEL_MAX),
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
            inputTransformation = InputTransformation.maxLength(Choices.NAME_MAX),
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

/** The [notes] that are in [shown], in that order. */
@Composable
private fun NoteBanners(notes: Set<ProviderTable.Note>, shown: List<ProviderTable.Note>) {
    for (note in shown.filter { it in notes }) {
        Banner(text = stringResource(noteText(note)), modifier = Modifier.fillMaxWidth())
    }
}

/** The address that should sign in: the account that signs in again, else the typed address. */
private fun expectedAddress(vm: SetupViewModel, state: SetupState): String =
    state.reauth?.let { it.deviceAccount ?: it.email } ?: SetupInput.normalizeEmail(vm.email.text.toString())
