package app.plico.ui.mailbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.plico.R
import app.plico.app
import app.plico.data.Account
import app.plico.data.Draft
import app.plico.data.Folder
import app.plico.data.FolderRole
import app.plico.data.MailView
import app.plico.data.MessageSummary
import app.plico.data.Prefs
import app.plico.ui.components.Banner
import app.plico.ui.components.BannerKind
import app.plico.ui.components.ConfirmDialog
import app.plico.ui.components.EmptyState
import app.plico.ui.components.Icons
import app.plico.ui.components.rememberDateFormats
import app.plico.ui.components.rememberPref
import app.plico.ui.message.MessageUndo
import app.plico.ui.nav.Navigator
import app.plico.ui.nav.Route
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** A request to open the [FolderPicker] for [ids]. */
private data class PickerRequest(
    val accountId: Long,
    val mode: FolderPickerMode,
    val ids: List<Long>,
    val exclude: Set<Long> = emptySet(),
    val labels: Map<Long, ToggleableState> = emptyMap(),
)

/**
 * A message list for [view] (DESIGN §13 Mailbox): search-bar top bar, drawer, swipe actions, multi-select,
 * pull to refresh, local and server search, banners and the Compose button.
 */
@Composable
fun MailboxScreen(view: MailView, nav: Navigator) {
    val app = LocalContext.current.app
    val vm = viewModel { MailboxViewModel(app, view) }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val unifiedUnread by vm.unifiedUnread.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val listView by vm.listView.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val serverSearch by vm.serverSearch.collectAsStateWithLifecycle()
    val push by remember(app) { app.push.status }.collectAsStateWithLifecycle()
    // NONE until read, so that no swipe acts before the user's choice is known.
    val swipeStart by rememberPref(Prefs.Swipe.NONE) { swipeStart }
    val swipeEnd by rememberPref(Prefs.Swipe.NONE) { swipeEnd }
    val gmailIpNoteShown by rememberPref(true) { gmailIpNoteShown }
    val dates = rememberDateFormats()

    val rows = messages.orEmpty()
    val context = remember(accounts, folders) { ListContext(accounts, folders) }
    val infos = remember(rows, context) { rows.associate { it.id to context.info(it) } }
    val viewAccount: Account? = when (view) {
        is MailView.InFolder -> folder?.let { context.accounts[it.accountId] }
        is MailView.Starred -> view.accountId?.let(context.accounts::get)
        else -> null
    }
    val crossAccount = accounts.size > 1 && when (val shown = listView ?: view) {
        MailView.Unified -> true
        is MailView.Starred -> shown.accountId == null
        is MailView.Search -> shown.accountId == null
        is MailView.InFolder -> false
    }
    val sections = remember(accounts, folders, unread) { MailboxFolders.sections(accounts, folders, unread) }
    val banners = MailboxBanners.of(
        shown = viewAccount?.let(::listOf) ?: accounts,
        all = accounts,
        guarded = MailboxBanners.guarded(folder, folders),
        status = push,
        gmailIpNoteShown = gmailIpNoteShown,
    )
    val selectedInfos = selected.mapNotNull(infos::get)
    val selection = if (selectedInfos.isEmpty()) null else RowActions.selection(selectedInfos)

    val searchBar = rememberSearchBarState(if (vm.searching) SearchBarValue.Expanded else SearchBarValue.Collapsed)
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var confirmDelete by remember { mutableStateOf<List<Long>?>(null) }
    var confirmEmpty by remember { mutableStateOf<Folder?>(null) }
    var confirmRetry by remember { mutableStateOf<Draft?>(null) }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }

    fun act(ids: List<Long>, action: RowAction) {
        if (action == RowAction.DELETE_FOREVER) confirmDelete = ids else vm.apply(action, ids)
    }

    fun actOnSelection(action: RowAction) {
        act(selected.toList(), action)
        vm.clearSelection()
    }

    fun open(row: MessageSummary) {
        if (infos[row.id]?.place == Place.DRAFTS) vm.editServerDraft(row.id) else nav.go(Route.Message(row.id))
    }

    fun endSearch() {
        scope.launch { searchBar.animateToCollapsed() }
    }

    fun openView(target: MailView) {
        scope.launch {
            drawerState.close()
            if (target != view) nav.replaceTop(Route.Mailbox(target))
        }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is MailboxEvent.Open -> nav.go(event.route)
                is MailboxEvent.Done -> launch {
                    // A new result replaces the old one at once, so that its Undo stays within the op delay.
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(
                        message = resources.getQuantityString(event.text, event.count, event.count),
                        actionLabel = event.undo?.let { resources.getString(R.string.mailbox_undo) },
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) event.undo?.let(vm::undo)
                }
                is MailboxEvent.Failed -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(event.error.text(resources))
                }
            }
        }
    }
    // The Undo of an action that closed the message screen above (MessageUndo).
    LaunchedEffect(Unit) {
        MessageUndo.notice.filterNotNull().collect {
            val notice = MessageUndo.take() ?: return@collect
            launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    message = resources.getString(notice.text),
                    actionLabel = resources.getString(R.string.mailbox_undo),
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) vm.undo(notice.undo)
            }
        }
    }
    // Changes the server refused and sends that failed (DESIGN §7.4, §7.5).
    LaunchedEffect(app) {
        app.mail.failures.collect { failure ->
            launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(failure.text, withDismissAction = true, duration = SnackbarDuration.Long)
            }
        }
    }
    LaunchedEffect(searchBar) {
        snapshotFlow { searchBar.targetValue == SearchBarValue.Expanded }.collect(vm::followSearchBar)
    }
    LaunchedEffect(messages) { messages?.let { list -> vm.pruneSelection(list.map { it.id }) } }
    LaunchedEffect(listState, rows.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            // Rows are the last items; the ones before them are banners, drafts and the search row.
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) - (info.totalItemsCount - rows.size)
        }.distinctUntilChanged().collect { vm.loadMore(it, rows.size) }
    }

    val isRoot = nav.backStack.size == 1
    BackHandler(enabled = isRoot && vm.view != MailView.Unified && !drawerState.isOpen) {
        nav.replaceTop(Route.Mailbox(MailView.Unified))
    }
    BackHandler(enabled = vm.searching && !drawerState.isOpen, onBack = ::endSearch)
    BackHandler(enabled = selection != null && !drawerState.isOpen, onBack = vm::clearSelection)

    ModalNavigationDrawer(
        drawerContent = {
            MailDrawer(
                drawerState = drawerState,
                current = view,
                sections = sections,
                unifiedUnread = unifiedUnread,
                onOpen = ::openView,
                onNotify = { f, on -> vm.setNotify(f.id, on) },
                onSettings = {
                    scope.launch {
                        drawerState.close()
                        nav.go(Route.Settings)
                    }
                },
            )
        },
        drawerState = drawerState,
        // Row swipes own horizontal drags; the drawer opens with its button and closes by drag or back.
        gesturesEnabled = drawerState.isOpen,
    ) {
        Scaffold(
            topBar = {
                if (selection != null) {
                    SelectionTopBar(
                        count = selected.size,
                        actions = selection,
                        onClear = vm::clearSelection,
                        onAction = ::actOnSelection,
                        onSelectAll = { vm.selectAll(rows.map { it.id }) },
                        onMove = {
                            selection.moveAccount?.let { accountId ->
                                picker = PickerRequest(accountId, FolderPickerMode.MOVE, selected.toList(), setOfNotNull(folder?.id))
                            }
                        },
                        onLabels = {
                            val ids = selected.toList()
                            selection.moveAccount?.let { accountId ->
                                scope.launch {
                                    picker = PickerRequest(accountId, FolderPickerMode.LABELS, ids, labels = vm.labelStates(ids))
                                }
                            }
                        },
                    )
                } else {
                    SearchTopBar(
                        query = vm.query,
                        searchBar = searchBar,
                        account = viewAccount ?: accounts.singleOrNull(),
                        menu = emptyFolderMenu(folder) { confirmEmpty = it },
                        onEndSearch = ::endSearch,
                        onMenu = { scope.launch { drawerState.open() } },
                        onAccount = {
                            val account = viewAccount ?: accounts.singleOrNull()
                            nav.go(if (account != null) Route.AccountSettings(account.id) else Route.Settings)
                        },
                    )
                }
            },
            floatingActionButton = {
                if (selection == null && !vm.searching && accounts.isNotEmpty()) {
                    val expanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
                    ExtendedFloatingActionButton(
                        text = { Text(stringResource(R.string.ui_compose)) },
                        icon = { Icon(Icons.Edit, contentDescription = null) },
                        onClick = vm::compose,
                        expanded = expanded,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            val pullState = rememberPullToRefreshState()
            val direction = LocalLayoutDirection.current
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                state = pullState,
                enabled = !vm.searching && selection == null,
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(direction),
                        end = padding.calculateEndPadding(direction),
                        bottom = padding.calculateBottomPadding() + FAB_SPACE,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(banners, key = { it.key() }) { banner ->
                        MailboxBannerItem(
                            banner = banner,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .animateItem(),
                            onSignIn = { nav.go(Route.Reauth(it.id)) },
                            onAccount = { nav.go(Route.AccountSettings(it.id)) },
                            onResync = { vm.resync(it.id) },
                            onResume = app.push::resume,
                            onFix = { nav.go(Route.Settings) },
                            onDismissIpNote = vm::dismissGmailIpNote,
                        )
                    }
                    if (vm.searching && listView != null) {
                        item(key = "server") {
                            ServerSearchRow(serverSearch, onSearch = vm::searchServer, onCancel = vm::cancelServerSearch)
                        }
                    }
                    if (!vm.searching) {
                        items(drafts, key = { "draft:${it.id}" }) { draft ->
                            LocalDraftRow(
                                draft = draft,
                                date = dates.list(draft.updated),
                                onOpen = { nav.go(Route.Compose(draft.id)) },
                                onRetry = {
                                    if (draft.error == resources.getString(R.string.data_error_may_have_been_sent)) {
                                        confirmRetry = draft
                                    } else {
                                        vm.retrySend(draft.id)
                                    }
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    if (messages != null && rows.isEmpty() && (vm.searching || drafts.isEmpty())) {
                        item(key = "empty") {
                            ListEmptyState(vm.view, listView, folder?.role, Modifier.fillParentMaxWidth())
                        }
                    }
                    items(rows, key = { it.id }) { row ->
                        val info = infos[row.id]
                        MessageRow(
                            row = row,
                            swipe = info?.let {
                                RowSwipe(RowActions.swipe(swipeStart, it), RowActions.swipe(swipeEnd, it))
                            } ?: NO_SWIPE,
                            date = dates.list(row.date),
                            selected = row.id in selected,
                            selecting = selection != null,
                            colorIndex = if (crossAccount) context.accounts[row.accountId]?.color else null,
                            draft = info?.place == Place.DRAFTS,
                            onOpen = ::open,
                            onSelect = vm::toggleSelected,
                            onAction = { id, action -> act(listOf(id), action) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { ids ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.mailbox_delete_forever_title, ids.size, ids.size),
            text = stringResource(R.string.mailbox_cannot_undo),
            confirmLabel = stringResource(R.string.mailbox_action_delete_forever),
            onConfirm = { vm.apply(RowAction.DELETE_FOREVER, ids) },
            onDismiss = { confirmDelete = null },
            destructive = true,
        )
    }
    confirmEmpty?.let { target ->
        ConfirmDialog(
            title = stringResource(
                if (target.role == FolderRole.JUNK) R.string.mailbox_empty_spam_title else R.string.mailbox_empty_trash_title,
            ),
            text = stringResource(R.string.mailbox_empty_text),
            confirmLabel = stringResource(R.string.mailbox_empty_confirm),
            onConfirm = { vm.emptyFolder(target.id) },
            onDismiss = { confirmEmpty = null },
            destructive = true,
        )
    }
    confirmRetry?.let { draft ->
        ConfirmDialog(
            title = stringResource(R.string.mailbox_send_again_title),
            text = draft.error,
            confirmLabel = stringResource(R.string.mailbox_send_again),
            onConfirm = { vm.retrySend(draft.id) },
            onDismiss = { confirmRetry = null },
        )
    }
    picker?.let { request ->
        FolderPicker(
            accountId = request.accountId,
            mode = request.mode,
            onDismiss = { picker = null },
            exclude = request.exclude,
            labels = request.labels,
            onMove = { target ->
                vm.moveTo(request.ids, target)
                vm.clearSelection()
            },
            onLabels = { add, remove ->
                vm.setLabels(request.ids, add, remove)
                vm.clearSelection()
            },
        )
    }
}

private val FAB_SPACE = 88.dp
private val NO_SWIPE = RowSwipe(null, null)

/** The "Empty" entry of the Trash and Spam overflow menu. */
@Composable
private fun emptyFolderMenu(folder: Folder?, onEmpty: (Folder) -> Unit): List<MenuEntry> {
    val label = when (folder?.role) {
        FolderRole.TRASH -> R.string.mailbox_action_empty_trash
        FolderRole.JUNK -> R.string.mailbox_action_empty_spam
        else -> return emptyList()
    }
    return listOf(MenuEntry(stringResource(label)) { onEmpty(folder) })
}

private fun MailboxBanner.key(): String = when (this) {
    is MailboxBanner.SignIn -> "signin:${account.id}"
    is MailboxBanner.Tls -> "tls:${account.id}"
    is MailboxBanner.Setup -> "setup:${account.id}"
    is MailboxBanner.Note -> "note:${account.id}"
    is MailboxBanner.Guard -> "guard:${folder.id}"
    is MailboxBanner.Push -> "push"
    MailboxBanner.GmailIp -> "gmail-ip"
}

@Composable
private fun MailboxBannerItem(
    banner: MailboxBanner,
    modifier: Modifier,
    onSignIn: (Account) -> Unit,
    onAccount: (Account) -> Unit,
    onResync: (Folder) -> Unit,
    onResume: () -> Unit,
    onFix: () -> Unit,
    onDismissIpNote: () -> Unit,
) {
    when (banner) {
        is MailboxBanner.SignIn -> Banner(
            text = stringResource(R.string.mailbox_banner_sign_in, banner.account.label),
            modifier = modifier,
            kind = BannerKind.ERROR,
            actionLabel = stringResource(R.string.mailbox_sign_in),
            onAction = { onSignIn(banner.account) },
        )
        is MailboxBanner.Tls -> Banner(
            text = stringResource(R.string.mailbox_banner_tls, banner.account.label),
            modifier = modifier,
            kind = BannerKind.ERROR,
        )
        is MailboxBanner.Setup -> Banner(
            text = banner.account.stateText?.takeIf(String::isNotBlank)
                ?.let { stringResource(R.string.mailbox_banner_setup, banner.account.label, it) }
                ?: stringResource(R.string.mailbox_banner_setup_default, banner.account.label),
            modifier = modifier,
            kind = BannerKind.ERROR,
            actionLabel = stringResource(R.string.mailbox_account),
            onAction = { onAccount(banner.account) },
        )
        is MailboxBanner.Note -> Banner(
            text = stringResource(R.string.mailbox_banner_setup, banner.account.label, banner.account.stateText.orEmpty()),
            modifier = modifier,
        )
        is MailboxBanner.Guard -> Banner(
            text = stringResource(R.string.mailbox_banner_guard, folderName(banner.folder)),
            modifier = modifier,
            kind = BannerKind.WARN,
            actionLabel = stringResource(R.string.mailbox_resync),
            onAction = { onResync(banner.folder) },
        )
        is MailboxBanner.Push -> {
            val (text, resume) = when (banner.problem) {
                PushProblem.PAUSED -> R.string.mailbox_banner_push_paused to true
                PushProblem.STOPPED -> R.string.mailbox_banner_push_stopped to true
                PushProblem.RESTRICTED -> R.string.mailbox_banner_push_restricted to false
                PushProblem.LATE -> R.string.mailbox_banner_push_late to false
            }
            Banner(
                text = stringResource(text),
                modifier = modifier,
                kind = BannerKind.WARN,
                actionLabel = stringResource(if (resume) R.string.mailbox_resume else R.string.mailbox_fix),
                onAction = if (resume) onResume else onFix,
            )
        }
        MailboxBanner.GmailIp -> Banner(
            text = stringResource(R.string.mailbox_banner_gmail_ip),
            modifier = modifier,
            actionLabel = stringResource(R.string.mailbox_ok),
            onAction = onDismissIpNote,
        )
    }
}

/** "Search on server" with its progress, result or error (DESIGN §7.7). */
@Composable
private fun ServerSearchRow(state: ServerSearch, onSearch: () -> Unit, onCancel: () -> Unit) {
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .padding(horizontal = 16.dp, vertical = 4.dp)
    when (state) {
        ServerSearch.Idle -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onSearch, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Search, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.mailbox_search_server))
            }
        }
        ServerSearch.Running -> Column(modifier, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.mailbox_searching_server),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.ui_cancel)) }
            }
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
        is ServerSearch.Done -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.hits == 0) {
                    stringResource(R.string.mailbox_search_server_none)
                } else {
                    pluralStringResource(R.plurals.mailbox_search_server_done, state.hits, state.hits)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ServerSearch.Failed -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.error.text(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSearch) { Text(stringResource(R.string.mailbox_try_again)) }
        }
    }
}

