package app.plico.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.plico.R
import app.plico.account.Providers
import app.plico.app
import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.MailView
import app.plico.data.Provider
import app.plico.data.PushMode
import app.plico.ui.components.AccountColors
import app.plico.ui.components.Avatar
import app.plico.ui.components.Banner
import app.plico.ui.components.BannerKind
import app.plico.ui.components.ConfirmDialog
import app.plico.ui.components.Icons
import app.plico.ui.components.PrivateTextInput
import app.plico.ui.components.formatFileSize
import app.plico.ui.nav.Navigator
import app.plico.ui.nav.Route

/**
 * Settings of one account (DESIGN §13): name, sender, colour, signature, From addresses, offline
 * window, push mode and its cost, notifications, Riseup folders, quota and web settings, Gmail notes,
 * sign in again and remove. Every change is stored at once and reconfigures the account's session.
 */
@Composable
fun AccountSettingsScreen(accountId: Long, nav: Navigator) {
    val app = LocalContext.current.app
    val vm = viewModel { AccountSettingsViewModel(app, accountId) }
    val account by vm.account.collectAsStateWithLifecycle()
    // The removal must not be left half-way from this screen.
    BackHandler(enabled = vm.removing) {}

    val title = account?.label ?: stringResource(R.string.ui_account)
    SettingsScaffold(title = title, onBack = { if (!vm.removing) nav.back() }) {
        val current = account ?: return@SettingsScaffold
        AccountHeader(current, onSignIn = { nav.go(Route.Reauth(accountId)) })
        ProfileGroup(current, vm)
        IdentitiesGroup(current, vm)
        OfflineGroup(current, vm)
        NewMailGroup(current, vm)
        NotificationsGroup(current, vm)
        when (current.provider) {
            Provider.RISEUP -> RiseupGroups(current, vm)
            Provider.GMAIL -> GmailNotesGroup()
        }
        SignInGroup(current, vm, nav)
    }
}

