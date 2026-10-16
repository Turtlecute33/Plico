package app.plico.ui.mailbox

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.plico.PlicoApp
import app.plico.R
import app.plico.account.MailError
import app.plico.data.Account
import app.plico.data.Db
import app.plico.data.Draft
import app.plico.data.DraftState
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MailView
import app.plico.data.MessageSummary
import app.plico.data.list
import app.plico.sync.Undo
import app.plico.ui.nav.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** A user-facing error: a string resource, with the server's own text when it has one. */
internal data class ErrorText(@param:StringRes val res: Int, val detail: String? = null) {
    companion object {
        fun of(e: Throwable): ErrorText = when (e) {
            is MailError.AuthRequired -> ErrorText(R.string.mailbox_error_sign_in)
            is MailError.Tls -> ErrorText(R.string.mailbox_error_tls)
            is MailError.Network -> ErrorText(R.string.mailbox_error_network)
            is MailError.Throttled -> ErrorText(R.string.mailbox_error_busy)
            is MailError.Server -> ErrorText(R.string.mailbox_error_server, e.message)
            is MailError.TooLarge -> ErrorText(R.string.mailbox_error_too_large)
            is MailError.Setup -> ErrorText(R.string.mailbox_error_detail, e.message)
            is IOException -> ErrorText(R.string.mailbox_error_network)
            else -> ErrorText(R.string.mailbox_error_failed)
        }
    }
}

/** One-shot results for the screen: snackbars and navigation after background work. */
internal sealed interface MailboxEvent {
    /** [count] messages changed; [undo] adds an Undo button. */
    data class Done(@param:PluralsRes val text: Int, val count: Int, val undo: Undo?) : MailboxEvent
    data class Failed(val error: ErrorText) : MailboxEvent
    data class Open(val route: Route) : MailboxEvent
}

/** "Search on server" (DESIGN §7.7). */
internal sealed interface ServerSearch {
    data object Idle : ServerSearch
    data object Running : ServerSearch
    data class Done(val hits: Int) : ServerSearch
    data class Failed(val error: ErrorText) : ServerSearch
}

/** The accounts and folders a list needs to decide what each row allows. */
internal class ListContext(accounts: List<Account>, folders: List<Folder>) {
    val accounts: Map<Long, Account> = accounts.associateBy { it.id }
    private val folders = folders.associateBy { it.id }
    private val archives = accounts.associate { it.id to MailboxFolders.archiveOf(it, folders)?.id }
    private val abilities = accounts.associate { account ->
        account.id to if (account.isGmail) {
            AccountAbilities(
                canDelete = MailboxFolders.byRole(account, folders, FolderRole.TRASH) != null,
                canSpam = MailboxFolders.byRole(account, folders, FolderRole.JUNK) != null,
            )
        } else {
            AccountAbilities()
        }
    }

    fun info(row: MessageSummary) = RowInfo(
        id = row.id,
        accountId = row.accountId,
        seen = row.seen,
        flagged = row.flagged,
        place = RowActions.placeOf(row, folders[row.folderId], archives[row.accountId]),
        gmail = accounts[row.accountId]?.isGmail == true,
        abilities = abilities[row.accountId] ?: AccountAbilities(),
    )
}