/** What an empty list says: a search hint, no hits, or an empty folder. */
@Composable
private fun ListEmptyState(view: MailView, shown: MailView?, role: FolderRole?, modifier: Modifier) {
    val padded = modifier.padding(vertical = 48.dp)
    when {
        shown == null -> EmptyState(
            icon = Icons.Search,
            title = stringResource(R.string.mailbox_search_hint),
            message = stringResource(R.string.mailbox_search_start),
            modifier = padded,
        )
        shown is MailView.Search -> EmptyState(
            icon = Icons.Search,
            title = stringResource(R.string.mailbox_search_empty),
            message = stringResource(R.string.mailbox_search_empty_text),
            modifier = padded,
        )
        else -> {
            val (icon, title) = when {
                view == MailView.Unified -> Icons.Inbox to R.string.mailbox_empty_inbox
                view is MailView.Starred -> Icons.Star to R.string.mailbox_empty_starred
                role == FolderRole.INBOX -> Icons.Inbox to R.string.mailbox_empty_inbox
                role == FolderRole.DRAFTS -> Icons.Draft to R.string.mailbox_empty_drafts
                role == FolderRole.TRASH -> Icons.Delete to R.string.mailbox_empty_trash
                role == FolderRole.JUNK -> Icons.ReportSpam to R.string.mailbox_empty_spam
                else -> Icons.Folder to R.string.mailbox_empty_folder
            }
            EmptyState(
                icon = icon,
                title = stringResource(title),
                message = stringResource(R.string.mailbox_empty_pull),
                modifier = padded,
            )
        }
    }
}
