package app.plico.ui.compose

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.plico.R
import app.plico.app
import app.plico.ui.components.Banner
import app.plico.ui.components.BannerKind
import app.plico.ui.components.ConfirmDialog
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.components.Menu
import app.plico.ui.components.MenuEntry
import app.plico.ui.components.PrivateTextInput
import app.plico.ui.components.UndoWindow
import app.plico.ui.components.rejectObscuredTouches
import app.plico.ui.components.showUndo
import app.plico.ui.nav.Navigator

/**
 * Edit and send draft [draftId] (DESIGN §13 Compose, §7.6). The draft saves itself while the user types and
 * when the app goes to the background; leaving the screen queues its server copy. Send waits for the undo
 * time with an Undo snackbar, then closes the screen. [fresh]: see [app.plico.ui.nav.Route.Compose].
 */
@Composable
fun ComposeScreen(draftId: Long, fresh: Boolean, nav: Navigator) {
    val app = LocalContext.current.app
    val vm = viewModel { ComposeViewModel(app, draftId, fresh) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveNow() }
    when (vm.phase) {
        ComposeViewModel.Phase.LOADING -> Unit
        ComposeViewModel.Phase.GONE -> EmptyState(
            icon = Icons.Draft,
            title = stringResource(R.string.compose_gone),
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            actionLabel = stringResource(R.string.ui_back),
            onAction = nav::back,
        )
        ComposeViewModel.Phase.EDITING, ComposeViewModel.Phase.QUEUED, ComposeViewModel.Phase.WAITING ->
            ComposeContent(vm, nav)
    }
}

@Composable
private fun ComposeContent(vm: ComposeViewModel, nav: Navigator) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmSend by rememberSaveable { mutableStateOf(false) }
    val toFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    val photos = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { vm.attach(it) }
    val files = rememberLauncherForActivityResult(OpenMultipleDocuments()) { vm.attach(it) }

    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(resources.getString(it.text)) } }
    LaunchedEffect(Unit) {
        if (vm.editing) (if (vm.to.chips.isEmpty()) toFocus else bodyFocus).requestFocus()
    }
    if (vm.phase == ComposeViewModel.Phase.QUEUED) {
        val sending = stringResource(R.string.compose_sending)
        val undo = stringResource(R.string.ui_undo)
        val haptic = LocalHapticFeedback.current
        LaunchedEffect(Unit) {
            // The send is queued (PLICO-UX-011); the system touch-feedback setting applies.
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            snackbar.currentSnackbarData?.dismiss()
            if (!snackbar.showUndo(sending, undo, UndoWindow.sendMs(vm.undoSeconds))) {
                nav.back()
                return@LaunchedEffect
            }
            vm.undoSend {
                Toast.makeText(context, R.string.compose_undo_too_late, Toast.LENGTH_LONG).show()
                nav.back()
            }
        }
    }

    Scaffold(
        topBar = {
            ComposeTopBar(
                vm = vm,
                onBack = nav::back,
                onPhotos = {
                    launchPicker(vm) { photos.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) }
                },
                onFiles = { launchPicker(vm) { files.launch(arrayOf("*/*")) } },
                onSend = { if (vm.mayHaveBeenSent) confirmSend = true else vm.send() },
                onDiscard = { confirmDiscard = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // With the keyboard: the snackbars ("Add a recipient", "Sending… Undo") show above it, not under it.
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        PrivateTextInput {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (vm.phase == ComposeViewModel.Phase.WAITING) {
                    // Opened from the Drafts view while it waits to be sent: nothing changes unless the user asks.
                    Banner(
                        text = stringResource(R.string.compose_waiting),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        actionLabel = stringResource(R.string.compose_cancel_send),
                        onAction = { vm.undoSend { vm.notice(Notice.BEING_SENT) } },
                    )
                }
                vm.failure?.let {
                    Banner(
                        text = stringResource(R.string.compose_failed, it),
                        kind = BannerKind.ERROR,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                FromRow(vm)
                FieldDivider()
                RecipientRow(
                    label = stringResource(R.string.compose_to),
                    field = vm.to,
                    vm = vm,
                    focusRequester = toFocus,
                    trailing = {
                        if (!vm.showCcBcc) {
                            val description = stringResource(R.string.compose_show_cc_bcc)
                            TextButton(
                                onClick = vm::expandCcBcc,
                                modifier = Modifier.padding(top = 4.dp).semantics { contentDescription = description },
                            ) { Text(stringResource(R.string.compose_cc_bcc)) }
                        }
                    },
                )
                FieldDivider()
                if (vm.showCcBcc) {
                    RecipientRow(stringResource(R.string.compose_cc), vm.cc, vm)
                    FieldDivider()
                    RecipientRow(stringResource(R.string.compose_bcc), vm.bcc, vm)
                    FieldDivider()
                }
                val subject = stringResource(R.string.compose_subject)
                TextField(
                    value = vm.subject,
                    onValueChange = vm::editSubject,
                    readOnly = !vm.editing,
                    singleLine = true,
                    placeholder = { Text(subject) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = plainFieldColors(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = subject },
                )
                FieldDivider()
                AttachmentSection(vm)
                val message = stringResource(R.string.compose_body)
                TextField(
                    value = vm.body,
                    onValueChange = vm::editBody,
                    readOnly = !vm.editing,
                    placeholder = { Text(message) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = plainFieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp)
                        .focusRequester(bodyFocus)
                        .semantics { contentDescription = message },
                )
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.compose_discard_title),
            text = stringResource(R.string.compose_discard_text),
            confirmLabel = stringResource(R.string.compose_discard),
            onConfirm = {
                vm.discard()
                nav.back()
            },
            onDismiss = { confirmDiscard = false },
            destructive = true,
        )
    }
    if (confirmSend) {
        ConfirmDialog(
            title = stringResource(R.string.mailbox_send_again_title),
            text = vm.failure,
            confirmLabel = stringResource(R.string.mailbox_send_again),
            onConfirm = {
                confirmSend = false
                vm.send()
            },
            onDismiss = { confirmSend = false },
        )
    }
}

@Composable
private fun ComposeTopBar(
    vm: ComposeViewModel,
    onBack: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.ui_compose), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Back, contentDescription = stringResource(R.string.ui_back))
            }
        },
        actions = {
            Menu(
                entries = listOf(
                    MenuEntry(stringResource(R.string.compose_attach_photos), Icons.Image, onPhotos),
                    MenuEntry(stringResource(R.string.compose_attach_files), Icons.Folder, onFiles),
                ),
                icon = Icons.Attach,
                description = stringResource(R.string.compose_attach),
                enabled = vm.editing,
            )
            Button(
                onClick = onSend,
                enabled = vm.editing,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.padding(horizontal = 4.dp).rejectObscuredTouches(),
            ) {
                Icon(Icons.Send, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.compose_send))
            }
            Menu(
                entries = listOf(MenuEntry(stringResource(R.string.compose_discard), Icons.Delete, onDiscard)),
                enabled = vm.editing,
            )
        },
    )
}