@Composable
private fun AccountHeader(account: Account, onSignIn: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(account.label, colorIndex = account.color, size = 56.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(account.email, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(signInTitle(account.provider, account.auth)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val problem = when (account.state) {
        AccountState.OK -> null
        AccountState.SIGN_IN_NEEDED -> stringResource(R.string.settings_state_sign_in_text)
        AccountState.TLS_PROBLEM -> stringResource(R.string.settings_state_tls_text)
        AccountState.SETUP_PROBLEM -> account.stateText ?: stringResource(R.string.settings_state_setup_text)
    }
    if (problem != null) {
        Banner(
            text = problem,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            kind = BannerKind.ERROR,
            actionLabel = stringResource(R.string.settings_sign_in)
                .takeIf { account.state == AccountState.SIGN_IN_NEEDED },
            onAction = onSignIn,
        )
    }
}

private enum class ProfileDialog { LABEL, NAME, SIGNATURE }

@Composable
private fun ProfileGroup(account: Account, vm: AccountSettingsViewModel) {
    var dialog by rememberSaveable { mutableStateOf<ProfileDialog?>(null) }

    SettingsGroup(stringResource(R.string.settings_profile)) {
        item {
            ClickRow(
                it, stringResource(R.string.settings_label), { dialog = ProfileDialog.LABEL },
                summary = account.label,
            )
        }
        item {
            ClickRow(
                it, stringResource(R.string.settings_sender_name), { dialog = ProfileDialog.NAME },
                summary = account.senderName.ifEmpty { stringResource(R.string.settings_sender_name_none) },
            )
        }
        item { ColorRow(it, account.color) { color -> vm.update { a -> a.copy(color = color) } } }
        item {
            ClickRow(
                it, stringResource(R.string.settings_signature), { dialog = ProfileDialog.SIGNATURE },
                summary = account.signature.lines().take(3).joinToString("\n")
                    .ifEmpty { stringResource(R.string.settings_signature_none) },
            )
        }
    }

    val close = { dialog = null }
    when (dialog) {
        ProfileDialog.LABEL -> {
            val empty = stringResource(R.string.settings_label_empty)
            TextFieldDialog(
                title = stringResource(R.string.settings_label),
                initial = account.label,
                onSave = { text -> cleanLabel(text)?.let { label -> vm.update { it.copy(label = label) } } },
                onDismiss = close,
                message = stringResource(R.string.settings_label_text),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                check = { if (cleanLabel(it) == null) empty else null },
            )
        }
        ProfileDialog.NAME -> TextFieldDialog(
            title = stringResource(R.string.settings_sender_name),
            initial = account.senderName,
            onSave = { text -> vm.update { it.copy(senderName = cleanLine(text, Choices.NAME_MAX)) } },
            onDismiss = close,
            message = stringResource(R.string.settings_sender_name_text),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        ProfileDialog.SIGNATURE -> TextFieldDialog(
            title = stringResource(R.string.settings_signature),
            initial = account.signature,
            onSave = { text -> vm.update { it.copy(signature = cleanSignature(text)) } },
            onDismiss = close,
            message = stringResource(R.string.settings_signature_text),
            singleLine = false,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        null -> Unit
    }
}

/** The account colour (DESIGN §13: a palette of 8), as a group of round swatches. */
@Composable
private fun ColorRow(shapes: ListItemShapes, selected: Int, onSelect: (Int) -> Unit) {
    val names = stringArrayResource(R.array.settings_color_names)
    SegmentedListItem(
        shapes = shapes,
        supportingContent = {
            FlowRow(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(AccountColors.COUNT) { index ->
                    val color = AccountColors.of(index)
                    val isSelected = index == Math.floorMod(selected, AccountColors.COUNT)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(index) }
                            .semantics { contentDescription = names.getOrElse(index) { "${index + 1}" } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(color.color, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = color.onColor,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { Text(stringResource(R.string.settings_color)) }
}

@Composable
private fun IdentitiesGroup(account: Account, vm: AccountSettingsViewModel) {
    val identities by vm.identities.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }

    SettingsGroup(stringResource(R.string.settings_identities)) {
        identities.forEach { identity ->
            item {
                InfoRow(
                    shapes = it,
                    title = identity.email,
                    summary = identity.name.ifEmpty { null },
                    trailing = {
                        val remove = stringResource(R.string.settings_identity_remove, identity.email)
                        IconButton(onClick = { vm.removeIdentity(identity.id) }) {
                            Icon(Icons.Delete, contentDescription = remove)
                        }
                    },
                )
            }
        }
        item { ClickRow(it, stringResource(R.string.settings_identity_add), { adding = true }, icon = Icons.Add) }
        item {
            val note = if (account.isGmail) R.string.settings_identities_gmail else R.string.settings_identities_riseup
            NoteRow(it, stringResource(note))
        }
    }

    if (adding) {
        IdentityDialog(
            account = account,
            existing = identities.map { it.email },
            onAdd = vm::addIdentity,
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun IdentityDialog(
    account: Account,
    existing: List<String>,
    onAdd: (email: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(account.senderName) }
    var tried by remember { mutableStateOf(false) }
    val problem = identityProblem(email, account.email, existing)
    val error = problem?.takeIf { tried }?.let { stringResource(it.text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_identity_add)) },
        text = {
            PrivateTextInput {
                Column {
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            tried = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_identity_address)) },
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email,
                        ),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_identity_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (problem == null) {
                        onAdd(email, name)
                        onDismiss()
                    } else {
                        tried = true
                    }
                },
            ) { Text(stringResource(R.string.settings_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

@Composable
private fun OfflineGroup(account: Account, vm: AccountSettingsViewModel) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.settings_offline)) {
        item {
            ClickRow(
                it, stringResource(R.string.settings_body_days), { choosing = true },
                summary = bodyDaysTitle(account.bodyDays),
            )
        }
    }
    if (choosing) {
        ChoiceDialog(
            title = stringResource(R.string.settings_body_days),
            choices = Choices.BODY_DAYS.map { days ->
                val description = stringResource(R.string.settings_body_all_text).takeIf { days == 0 }
                Choice(days, bodyDaysTitle(days), description)
            },
            selected = account.bodyDays,
            onSelect = { days -> vm.update { it.copy(bodyDays = days) } },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
private fun bodyDaysTitle(days: Int): String = if (days == 0) {
    stringResource(R.string.settings_body_all)
} else {
    pluralStringResource(R.plurals.settings_body_days, days, days)
}

private enum class PushDialog { MODE, INTERVAL }

/** Push mode, poll interval and the measured cost (DESIGN §8.3 "Riseup cost", push PB14). */
@Composable
private fun NewMailGroup(account: Account, vm: AccountSettingsViewModel) {
    var dialog by rememberSaveable { mutableStateOf<PushDialog?>(null) }

    SettingsGroup(stringResource(R.string.settings_new_mail)) {
        item {
            ClickRow(
                it, stringResource(R.string.settings_push_mode), { dialog = PushDialog.MODE },
                summary = stringResource(account.pushMode.title()),
            )
        }
        if (account.pushMode != PushMode.INSTANT) {
            item {
                ClickRow(
                    it, stringResource(R.string.settings_poll_interval), { dialog = PushDialog.INTERVAL },
                    summary = pollTitle(account.pollMinutes),
                )
            }
        }
        item { InfoRow(it, stringResource(R.string.settings_keepalive), summary = keepaliveSummary(account)) }
    }

    when (dialog) {
        PushDialog.MODE -> ChoiceDialog(
            title = stringResource(R.string.settings_push_mode),
            choices = PushMode.entries.map { Choice(it, stringResource(it.title()), stringResource(it.description())) },
            selected = account.pushMode,
            onSelect = { mode -> vm.update { it.copy(pushMode = mode) } },
            onDismiss = { dialog = null },
        )
        PushDialog.INTERVAL -> ChoiceDialog(
            title = stringResource(R.string.settings_poll_interval),
            choices = Choices.POLL_MINUTES.map { Choice(it, pollTitle(it)) },
            selected = account.pollMinutes,
            onSelect = { minutes -> vm.update { it.copy(pollMinutes = minutes) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun pollTitle(minutes: Int): String = pluralStringResource(R.plurals.settings_every_minutes, minutes, minutes)

/** The measured keepalive and the estimated wakes per day for the account's mode. */
@Composable
private fun keepaliveSummary(account: Account): String {
    val seconds = account.serverKeepaliveS?.takeIf { it > 0 }
    val measured = when {
        seconds == null -> stringResource(R.string.settings_keepalive_none)
        else -> roundedMinutes(seconds)?.let { pluralStringResource(R.plurals.settings_keepalive_minutes, it, it) }
            ?: pluralStringResource(R.plurals.settings_keepalive_seconds, seconds, seconds)
    }
    val estimate = wakeEstimate(account.pushMode, seconds, account.pollMinutes)
    val wifi = account.pushMode == PushMode.INSTANT_ON_WIFI
    return listOfNotNull(
        measured,
        estimate.keepaliveWakes?.let {
            pluralStringResource(if (wifi) R.plurals.settings_wakes_wifi else R.plurals.settings_wakes, it, it)
        },
        estimate.polls?.let {
            pluralStringResource(if (wifi) R.plurals.settings_polls_mobile else R.plurals.settings_polls, it, it)
        },
        if (account.pushMode == PushMode.INSTANT && estimate.keepaliveWakes != null) {
            stringResource(R.string.settings_keepalive_hint)
        } else {
            null
        },
    ).joinToString("\n")
}

@Composable
private fun NotificationsGroup(account: Account, vm: AccountSettingsViewModel) {
    val context = LocalContext.current
    SettingsGroup(stringResource(R.string.settings_notifications)) {
        item {
            ClickRow(
                it, stringResource(R.string.settings_account_channel), { SystemPages.mailChannel(context, account.id) },
                summary = stringResource(R.string.settings_mail_channel_text),
                icon = Icons.Notifications,
            )
        }
        item { NoteRow(it, stringResource(R.string.settings_folder_notify_note)) }
    }
    if (account.isGmail) {
        SettingsGroup(stringResource(R.string.settings_categories)) {
            Choices.GMAIL_CATEGORIES.forEach { category ->
                item {
                    val on = category in account.gmailCategories
                    CheckboxRow(
                        shapes = it,
                        title = stringResource(gmailCategoryTitle(category)),
                        checked = on,
                        onCheckedChange = { checked ->
                            vm.update { a ->
                                a.copy(gmailCategories = withCategory(a.gmailCategories, category, checked))
                            }
                        },
                        // At least one category stays on.
                        enabled = !(on && account.gmailCategories.size == 1),
                    )
                }
            }
        }
    }
}

/** Riseup archive folder, quota and the links to account.riseup.net (DESIGN §7.2, riseup R18, R19). */
@Composable
private fun RiseupGroups(account: Account, vm: AccountSettingsViewModel) {
    val context = LocalContext.current
    val folders by vm.archiveFolders.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    val automatic = stringResource(R.string.settings_archive_auto)
    val chosen = folders.firstOrNull { it.id == account.archiveFolder }

    SettingsGroup(stringResource(R.string.settings_folders)) {
        item {
            ClickRow(
                it, stringResource(R.string.settings_archive_folder), { choosing = true },
                summary = chosen?.path ?: automatic,
            )
        }
    }
    SettingsGroup(stringResource(R.string.settings_storage)) {
        item { QuotaRow(it, account) }
    }
    val web = Providers.RISEUP.webSettings
    if (web != null) {
        SettingsGroup(stringResource(R.string.settings_riseup_web)) {
            // Riseup documents no deep links into its account pages, so every row opens the start page.
            listOf(
                R.string.settings_riseup_filters to R.string.settings_riseup_filters_text,
                R.string.settings_riseup_vacation to R.string.settings_riseup_vacation_text,
                R.string.settings_riseup_aliases to R.string.settings_riseup_aliases_text,
                R.string.settings_riseup_spam to R.string.settings_riseup_spam_text,
            ).forEach { (title, summary) ->
                item {
                    ClickRow(
                        it, stringResource(title), { SystemPages.browser(context, web) },
                        summary = stringResource(summary),
                        trailing = { Icon(Icons.OpenInNew, contentDescription = null) },
                    )
                }
            }
        }
    }

    if (choosing) {
        ChoiceDialog(
            title = stringResource(R.string.settings_archive_folder),
            choices = listOf(Choice<Long?>(null, automatic, stringResource(R.string.settings_archive_auto_text))) +
                folders.map { Choice(it.id, it.path) },
            selected = chosen?.id,
            onSelect = { id -> vm.update { it.copy(archiveFolder = id) } },
            onDismiss = { choosing = false },
        )
    }
}

/** Riseup's quota is an estimate that changes only when new mail arrives (riseup R18). Stored in bytes. */
@Composable
private fun QuotaRow(shapes: ListItemShapes, account: Account) {
    val fraction = quotaFraction(account.quotaUsed, account.quotaLimit)
    SegmentedListItem(
        shapes = shapes,
        supportingContent = {
            Column {
                if (fraction == null) {
                    Text(stringResource(R.string.settings_quota_unknown))
                } else {
                    Text(
                        stringResource(
                            R.string.settings_quota_used,
                            formatFileSize(account.quotaUsed!!),
                            formatFileSize(account.quotaLimit!!),
                        ),
                    )
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                    Text(stringResource(R.string.settings_quota_note))
                }
            }
        },
    ) { Text(stringResource(R.string.settings_quota)) }
}

@Composable
private fun GmailNotesGroup() {
    SettingsGroup(stringResource(R.string.settings_gmail_notes)) {
        item {
            InfoRow(
                it, stringResource(R.string.settings_gmail_send_as),
                summary = stringResource(R.string.settings_gmail_send_as_text), icon = Icons.Info,
            )
        }
        item {
            InfoRow(
                it, stringResource(R.string.settings_gmail_ip),
                summary = stringResource(R.string.settings_gmail_ip_text), icon = Icons.Info,
            )
        }
    }
}

@Composable
private fun SignInGroup(account: Account, vm: AccountSettingsViewModel, nav: Navigator) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error

    SettingsGroup(stringResource(R.string.settings_sign_in_group)) {
        item {
            ClickRow(
                it, stringResource(R.string.settings_sign_in_again), { nav.go(Route.Reauth(account.id)) },
                summary = stringResource(R.string.settings_sign_in_again_text),
                icon = Icons.Refresh,
                enabled = !vm.removing,
            )
        }
        item {
            ClickRow(
                it, stringResource(R.string.settings_remove_account), { confirming = true },
                enabled = !vm.removing,
                color = error,
                leading = { Icon(Icons.Delete, contentDescription = null, tint = error) },
                trailing = if (vm.removing) {
                    { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
            )
        }
    }

    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.settings_remove_title, account.label),
            text = stringResource(R.string.settings_remove_text),
            confirmLabel = stringResource(R.string.settings_remove),
            onConfirm = {
                vm.remove { othersLeft ->
                    if (othersLeft) {
                        // The mailbox below may show a folder of the removed account.
                        nav.reset(Route.Mailbox(MailView.Unified))
                        nav.go(Route.Settings)
                    } else {
                        nav.reset(Route.Setup)
                    }
                }
            },
            onDismiss = { confirming = false },
            destructive = true,
        )
    }
}
