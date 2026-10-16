package app.plico.ui.settings

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plico.R
import app.plico.app
import app.plico.data.Account
import app.plico.data.AuthKind
import app.plico.data.Prefs
import app.plico.ui.components.Avatar
import app.plico.ui.components.Icons
import app.plico.ui.components.rememberPref
import app.plico.ui.nav.Navigator
import app.plico.ui.nav.Route

/**
 * App settings (DESIGN §13): accounts, Gmail sign-in, notifications, privacy and security, mail,
 * push and battery, about. Every change applies at once.
 */
@Composable
fun SettingsScreen(nav: Navigator) {
    val app = LocalContext.current.app
    val accounts by remember(app) { app.accounts.observeAll() }.collectAsStateWithLifecycle(null)
    SettingsScaffold(title = stringResource(R.string.ui_settings), onBack = nav::back) {
        val list = accounts ?: return@SettingsScaffold
        AccountsGroup(list, nav)
        GmailSignInGroup(usesGoogleClient = list.any { it.auth != AuthKind.PASSWORD })
        NotificationsGroup(list)
        PrivacyGroup()
        MailGroup()
        PushGroup(hasAccounts = list.isNotEmpty())
        SettingsGroup(title = null) {
            item { ClickRow(it, stringResource(R.string.ui_about), { nav.go(Route.About) }, icon = Icons.Info) }
        }
    }
}

@Composable
private fun AccountsGroup(accounts: List<Account>, nav: Navigator) {
    SettingsGroup(stringResource(R.string.settings_accounts)) {
        accounts.forEach { account ->
            item {
                val problem = account.state.problemTitle()?.let { res -> stringResource(res) }
                ClickRow(
                    shapes = it,
                    title = account.label,
                    summary = listOfNotNull(account.email, problem).joinToString("\n"),
                    leading = { Avatar(account.label, colorIndex = account.color) },
                    onClick = { nav.go(Route.AccountSettings(account.id)) },
                )
            }
        }
        item { ClickRow(it, stringResource(R.string.settings_add_account), { nav.go(Route.Setup) }, icon = Icons.Add) }
    }
}

/** The user's own Google OAuth client ID, which turns on both Google sign-in methods (DESIGN §6). */
@Composable
private fun GmailSignInGroup(usesGoogleClient: Boolean) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val clientId by rememberPref("") { googleClientId }
    var editing by rememberSaveable { mutableStateOf(false) }

    SettingsGroup(stringResource(R.string.settings_gmail_sign_in)) {
        item {
            ClickRow(
                shapes = it,
                title = stringResource(R.string.settings_client_id),
                summary = clientId.ifEmpty { stringResource(R.string.settings_client_id_none) },
                icon = Icons.Account,
                onClick = { editing = true },
            )
        }
    }

    if (editing) {
        val invalid = stringResource(R.string.settings_client_id_invalid)
        val guide = stringResource(R.string.settings_client_id_guide, context.packageName)
        val inUse = stringResource(R.string.settings_client_id_in_use)
        TextFieldDialog(
            title = stringResource(R.string.settings_client_id),
            initial = clientId,
            onSave = { prefs.googleClientId = ClientIds.normalize(it).orEmpty() },
            onDismiss = { editing = false },
            label = stringResource(R.string.settings_client_id_label),
            message = if (usesGoogleClient) "$guide\n\n$inUse" else guide,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            // An empty field is valid: it removes the client ID.
            check = { if (ClientIds.normalize(it) == null) invalid else null },
        )
    }
}

@Composable
private fun NotificationsGroup(accounts: List<Account>) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val hide by rememberPref(true) { hideNotificationContent }
    val lock by rememberPref(false) { appLock }

    SettingsGroup(stringResource(R.string.settings_notifications)) {
        // With the app lock on, notifications never show content (DESIGN §9).
        switch(
            R.string.settings_hide_content,
            if (lock) R.string.settings_hide_content_locked else R.string.settings_hide_content_text,
            checked = hide || lock,
            enabled = !lock,
        ) { prefs.hideNotificationContent = it }
        accounts.forEach { account ->
            item {
                ClickRow(
                    shapes = it,
                    title = stringResource(R.string.settings_mail_channel, account.label),
                    onClick = { SystemPages.mailChannel(context, account.id) },
                    summary = stringResource(R.string.settings_mail_channel_text),
                    icon = Icons.Notifications,
                )
            }
        }
    }
}