/** From: every account address and identity (DESIGN §13); a plain line when there is only one. */
@Composable
private fun FromRow(vm: ComposeViewModel) {
    val selected = vm.from ?: return
    val options = vm.senders
    val choosable = options.size > 1 && vm.editing
    val manyAccounts = options.distinctBy { it.accountId }.size > 1
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open && choosable, onExpandedChange = { open = it && choosable }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = choosable)
                .semantics(mergeDescendants = true) {}
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldLabel(stringResource(R.string.compose_from))
            SenderText(selected, manyAccounts, Modifier.weight(1f))
            if (options.size > 1) ExposedDropdownMenuDefaults.TrailingIcon(expanded = open && choosable)
        }
        ExposedDropdownMenu(expanded = open && choosable, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { SenderText(option, manyAccounts) },
                    onClick = {
                        open = false
                        vm.selectFrom(option)
                    },
                    trailingIcon = if (option == selected) {
                        { Icon(Icons.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun SenderText(option: SenderOption, showAccount: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(option.display, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showAccount) {
            Text(
                text = option.accountLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The name of a field row ("From", "To"). [decorative] when the field itself carries the same label. */
@Composable
internal fun FieldLabel(text: String, decorative: Boolean = false) {
    Box(
        modifier = Modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 56.dp)
            .padding(end = 8.dp)
            .then(if (decorative) Modifier.clearAndSetSemantics {} else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FieldDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/** A text field without container or underline; the dividers between the rows frame it. */
@Composable
private fun plainFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

private inline fun launchPicker(vm: ComposeViewModel, launch: () -> Unit) {
    try {
        launch()
    } catch (_: ActivityNotFoundException) {
        vm.notice(Notice.NO_PICKER)
    }
}
