package app.plico.ui.message

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.plico.PlicoApp
import app.plico.R
import app.plico.data.Account
import app.plico.data.Attachment
import app.plico.data.Body
import app.plico.data.Files
import app.plico.data.Folder
import app.plico.data.Message
import app.plico.data.MessageDetail
import app.plico.html.AttachmentCheck
import app.plico.html.AttachmentSafety
import app.plico.html.HtmlSanitizer
import app.plico.html.LinkCleaner
import app.plico.html.Psl
import app.plico.html.RemoteFetcher
import app.plico.html.SanitizedHtml
import app.plico.html.SenderCheck
import app.plico.html.SenderTrust
import app.plico.html.SenderWarning
import app.plico.sync.Undo
import app.plico.ui.components.ErrorText
import app.plico.ui.components.OneAtATime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Everything the message screen shows once the row is loaded. */
data class MessageView(
    val message: Message,
    val account: Account,
    val sender: SenderLine,
    val recipients: List<RecipientLine>,
    val summary: RecipientSummary?,
    val placement: Placement,
    val banners: List<SenderBanner>,
    val firstContact: Boolean,
    val trust: SenderTrust,
    val trusted: Boolean,
    val remoteDefault: Boolean,
    val remoteAllowed: Boolean,
    val unsubscribe: Unsubscribe?,
    /** Attachment cards; inline images shown by the body are not listed. */
    val attachments: List<Attachment>,
    val replyAll: Boolean,
    /** Gmail without a visible Trash or Spam mailbox turns Delete or Spam off (DESIGN §7.3). */
    val canDelete: Boolean,
    val canSpam: Boolean,
    /** The sender block may show a cached logo of From (DESIGN §12); never fetched when a message opens. */
    val logoAllowed: Boolean = false,
)

sealed interface MessageState {
    data object Loading : MessageState

    /** The row is gone (expunged, account removed). */
    data object Gone : MessageState
    data class Ready(val view: MessageView) : MessageState
}

sealed interface BodyState {
    data object Loading : BodyState
    data class Failed(val error: ErrorText) : BodyState

    /** No text parts (attachments only). */
    data object Empty : BodyState

    /** For the WebView; [remote] = built with remote content allowed. */
    data class Html(val sanitized: SanitizedHtml, val remote: Boolean) : BodyState

    /** The WebView renderer died on this message (privacy PS7): plain text instead. */
    data class Text(val text: String) : BodyState
}

sealed interface HeadersState {
    data object Loading : HeadersState
    data class Loaded(val raw: String, val senderDate: String?) : HeadersState
    data class Failed(val error: ErrorText) : HeadersState
}

/** One-shot work for the screen: navigation, intents and snackbars. */
sealed interface MessageEvent {
    data object Close : MessageEvent
    /** [fresh]: a reply, forward or mailto draft made now, discarded when the user leaves it unedited. */
    data class Compose(val draftId: Long, val fresh: Boolean) : MessageEvent
    data class OpenUrl(val url: String) : MessageEvent
    data class OpenFile(val file: File, val mimeType: String) : MessageEvent

    /** Ask for a place with ACTION_CREATE_DOCUMENT, then call [MessageViewModel.saveTo]. */
    data class SaveFile(val name: String, val mimeType: String) : MessageEvent
    data class Notice(@StringRes val text: Int) : MessageEvent
}

/** An attachment whose checks warn; the screen asks before it opens. */
data class PendingOpen(val name: String, val file: File, val check: AttachmentCheck)

/**
 * State and actions of one message (DESIGN §11, §13 Message). Scoped to the back-stack entry. Reads use the
 * DAOs on Dispatchers.IO; every change goes through [app.plico.sync.MailActions].
 */
class MessageViewModel(private val app: PlicoApp, private val messageId: Long) : ViewModel(), BodyResources {
    private val mail get() = app.mail
    private val ids = listOf(messageId)

    /** Loaded once off the main thread; the link sheet and the sender checks need it. */
    private val psl = viewModelScope.async(Dispatchers.IO) { Psl.get(app) }
    private val budget = RemoteFetcher.Budget()