@Composable
private fun PrivacyGroup() {
    val prefs = LocalContext.current.app.prefs
    val remote by rememberPref(false) { remoteContentDefault }
    val confirmLinks by rememberPref(true) { confirmLinks }
    val darkEmail by rememberPref(true) { darkEmail }
    val lock by rememberPref(false) { appLock }
    val timeout by rememberPref(0) { appLockTimeoutMinutes }
    val hideInRecents by rememberPref(true) { hideInRecents }
    val blockScreenshots by rememberPref(false) { blockScreenshots }
    val incognito by rememberPref(true) { incognitoKeyboard }
    val canLock = rememberCanLock()
    var choosingTimeout by rememberSaveable { mutableStateOf(false) }

    SettingsGroup(stringResource(R.string.settings_privacy)) {
        switch(R.string.settings_remote_content, R.string.settings_remote_content_text, remote) {
            prefs.remoteContentDefault = it
        }
        switch(R.string.settings_confirm_links, R.string.settings_confirm_links_text, confirmLinks) {
            prefs.confirmLinks = it
        }
        switch(R.string.settings_dark_email, R.string.settings_dark_email_text, darkEmail) { prefs.darkEmail = it }
        // Turning the lock on needs a strong biometric or a screen lock; turning it off is always possible.
        switch(
            R.string.settings_app_lock,
            if (canLock || lock) R.string.settings_app_lock_text else R.string.settings_app_lock_unavailable,
            checked = lock,
            enabled = canLock || lock,
        ) { prefs.appLock = it }
        if (lock) {
            item {
                ClickRow(
                    it, stringResource(R.string.settings_lock_timeout), { choosingTimeout = true },
                    summary = lockTimeoutTitle(timeout),
                )
            }
        }
        // MainActivity hides the Recents preview anyway while the app lock is on.
        switch(
            R.string.settings_hide_in_recents,
            if (lock) R.string.settings_hide_in_recents_locked else R.string.settings_hide_in_recents_text,
            checked = hideInRecents || lock,
            enabled = !lock,
        ) { prefs.hideInRecents = it }
        switch(R.string.settings_block_screenshots, R.string.settings_block_screenshots_text, blockScreenshots) {
            prefs.blockScreenshots = it
        }
        switch(R.string.settings_incognito_keyboard, R.string.settings_incognito_keyboard_text, incognito) {
            prefs.incognitoKeyboard = it
        }
    }

    if (choosingTimeout) {
        ChoiceDialog(
            title = stringResource(R.string.settings_lock_timeout),
            choices = Choices.LOCK_TIMEOUT_MINUTES.map { Choice(it, lockTimeoutTitle(it)) },
            selected = timeout,
            onSelect = { prefs.appLockTimeoutMinutes = it },
            onDismiss = { choosingTimeout = false },
        )
    }
}

@Composable
private fun lockTimeoutTitle(minutes: Int): String = if (minutes == 0) {
    stringResource(R.string.settings_lock_at_once)
} else {
    pluralStringResource(R.plurals.settings_lock_after, minutes, minutes)
}

/** True when the phone can show the system unlock prompt (DESIGN §12); checked again at every resume. */
@Composable
private fun rememberCanLock(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(BiometricManager::class.java) }
    var canLock by remember { mutableStateOf(true) }
    LifecycleResumeEffect(manager) {
        canLock = manager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
        onPauseOrDispose {}
    }
    return canLock
}

@Composable
private fun MailGroup() {
    val prefs = LocalContext.current.app.prefs
    val swipeStart by rememberPref(Prefs.Swipe.ARCHIVE) { swipeStart }
    val swipeEnd by rememberPref(Prefs.Swipe.DELETE) { swipeEnd }
    val undoSeconds by rememberPref(5) { undoSendSeconds }
    var dialog by rememberSaveable { mutableStateOf<MailDialog?>(null) }
    // A start-to-end swipe goes to the right in left-to-right layouts.
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val startTitle = stringResource(if (ltr) R.string.settings_swipe_right else R.string.settings_swipe_left)
    val endTitle = stringResource(if (ltr) R.string.settings_swipe_left else R.string.settings_swipe_right)

    SettingsGroup(stringResource(R.string.settings_mail)) {
        item {
            ClickRow(it, startTitle, { dialog = MailDialog.SWIPE_START }, summary = stringResource(swipeStart.title()))
        }
        item {
            ClickRow(it, endTitle, { dialog = MailDialog.SWIPE_END }, summary = stringResource(swipeEnd.title()))
        }
        item {
            ClickRow(
                it, stringResource(R.string.settings_undo_send), { dialog = MailDialog.UNDO_SEND },
                summary = undoSendTitle(undoSeconds),
            )
        }
    }

    val swipeChoices = Prefs.Swipe.entries.map { Choice(it, stringResource(it.title())) }
    when (dialog) {
        MailDialog.SWIPE_START ->
            ChoiceDialog(startTitle, swipeChoices, swipeStart, { prefs.swipeStart = it }, { dialog = null })
        MailDialog.SWIPE_END ->
            ChoiceDialog(endTitle, swipeChoices, swipeEnd, { prefs.swipeEnd = it }, { dialog = null })
        MailDialog.UNDO_SEND -> ChoiceDialog(
            title = stringResource(R.string.settings_undo_send),
            choices = Choices.UNDO_SEND_SECONDS.map { Choice(it, undoSendTitle(it)) },
            selected = undoSeconds,
            onSelect = { prefs.undoSendSeconds = it },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class MailDialog { SWIPE_START, SWIPE_END, UNDO_SEND }

@Composable
private fun undoSendTitle(seconds: Int): String = if (seconds == 0) {
    stringResource(R.string.settings_off)
} else {
    pluralStringResource(R.plurals.settings_seconds, seconds, seconds)
}