/**
 * State and actions of one mailbox entry (DESIGN §13). [route] is the view of the route; a search route
 * starts in search mode over all inboxes. DAO reads are flows; every write runs on Dispatchers.IO.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class MailboxViewModel(private val app: PlicoApp, route: MailView) : ViewModel() {
    /** The list when not searching. */
    val view: MailView = if (route is MailView.Search) MailView.Unified else route

    /** The search field. */
    val query = TextFieldState((route as? MailView.Search)?.query.orEmpty())

    /** Search mode: the list shows local hits for [query]. The screen's search bar drives it. */
    var searching by mutableStateOf(route is MailView.Search)
        private set

    val accounts: StateFlow<List<Account>> = app.accounts.observeAll().state(emptyList())
    val folders: StateFlow<List<Folder>> = app.folders.observeVisible().state(emptyList())
    val unread: StateFlow<Map<Long, Int>> = app.folders.observeUnread().state(emptyMap())
    val unifiedUnread: StateFlow<Int> = app.folders.observeUnifiedUnread().state(0)

    /** The folder of a folder view; null for the other views and once the folder is gone. */
    val folder: StateFlow<Folder?> =
        ((view as? MailView.InFolder)?.let { app.folders.observe(it.folderId) } ?: flowOf(null)).state(null)

    /** The account that local search uses; null = every account. A folder never changes its account. */
    private val scopeAccount: Flow<Long?> = when (view) {
        is MailView.InFolder -> flow { emit(app.folders.get(view.folderId)?.accountId) }.flowOn(Dispatchers.IO)
        is MailView.Starred -> flowOf(view.accountId)
        else -> flowOf(null)
    }

    /** What the list shows: [view], a local search, or null while the search field is empty. */
    val listView: StateFlow<MailView?> = combine(
        snapshotFlow { if (searching) query.text.toString().trim() else null }
            .distinctUntilChanged()
            .debounce { if (it.isNullOrEmpty()) 0 else SEARCH_DEBOUNCE_MS },
        scopeAccount,
    ) { text, account ->
        when {
            text == null -> view
            text.isEmpty() -> null
            else -> MailView.Search(text, account)
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, if (searching) null else view)

    /** How many rows of which list to read; a new list starts with one page again. */
    private data class Page(val view: MailView?, val limit: Int = RowActions.PAGE)

    private val page = MutableStateFlow(Page(null))

    /** Rows of [listView], newest first; null until the first read. */
    val messages: StateFlow<List<MessageSummary>?> = listView.flatMapLatest { shown ->
        if (shown == null) return@flatMapLatest flowOf(emptyList())
        page.update { if (it.view == shown) it else Page(shown) }
        page.flatMapLatest { app.messages.observeList(shown, it.limit) }
    }.state(null)

    /** Local drafts on top of a Drafts view (DESIGN §7.5): editing, queued, sending and failed ones. */
    val drafts: StateFlow<List<Draft>> = folder.flatMapLatest { f ->
        if (f?.role != FolderRole.DRAFTS) {
            flowOf(emptyList())
        } else {
            app.drafts.observeByAccount(f.accountId).map { list -> list.filter { it.state != DraftState.SENT } }
        }
    }.state(emptyList())

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _serverSearch = MutableStateFlow<ServerSearch>(ServerSearch.Idle)
    val serverSearch: StateFlow<ServerSearch> = _serverSearch.asStateFlow()
    private var serverJob: Job? = null

    private val _events = Channel<MailboxEvent>(Channel.BUFFERED)
    val events: Flow<MailboxEvent> = _events.receiveAsFlow()

    init {
        // DESIGN §7.1: opening a folder syncs it; failures show in the account banners instead.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.mail.refresh(view)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        // A new query makes an earlier server result stale.
        viewModelScope.launch { listView.collect { cancelServerSearch() } }
    }

    /** Follows the search bar; leaving search mode clears the query. */
    fun followSearchBar(expanded: Boolean) {
        if (expanded == searching) return
        searching = expanded
        if (!expanded) {
            query.clearText()
            cancelServerSearch()
        }
    }

    fun loadMore(lastVisible: Int, loaded: Int) =
        page.update { it.copy(limit = RowActions.nextLimit(it.limit, loaded, lastVisible)) }

    fun toggleSelected(id: Long) = _selected.update { RowActions.toggle(it, id) }
    fun selectAll(ids: Collection<Long>) = _selected.update { it + ids }
    fun clearSelection() = _selected.update { emptySet() }
    fun pruneSelection(visible: Collection<Long>) = _selected.update { RowActions.prune(it, visible) }

    /** Applies [action] to [ids]. Delete forever must be confirmed by the caller. */
    fun apply(action: RowAction, ids: List<Long>) = io {
        val shown = listView.value ?: view
        val mail = app.mail
        val count = ids.size
        when (action) {
            RowAction.ARCHIVE -> done(R.plurals.mailbox_done_archived, count, mail.archive(ids, shown))
            RowAction.DELETE -> done(R.plurals.mailbox_done_deleted, count, mail.delete(ids))
            RowAction.DELETE_FOREVER -> {
                mail.deleteForever(ids)
                done(R.plurals.mailbox_done_deleted_forever, count, null)
            }
            RowAction.MARK_READ -> mail.markSeen(ids, true)
            RowAction.MARK_UNREAD -> mail.markSeen(ids, false)
            RowAction.STAR -> mail.star(ids, true)
            RowAction.UNSTAR -> mail.star(ids, false)
            RowAction.SPAM -> done(R.plurals.mailbox_done_spam, count, mail.spam(ids, true))
            RowAction.NOT_SPAM -> done(R.plurals.mailbox_done_not_spam, count, mail.spam(ids, false))
        }
    }

    fun moveTo(ids: List<Long>, target: Folder) = io {
        done(R.plurals.mailbox_done_moved, ids.size, app.mail.moveTo(ids, target.id, listView.value ?: view))
    }

    fun setLabels(ids: List<Long>, add: Set<Long>, remove: Set<Long>) = io {
        if (add.isNotEmpty() || remove.isNotEmpty()) app.mail.setLabels(ids, add, remove)
    }

    fun undo(undo: Undo) = io { app.mail.undo(undo) }

    /** Initial label checkboxes for the Gmail label picker over [ids]. */
    suspend fun labelStates(ids: Collection<Long>): Map<Long, ToggleableState> =
        withContext(Dispatchers.IO) { RowActions.labelStates(app.db.labelMemberships(ids)) }

    /** Pull to refresh. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.mail.refresh(listView.value ?: view)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(MailboxEvent.Failed(ErrorText.of(e)))
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Searches the server for [query] (Gmail: All Mail; Riseup: this folder, or INBOX in the unified view). */
    fun searchServer() {
        val text = query.text.toString().trim()
        if (text.isEmpty()) return
        serverJob?.cancel()
        _serverSearch.value = ServerSearch.Running
        serverJob = viewModelScope.launch(Dispatchers.IO) {
            val (accountId, folderId) = scopeIds()
            _serverSearch.value = try {
                ServerSearch.Done(app.mail.searchServer(text, accountId, folderId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerSearch.Failed(ErrorText.of(e))
            }
        }
    }

    /** Cancel stops the request; the session closes its socket (DESIGN §7.7). */
    fun cancelServerSearch() {
        serverJob?.cancel()
        serverJob = null
        _serverSearch.value = ServerSearch.Idle
    }

    fun compose() = io {
        _events.send(MailboxEvent.Open(Route.Compose(app.mail.newDraft(scopeIds().first))))
    }

    /** A server draft row: copy it into a local draft, then edit (DESIGN §7.6). */
    fun editServerDraft(messageId: Long) = io {
        _events.send(MailboxEvent.Open(Route.Compose(app.mail.editServerDraft(messageId))))
    }

    fun retrySend(draftId: Long) = io { app.mail.retrySend(draftId) }
    fun setNotify(folderId: Long, notify: Boolean) = io { app.mail.setFolderNotify(folderId, notify) }
    fun resync(folderId: Long) = io { app.mail.resyncFolder(folderId) }
    fun emptyFolder(folderId: Long) = io { app.mail.emptyFolder(folderId) }

    fun dismissGmailIpNote() {
        app.prefs.gmailIpNoteShown = true
    }

    /** (account, folder) of this view for server search and Compose; null = every account. Blocks. */
    private fun scopeIds(): Pair<Long?, Long?> = when (view) {
        is MailView.InFolder -> app.folders.get(view.folderId)?.let { it.accountId to it.id } ?: (null to null)
        is MailView.Starred -> view.accountId to null
        else -> null to null
    }

    private suspend fun done(@PluralsRes text: Int, count: Int, undo: Undo?) =
        _events.send(MailboxEvent.Done(text, count, undo))

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(MailboxEvent.Failed(ErrorText.of(e)))
            }
        }
    }

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial)

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * The Gmail label views (kind 1) of each message in [ids]; a message without labels maps to an empty set.
 * Blocks: call on Dispatchers.IO.
 */
internal fun Db.labelMemberships(ids: Collection<Long>): Map<Long, Set<Long>> {
    val memberships = ids.associateWithTo(HashMap()) { HashSet<Long>() }
    // Ids are numbers, so joining them into the statement is safe; 500 stays far below SQLite's limits.
    ids.chunked(500).forEach { batch ->
        sql.list(
            "SELECT mf.message_id, mf.folder_id FROM message_folder mf JOIN folder f ON f.id = mf.folder_id " +
                "WHERE f.kind = ${FolderKind.LABEL} AND mf.message_id IN (${batch.joinToString(",")})",
        ) { it.getLong(0) to it.getLong(1) }.forEach { (message, label) -> memberships[message]?.add(label) }
    }
    return memberships
}