    private val detail = app.messages.observeDetail(messageId).shareIn(viewModelScope, SharingStarted.Eagerly, 1)
    private val fixed = viewModelScope.async(Dispatchers.IO) { detail.first()?.let { fixedOf(it) } }

    /** Sender checks need the PSL; the message shows without them first. */
    private val warnings = MutableStateFlow<List<SenderWarning>>(emptyList())
    private val shownOnce = MutableStateFlow(false)
    private val remoteDefault = app.prefs.changes().map { app.prefs.remoteContentDefault }.distinctUntilChanged()
    private val bodyError = MutableStateFlow<ErrorText?>(null)
    private val renderGone = MutableStateFlow(messageId in renderCrashes)
    private val eventChannel = Channel<MessageEvent>(Channel.BUFFERED)
    private var headersJob: Job? = null
    private var pendingSave: File? = null
    private var busy = false

    val state: StateFlow<MessageState> = flow {
        val f = fixed.await() ?: return@flow emit(MessageState.Gone)
        val trusted = app.trustedSenders.observeTrusted(f.fromEmail)
        emitAll(
            combine(detail, trusted, remoteDefault, shownOnce, warnings) { d, t, def, once, w ->
                if (d == null) MessageState.Gone else MessageState.Ready(viewOf(d, f, t, def, once, w))
            },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, MessageState.Loading)

    val body: StateFlow<BodyState> = combine(
        detail.map { it?.body }.distinctUntilChanged(),
        state.mapNotNull { (it as? MessageState.Ready)?.view?.remoteAllowed }.distinctUntilChanged(),
        bodyError,
        renderGone,
    ) { body, remote, error, gone -> bodyOf(body, remote, error, gone) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, BodyState.Loading)

    private val progressFlow = MutableStateFlow<Map<Long, Float>>(emptyMap())
    private val headersFlow = MutableStateFlow<HeadersState?>(null)
    private val linkSheetFlow = MutableStateFlow<LinkSheet?>(null)
    private val pendingOpenFlow = MutableStateFlow<PendingOpen?>(null)
    private val composer = OneAtATime(viewModelScope)

    /** Download progress (0..1) per attachment id. */
    val progress: StateFlow<Map<Long, Float>> = progressFlow.asStateFlow()
    val headers: StateFlow<HeadersState?> = headersFlow.asStateFlow()
    val linkSheet: StateFlow<LinkSheet?> = linkSheetFlow.asStateFlow()

    /** An attachment that waits for "Open anyway". */
    val pendingOpen: StateFlow<PendingOpen?> = pendingOpenFlow.asStateFlow()

    /** Reply, Reply all, Forward or a mailto draft is being prepared. */
    val composing: StateFlow<Boolean> = composer.busy
    val events: Flow<MessageEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val first = detail.first() ?: return@launch
            if (!first.message.seen) change { mail.markSeen(ids, true) }
            if (first.body == null) loadBody()
            val f = fixed.await()
            if (f?.ownMessage == false) {
                warnings.value = withContext(Dispatchers.IO) { senderWarnings(first.message, f.own) }
            }
        }
    }

    fun retryBody() = loadBody()

    private fun loadBody() {
        bodyError.value = null
        viewModelScope.launch {
            try {
                mail.loadBody(messageId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                bodyError.value = errorText(e)
            }
        }
    }

    /** The WebView renderer died (privacy PS7): text from now on, also the next time in this process. */
    fun onRenderGone() {
        renderCrashes += messageId
        renderGone.value = true
    }

    // ---- Remote content

    fun showRemoteOnce() {
        shownOnce.value = true
    }

    fun setAlwaysRemote(on: Boolean) {
        val email = (state.value as? MessageState.Ready)?.view?.message?.fromEmail ?: return
        if (!on) shownOnce.value = false
        change { app.trustedSenders.setTrusted(email, on) }
    }

    /**
     * A `cid:` image of this message (DESIGN §11.2, privacy PS13). Blocking; WebView worker thread. Any failure
     * (also a cache file that the prune deleted meanwhile) is a 403: an exception here would end the process,
     * push included.
     */
    override fun cidImage(contentId: String): Served? = try {
        val part = app.messages.attachments(messageId)
            .firstOrNull { a -> a.cid?.let { sameContentId(it, contentId) } == true }
        // Attachment sizes are encoded sizes; base64 adds a third.
        val image = part?.mime?.startsWith("image/", ignoreCase = true) == true
        if (part == null || !image || part.size > MAX_INLINE_BYTES * 4 / 3) {
            null
        } else {
            val file = runBlocking { viewModelScope.async(Dispatchers.IO) { mail.loadAttachment(part.id) }.await() }
            val type = file.takeIf { it.length() <= MAX_INLINE_BYTES }
                ?.inputStream()?.use { AttachmentSafety.sniff(it.readNBytes(AttachmentSafety.SNIFF_BYTES)) }
                ?.takeIf { it.startsWith("image/") }
            type?.let { Served(it, file.inputStream()) }
        }
    } catch (_: Exception) {
        null
    }

    /** A remote image, only while the body on screen allows remote content. Blocking; WebView worker thread. */
    override fun remoteImage(url: String): Served? {
        if ((body.value as? BodyState.Html)?.remote != true) return null
        val resource = RemoteFetcher.image(url, budget) ?: return null
        return Served(resource.mimeType, ByteArrayInputStream(resource.bytes))
    }

    // ---- Links

    /** A link tapped in the body; [text] is the anchor text when known. */
    fun onLink(url: String, text: String?) = openLink(url, text, sheet = false)

    fun dismissLinkSheet() {
        linkSheetFlow.value = null
    }

    private fun openLink(url: String, text: String?, sheet: Boolean) {
        viewModelScope.launch {
            val link = withContext(Dispatchers.Default) { LinkCleaner(psl.await()).clean(url, text) }
            val confirm = sheet || app.prefs.confirmLinks
            when (val tap = linkTap(link, confirm)) {
                is LinkTap.Open -> eventChannel.send(MessageEvent.OpenUrl(tap.url))
                is LinkTap.Sheet -> linkSheetFlow.value = tap.sheet
                is LinkTap.Compose -> compose(fresh = true) { mail.newDraft(accountId(), tap.prefill) }
            }
        }
    }

    // ---- Top bar and menu actions

    fun archive() = close(R.string.message_archived) { mail.archive(ids) }

    fun delete() = close(R.string.message_deleted) { mail.delete(ids) }

    fun deleteForever() = close(null) {
        mail.deleteForever(ids)
        null
    }

    fun markUnread() = close(null) {
        mail.markSeen(ids, false)
        null
    }

    fun spam(isSpam: Boolean) = close(if (isSpam) R.string.message_spam_done else R.string.message_not_spam_done) {
        mail.spam(ids, isSpam)
    }

    fun moveTo(folder: Folder) = close(R.string.message_moved) { mail.moveTo(ids, folder.id, it.placement.view) }

    /** Gmail labels by label-view folder ids. */
    fun setLabels(add: Set<Long>, remove: Set<Long>) = change { mail.setLabels(ids, add, remove) }

    fun star(on: Boolean) = change { mail.star(ids, on) }

    fun important(on: Boolean) = change { mail.important(ids, on) }

    fun unsubscribe(method: Unsubscribe) {
        when (method) {
            Unsubscribe.OneClick -> viewModelScope.launch {
                val done = try {
                    mail.unsubscribeOneClick(messageId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    false
                }
                notice(if (done) R.string.message_unsubscribed else R.string.message_unsubscribe_failed)
            }
            is Unsubscribe.Mail -> compose(fresh = true) { mail.newDraft(accountId(), prefillOf(method.mailto)) }
            is Unsubscribe.Web -> openLink(method.url, null, sheet = true)
        }
    }

    fun showHeaders() {
        headersJob?.cancel()
        headersFlow.value = HeadersState.Loading
        headersJob = viewModelScope.launch {
            headersFlow.value = try {
                val raw = mail.fetchHeaders(messageId)
                HeadersState.Loaded(raw, withContext(Dispatchers.Default) { senderDate(raw) })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                HeadersState.Failed(errorText(e))
            }
        }
    }

    fun hideHeaders() {
        headersJob?.cancel()
        headersFlow.value = null
    }

    // ---- Reply

    fun reply(all: Boolean) = compose(fresh = true) { mail.reply(messageId, all) }

    fun forward() = compose(fresh = true) { mail.forward(messageId) }

    fun editDraft() = compose(fresh = false) { mail.editServerDraft(messageId) }

    // ---- Attachments

    fun open(attachment: Attachment) = fetch(attachment) { file, check ->
        if (check.warnings.isEmpty()) {
            eventChannel.send(MessageEvent.OpenFile(file, check.mimeType))
        } else {
            pendingOpenFlow.value = PendingOpen(attachment.name, file, check)
        }
    }

    fun openAnyway() {
        val pending = pendingOpenFlow.value ?: return
        pendingOpenFlow.value = null
        eventChannel.trySend(MessageEvent.OpenFile(pending.file, pending.check.mimeType))
    }

    fun cancelOpen() {
        pendingOpenFlow.value = null
    }

    fun save(attachment: Attachment) = fetch(attachment) { file, check ->
        pendingSave = file
        eventChannel.send(MessageEvent.SaveFile(Files.safeName(attachment.name), check.mimeType))
    }

    /** The result of ACTION_CREATE_DOCUMENT; null when the user cancelled. */
    fun saveTo(uri: Uri?) {
        val file = pendingSave ?: return
        pendingSave = null
        if (uri == null) return
        viewModelScope.launch {
            val saved = io {
                try {
                    val out = app.contentResolver.openOutputStream(uri)
                    out?.use { file.inputStream().use { input -> input.copyTo(it) } } != null
                } catch (_: Exception) {
                    false
                }
            }
            notice(if (saved) R.string.message_saved else R.string.message_save_failed)
        }
    }

    override fun onCleared() {
        // DESIGN §11.5: cached attachment files older than 1 h go when the message closes.
        thread(name = "prune-attachments") { app.files.pruneAttachmentCache() }
    }

    // ---- Helpers

    private fun fetch(attachment: Attachment, then: suspend (File, AttachmentCheck) -> Unit) {
        if (attachment.id in progressFlow.value) return
        progressFlow.update { it + (attachment.id to 0f) }
        viewModelScope.launch {
            try {
                val file = mail.loadAttachment(attachment.id) { p ->
                    progressFlow.update { if (attachment.id in it) it + (attachment.id to p.coerceIn(0f, 1f)) else it }
                }
                val check = io {
                    val head = file.inputStream().use { it.readNBytes(AttachmentSafety.SNIFF_BYTES) }
                    AttachmentSafety.check(attachment.name, attachment.mime, head)
                }
                then(file, check)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice(errorText(e).res)
            } finally {
                progressFlow.update { it - attachment.id }
            }
        }
    }

    /** An action that ends the screen; its Undo goes to the screen below ([MessageUndo]). */
    private fun close(@StringRes undoText: Int?, action: (MessageView) -> Undo?) {
        val view = (state.value as? MessageState.Ready)?.view ?: return
        if (busy) return
        busy = true
        val since = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                val undo = io { action(view) }
                if (undo != null && undoText != null) MessageUndo.post(UndoNotice(undoText, undo, since))
                eventChannel.send(MessageEvent.Close)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice(R.string.message_action_failed)
            } finally {
                busy = false
            }
        }
    }

    /** An action that keeps the screen open. */
    private fun change(action: () -> Unit) {
        viewModelScope.launch {
            try {
                io(action)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice(R.string.message_action_failed)
            }
        }
    }

    private fun compose(fresh: Boolean, make: suspend () -> Long) {
        composer.run {
            try {
                eventChannel.send(MessageEvent.Compose(make(), fresh))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice(errorText(e).res)
            }
        }
    }

    private suspend fun accountId(): Long? = detail.first()?.message?.accountId

    private suspend fun notice(@StringRes text: Int) = eventChannel.send(MessageEvent.Notice(text))

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** What does not change while the screen is open: the account and the user's own addresses. */
    private class Fixed(
        val account: Account,
        val fromEmail: String,
        val own: Set<String>,
        /** In Sent or Drafts ([sentByUser]): no sender checks. */
        val ownMessage: Boolean,
        val canDelete: Boolean,
        val canSpam: Boolean,
    )

    private fun fixedOf(d: MessageDetail): Fixed? {
        val m = d.message
        val account = app.accounts.get(m.accountId) ?: return null
        val own = (listOf(account.email) + app.accounts.identities(account.id).map { it.email })
            .mapTo(HashSet()) { it.lowercase() }
        val abilities = app.folders.abilities(account)
        return Fixed(account, m.fromEmail, own, sentByUser(d.folders), abilities.canDelete, abilities.canSpam)
    }

    /** A From with an own address is never a first contact (a copy to self); the other checks still run. */
    private suspend fun senderWarnings(m: Message, own: Set<String>): List<SenderWarning> {
        val firstContact = m.fromEmail.lowercase() !in own && isFirstContact(m)
        return SenderCheck(psl.await()).check(m.fromName, m.fromEmail, m.replyTo, m.auth, firstContact)
    }

    /** No contact entry and no earlier message from this address in the local database (DESIGN §11.4). */
    private fun isFirstContact(m: Message): Boolean = m.fromEmail.isNotBlank() &&
        !app.contacts.isKnown(m.fromEmail) && !app.messages.hasEarlierFrom(m.fromEmail, m.date, m.id)

    private fun viewOf(
        d: MessageDetail,
        f: Fixed,
        trusted: Boolean,
        remoteDefault: Boolean,
        once: Boolean,
        warnings: List<SenderWarning>,
    ): MessageView {
        val m = d.message
        val placement = placementOf(m.folderId, d.folders, f.account.isGmail)
        val trust = SenderCheck.trust(f.account.isGmail, m.auth, placement.inJunk)
        val recipients = recipientLines(m.toList, m.ccList, m.bccList)
        return MessageView(
            message = m,
            account = f.account,
            sender = senderLine(m.fromName, m.fromEmail),
            recipients = recipients,
            summary = recipientSummary(recipients, f.own),
            placement = placement,
            banners = senderBanners(warnings),
            firstContact = SenderWarning.FIRST_CONTACT in warnings,
            trust = trust,
            trusted = trusted,
            remoteDefault = remoteDefault,
            remoteAllowed = allowRemote(remoteDefault, once, trusted, trust, placement.inJunk),
            unsubscribe = unsubscribeOf(m.unsubscribe, m.unsubscribePost, trust),
            attachments = d.attachments.filter { !it.inline },
            replyAll = replyAllUseful(m.fromEmail, m.toList, m.ccList, f.own),
            canDelete = placement.deletesForever || f.canDelete,
            canSpam = placement.inJunk || f.canSpam,
            logoAllowed = logoAllowed(m.fromEmail, m.draft, f.ownMessage, f.own, trust),
        )
    }

    private fun bodyOf(body: Body?, remote: Boolean, error: ErrorText?, gone: Boolean): BodyState {
        if (body == null) return if (error != null) BodyState.Failed(error) else BodyState.Loading
        val html = body.html?.takeIf { it.isNotBlank() }
        val text = body.text?.takeIf { it.isNotBlank() }
        return when {
            html == null && text == null -> BodyState.Empty
            gone -> BodyState.Text(text ?: HtmlSanitizer.text(html.orEmpty()))
            else -> {
                val source = html ?: HtmlSanitizer.plainToHtml(text.orEmpty())
                BodyState.Html(HtmlSanitizer.sanitize(source, remote), remote)
            }
        }
    }

    private companion object {
        /** DESIGN §11.2: inline images up to 10 MB. */
        const val MAX_INLINE_BYTES = 10L * 1024 * 1024

        /** Messages whose WebView renderer died in this process; shown as text (privacy PS7). */
        val renderCrashes: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    }
}
