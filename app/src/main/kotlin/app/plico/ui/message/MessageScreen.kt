package app.plico.ui.message

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.plico.R
import app.plico.app
import app.plico.data.MailView
import app.plico.ui.components.Avatar
import app.plico.ui.components.Banner
import app.plico.ui.components.BannerKind
import app.plico.ui.components.ConfirmDialog
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.components.rejectObscuredTouches
import app.plico.ui.components.rememberDateFormats
import app.plico.ui.components.rememberPref
import app.plico.ui.mailbox.FolderPicker
import app.plico.ui.mailbox.FolderPickerMode
import app.plico.ui.nav.Navigator
import app.plico.ui.nav.Route
import app.plico.ui.theme.isDark
import kotlinx.coroutines.launch

/**
 * One message (DESIGN §13 Message, §11): actions in the top bar, the header with sender checks, the
 * remote-content bar, the body in a locked-down WebView, attachment cards and Reply / Reply all / Forward.
 * Opening marks the message seen and loads a missing body.
 */
@Composable
fun MessageScreen(messageId: Long, nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val vm = viewModel { MessageViewModel(app, messageId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val body by vm.body.collectAsStateWithLifecycle()
    val linkSheet by vm.linkSheet.collectAsStateWithLifecycle()
    val headers by vm.headers.collectAsStateWithLifecycle()
    val pendingOpen by vm.pendingOpen.collectAsStateWithLifecycle()
    val darkEmail by rememberPref(initial = true) { darkEmail }
    var showOriginal by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<FolderPickerMode?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val say = { text: Int -> scope.launch { snackbar.showSnackbar(resources.getString(text)) } }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.saveTo(result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK })
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                MessageEvent.Close -> if (nav.current == Route.Message(messageId)) nav.back()
                is MessageEvent.Compose -> nav.go(Route.Compose(event.draftId))
                is MessageEvent.OpenUrl -> if (!openUrl(context, event.url)) say(R.string.message_link_no_app)
                is MessageEvent.OpenFile ->
                    if (!openFile(context, event.file, event.mimeType)) say(R.string.message_no_app_file)
                is MessageEvent.SaveFile -> try {
                    saver.launch(createDocument(event.name, event.mimeType))
                } catch (_: ActivityNotFoundException) {
                    vm.saveTo(null)
                    say(R.string.message_save_failed)
                }
                is MessageEvent.Notice -> say(event.text)
            }
        }
    }

    val view = (state as? MessageState.Ready)?.view
    val canDarken = MaterialTheme.colorScheme.isDark && darkEmail && body is BodyState.Html
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val paneTitle = stringResource(R.string.ui_message)
    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .semantics { this.paneTitle = paneTitle },
        topBar = {
            MessageTopBar(
                view = view,
                vm = vm,
                scrollBehavior = scrollBehavior,
                originalColours = if (canDarken) showOriginal else null,
                onBack = nav::back,
                onDeleteForever = { confirmDelete = true },
                onPick = { picker = it },
                onUnsubscribe = { method ->
                    if (method == Unsubscribe.OneClick) confirmUnsubscribe = true else vm.unsubscribe(method)
                },
                onToggleColours = { showOriginal = !showOriginal },
            )
        },
        bottomBar = { view?.let { ReplyBar(it, vm) } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val s = state) {
            MessageState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { Loading() }
            MessageState.Gone -> EmptyState(
                icon = Icons.Inbox,
                title = stringResource(R.string.message_gone),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            is MessageState.Ready -> MessageContent(
                view = s.view,
                body = body,
                vm = vm,
                darkening = canDarken && !showOriginal,
                padding = padding,
                onSignIn = { nav.go(Route.Reauth(s.view.account.id)) },
            )
        }
    }

    linkSheet?.let { sheet ->
        LinkSheetView(
            sheet = sheet,
            onOpen = { url ->
                vm.dismissLinkSheet()
                if (!openUrl(context, url)) say(R.string.message_link_no_app)
            },
            onDismiss = vm::dismissLinkSheet,
        )
    }
    headers?.let { HeadersDialog(it, onDismiss = vm::hideHeaders) }
    pendingOpen?.let { pending ->
        ConfirmDialog(
            title = stringResource(R.string.message_open_file_title),
            text = (listOf(revealBidi(pending.name)) + pending.check.warnings.map { stringResource(it.text) })
                .joinToString("\n\n"),
            confirmLabel = stringResource(R.string.message_open_anyway),
            onConfirm = vm::openAnyway,
            onDismiss = vm::cancelOpen,
            destructive = true,
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.message_delete_forever_title),
            text = stringResource(R.string.message_delete_forever_text),
            confirmLabel = stringResource(R.string.message_delete_forever),
            onConfirm = vm::deleteForever,
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
    if (confirmUnsubscribe) {
        ConfirmDialog(
            title = stringResource(R.string.message_unsubscribe_title),
            text = stringResource(R.string.message_unsubscribe_text),
            confirmLabel = stringResource(R.string.message_unsubscribe),
            onConfirm = { vm.unsubscribe(Unsubscribe.OneClick) },
            onDismiss = { confirmUnsubscribe = false },
        )
    }
    val mode = picker
    if (mode != null && view != null) {
        FolderPicker(
            accountId = view.account.id,
            mode = mode,
            onDismiss = { picker = null },
            exclude = setOfNotNull(view.message.folderId, (view.placement.view as? MailView.InFolder)?.folderId),
            labels = view.placement.labelIds.associateWith { ToggleableState.On },
            onMove = vm::moveTo,
            onLabels = vm::setLabels,
        )
    }
}

@Composable
private fun MessageTopBar(
    view: MessageView?,
    vm: MessageViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    originalColours: Boolean?,
    onBack: () -> Unit,
    onDeleteForever: () -> Unit,
    onPick: (FolderPickerMode) -> Unit,
    onUnsubscribe: (Unsubscribe) -> Unit,
    onToggleColours: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Back, stringResource(R.string.message_back)) }
        },
        actions = {
            if (view == null) return@TopAppBar
            val place = view.placement
            if (place.canArchive) {
                IconButton(onClick = vm::archive) { Icon(Icons.Archive, stringResource(R.string.message_archive)) }
            }
            if (view.canDelete) {
                IconButton(
                    onClick = { if (place.deletesForever) onDeleteForever() else vm.delete() },
                    modifier = Modifier.rejectObscuredTouches(),
                ) {
                    if (place.deletesForever) {
                        Icon(Icons.DeleteForever, stringResource(R.string.message_delete_forever))
                    } else {
                        Icon(Icons.Delete, stringResource(R.string.message_delete))
                    }
                }
            }
            IconButton(onClick = vm::markUnread) {
                Icon(Icons.MarkUnread, stringResource(R.string.message_mark_unread))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.More, stringResource(R.string.message_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val item = @Composable { icon: ImageVector, text: Int, action: () -> Unit ->
                        DropdownMenuItem(
                            text = { Text(stringResource(text)) },
                            leadingIcon = { Icon(icon, contentDescription = null) },
                            onClick = {
                                menu = false
                                action()
                            },
                        )
                    }
                    item(Icons.Move, R.string.message_move) { onPick(FolderPickerMode.MOVE) }
                    if (place.gmail) item(Icons.Label, R.string.message_labels) { onPick(FolderPickerMode.LABELS) }
                    if (view.canSpam) {
                        if (place.inJunk) {
                            item(Icons.NotSpam, R.string.message_not_spam) { vm.spam(false) }
                        } else {
                            item(Icons.ReportSpam, R.string.message_spam) { vm.spam(true) }
                        }
                    }
                    if (view.message.flagged) {
                        item(Icons.StarFilled, R.string.message_unstar) { vm.star(false) }
                    } else {
                        item(Icons.Star, R.string.message_star) { vm.star(true) }
                    }
                    if (place.gmail) {
                        if (place.important) {
                            item(Icons.LabelImportantFilled, R.string.message_not_important) { vm.important(false) }
                        } else {
                            item(Icons.LabelImportant, R.string.message_important) { vm.important(true) }
                        }
                    }
                    view.unsubscribe?.let { method ->
                        item(Icons.Unsubscribe, R.string.message_unsubscribe) { onUnsubscribe(method) }
                    }
                    originalColours?.let { original ->
                        val text = if (original) R.string.message_dark_colours else R.string.message_original_colours
                        item(Icons.Image, text, onToggleColours)
                    }
                    item(Icons.Info, R.string.message_view_headers, vm::showHeaders)
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun MessageContent(
    view: MessageView,
    body: BodyState,
    vm: MessageViewModel,
    darkening: Boolean,
    padding: PaddingValues,
    onSignIn: () -> Unit,
) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer {
                Text(
                    text = view.message.subject.ifBlank { stringResource(R.string.message_no_subject) },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (view.placement.labels.isNotEmpty()) LabelChips(view.placement.labels.map { it.name })
            SenderBlock(view)
            for (banner in view.banners) Banner(stringResource(banner.warning.text), kind = banner.kind)
            (body as? BodyState.Html)?.let { html ->
                remoteBar(
                    hasRemote = html.sanitized.hasRemote,
                    allowed = html.remote,
                    trackers = html.sanitized.trackersRemoved,
                    trust = view.trust,
                    trusted = view.trusted,
                    defaultOn = view.remoteDefault,
                )
            }?.let { RemoteContentBar(it, onShow = vm::showRemoteOnce, onAlways = vm::setAlwaysRemote) }
        }
        Spacer(Modifier.height(8.dp))
        when (body) {
            BodyState.Loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center,
            ) { Loading() }
            is BodyState.Failed -> BodyError(body.error, onRetry = vm::retryBody, onSignIn = onSignIn)
            BodyState.Empty -> Unit
            is BodyState.Html -> MessageWebView(
                html = body.sanitized.html,
                darkening = darkening,
                background = if (darkening) MaterialTheme.colorScheme.surface else Color.White,
                resources = vm,
                onLink = vm::onLink,
                onRenderGone = vm::onRenderGone,
                modifier = Modifier.fillMaxWidth(),
            )
            is BodyState.Text -> Column(Modifier.padding(horizontal = 16.dp)) {
                Banner(stringResource(R.string.message_render_failed), kind = BannerKind.WARN)
                SelectionContainer {
                    Text(
                        body.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
        if (view.attachments.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    pluralStringResource(R.plurals.message_attachments, view.attachments.size, view.attachments.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (attachment in view.attachments) {
                    AttachmentCard(
                        attachment = attachment,
                        progress = progress[attachment.id],
                        onOpen = { vm.open(attachment) },
                        onSave = { vm.save(attachment) },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LabelChips(names: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (name in names) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Name and real address always; recipients and the full date on a tap. */
@Composable
private fun SenderBlock(view: MessageView) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val formats = rememberDateFormats()
    val scheme = MaterialTheme.colorScheme
    val toggleLabel = stringResource(if (expanded) R.string.message_hide_details else R.string.message_show_details)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Avatar(view.sender.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = view.sender.name.ifEmpty { stringResource(R.string.message_no_sender) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formats.list(view.message.date),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                view.sender.address?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!expanded) {
                    view.summary?.let {
                        Text(
                            summaryText(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (view.firstContact) {
                    Text(
                        stringResource(R.string.html_sender_first_contact),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.tertiary,
                    )
                }
            }
            Icon(
                imageVector = MessageIcons.ExpandMore,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(expanded) {
            Column(
                modifier = Modifier.padding(start = 52.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val lines = view.recipients + listOfNotNull(replyToLine(view.message.replyTo, view.message.fromEmail))
                for (line in lines) {
                    val addresses = line.addresses.joinToString("\n", transform = ::addressText)
                    DetailRow(stringResource(line.field.label), addresses)
                }
                DetailRow(stringResource(R.string.message_field_date), formats.full(view.message.date))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(64.dp)
                .padding(top = 1.dp),
        )
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun summaryText(summary: RecipientSummary): String {
    val me = stringResource(R.string.message_me)
    val names = summary.names.joinToString(", ") { it ?: me }
    val all = if (summary.more > 0) {
        pluralStringResource(R.plurals.message_and_more, summary.more, names, summary.more)
    } else {
        names
    }
    return stringResource(R.string.message_to_summary, all)
}

/** "Images blocked · 2 trackers removed", Show, and "Always show images from this sender". */
@Composable
private fun RemoteContentBar(bar: RemoteBar, onShow: () -> Unit, onAlways: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val text = listOfNotNull(
        when (bar.images) {
            RemoteImages.BLOCKED -> stringResource(R.string.message_images_blocked)
            RemoteImages.SHOWN -> stringResource(R.string.message_images_shown)
            RemoteImages.NONE -> null
        },
        bar.trackers.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.message_trackers_removed, it, it) },
    ).joinToString(" · ")
    Surface(shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerHigh) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp)) {
            Row(
                modifier = Modifier.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Image, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                )
                if (bar.images == RemoteImages.BLOCKED) {
                    TextButton(onClick = onShow, modifier = Modifier.rejectObscuredTouches()) {
                        Text(stringResource(R.string.message_show_images))
                    }
                }
            }
            bar.always?.let { on ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .rejectObscuredTouches()
                        .toggleable(value = on, role = Role.Switch, onValueChange = onAlways)
                        .padding(start = 32.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.message_always_from_sender),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        bar.note?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = on, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun BodyError(error: ErrorText, onRetry: () -> Unit, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(error.text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        error.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error.signIn) FilledTonalButton(onClick = onSignIn) { Text(stringResource(R.string.message_sign_in)) }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.message_retry)) }
        }
    }
}

@Composable
private fun ReplyBar(view: MessageView, vm: MessageViewModel) {
    val busy by vm.composing.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (view.message.draft) {
                ReplyButton(Icons.Edit, R.string.message_edit_draft, !busy, primary = true, onClick = vm::editDraft)
            } else {
                ReplyButton(Icons.Reply, R.string.message_reply, !busy, primary = true) { vm.reply(false) }
                if (view.replyAll) ReplyButton(Icons.ReplyAll, R.string.message_reply_all, !busy) { vm.reply(true) }
                ReplyButton(Icons.Forward, R.string.message_forward, !busy, onClick = vm::forward)
            }
        }
    }
}

@Composable
private fun RowScope.ReplyButton(
    icon: ImageVector,
    text: Int,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(text), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    // Three buttons share the width: less side padding than the default, so that the labels fit.
    val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    if (primary) {
        FilledTonalButton(onClick, Modifier.weight(1f), enabled = enabled, contentPadding = padding, content = content)
    } else {
        OutlinedButton(onClick, Modifier.weight(1f), enabled = enabled, contentPadding = padding, content = content)
    }
}

/** "View headers": the raw header block on demand, with the sender's own Date first. */
@Composable
private fun HeadersDialog(state: HeadersState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_headers_title)) },
        text = {
            when (state) {
                HeadersState.Loading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { Loading() }
                is HeadersState.Failed -> Text(stringResource(state.error.text))
                is HeadersState.Loaded -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.senderDate?.let {
                        Text(
                            stringResource(R.string.message_headers_sender_date, revealBidi(it)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    SelectionContainer {
                        Text(
                            revealBidi(state.raw.trimEnd()),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.message_close)) } },
    )
}

@Composable
private fun Loading() {
    val description = stringResource(R.string.message_loading)
    LoadingIndicator(Modifier.semantics { contentDescription = description })
}
