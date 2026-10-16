package app.plico.ui.compose

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.plico.PlicoApp
import app.plico.R
import app.plico.account.Providers
import app.plico.data.Contact
import app.plico.data.DraftAttachment
import app.plico.data.DraftState
import app.plico.mail.mime.Address
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Short messages the screen shows in a snackbar. */
internal enum class Notice(@StringRes val text: Int) {
    NO_RECIPIENT(R.string.compose_no_recipient),
    INVALID_RECIPIENTS(R.string.compose_invalid_recipients),
    WAIT_FOR_ATTACHMENTS(R.string.compose_wait_attachments),
    TOO_LARGE(R.string.compose_send_too_large),
    NOT_SENDABLE(R.string.compose_not_sendable),
    ATTACH_FAILED(R.string.compose_attach_failed),
    NO_PICKER(R.string.compose_no_picker),
}

/** A file that is being copied into the draft. */
internal data class PendingAttachment(val key: Long, val name: String?)

/** Contact suggestions for the recipient field that is being typed in. */
internal data class Suggestions(val field: RecipientField?, val contacts: List<Contact>) {
    companion object {
        val NONE = Suggestions(null, emptyList())
    }
}

/**
 * State and actions of one compose screen (DESIGN §13, §7.6). Scoped to its back-stack entry, so
 * [onCleared] is the moment the user leaves: the draft is then saved and its server copy queued, or deleted
 * when it is empty. Every write to the draft runs in order under one lock, off the main thread.
 */
internal class ComposeViewModel(private val app: PlicoApp, private val draftId: Long) : ViewModel() {
    enum class Phase { LOADING, EDITING, QUEUED, GONE }

    var phase by mutableStateOf(Phase.LOADING)
        private set
    var senders by mutableStateOf(emptyList<SenderOption>())
        private set
    var from by mutableStateOf<SenderOption?>(null)
        private set
    val to = RecipientField()
    val cc = RecipientField()
    val bcc = RecipientField()
    var showCcBcc by mutableStateOf(false)
        private set
    var subject by mutableStateOf("")
        private set
    var body by mutableStateOf(TextFieldValue())
        private set
    var attachments by mutableStateOf(emptyList<DraftAttachment>())
        private set
    val adding = mutableStateListOf<PendingAttachment>()

    /** Files that are being added again in the other quality. */
    val replacing = mutableStateListOf<String>()

    /** The reason of an earlier failed send. */
    var failure by mutableStateOf<String?>(null)
        private set
    var suggestions by mutableStateOf(Suggestions.NONE)
        private set

    /** True from the Send tap until the draft is queued or refused. */
    var sending by mutableStateOf(false)
        private set
    var undoSeconds = 0
        private set

    val notices: Flow<Notice> get() = noticeChannel.receiveAsFlow()

    /** Estimated size on the wire (DESIGN §13). */
    val encodedSize by derivedStateOf {
        val text = DraftRules.utf8Length(subject) + DraftRules.utf8Length(body.text)
        DraftRules.encodedSize(text + attachments.sumOf { it.size })
    }
    val sizeLimit by derivedStateOf { from?.let { Providers.of(it.provider).maxMessageBytes } }
    val tooLarge by derivedStateOf { sizeLimit?.let { encodedSize > it } == true }

    /** Picker URIs of the files added in this session, for "Original quality". Never stored. */
    private val sources = mutableStateMapOf<String, Source>()
    private val removed = HashSet<String>()
    private val noticeChannel = Channel<Notice>(Channel.BUFFERED)
    private val lock = Mutex()
    private var edited = false
    private var closed = false
    private var autosave: Job? = null
    private var suggest: Job? = null
    private var nextKey = 0L

    private data class Source(val uri: Uri, val original: Boolean)

    init {
        viewModelScope.launch { load() }
    }

    val editing: Boolean get() = phase == Phase.EDITING && !sending

    /** True for a re-encoded image whose source is known in this session. */
    fun canChooseQuality(a: DraftAttachment): Boolean = a.file in sources && a.mime.lowercase() in REENCODED

    fun isOriginal(a: DraftAttachment): Boolean = sources[a.file]?.original == true

    fun selectFrom(option: SenderOption) {
        if (!editing || option == from) return
        from = option
        changed()
    }

    fun expandCcBcc() {
        showCcBcc = true
    }

    fun editSubject(value: String) {
        if (!editing) return
        val clean = value.replace('\n', ' ').replace('\r', ' ')
        if (clean == subject) return
        subject = clean
        changed()
    }

    fun editBody(value: TextFieldValue) {
        val textChanged = value.text != body.text
        if (textChanged && !editing) return
        body = value
        if (textChanged) changed()
    }

    fun type(field: RecipientField, value: String) {
        if (!editing) return
        if (field.type(value)) changed()
        suggestFor(field)
    }

    /** Focus left [field]: its text becomes chips. The stored content is the same, so nothing is saved. */
    fun commit(field: RecipientField) {
        field.commit()
        if (suggestions.field === field) suggestions = Suggestions.NONE
    }

    fun pick(field: RecipientField, contact: Contact) {
        if (!editing) return
        field.pick(Recipients.of(Address(contact.name.ifBlank { null }, contact.email)))
        suggestions = Suggestions.NONE
        changed()
    }

    fun remove(field: RecipientField, recipient: Recipient) {
        if (!editing) return
        field.remove(recipient)
        changed()
    }

    /** Puts [recipient] back into the text of [field] for editing. */
    fun edit(field: RecipientField, recipient: Recipient) {
        if (!editing) return
        field.edit(recipient)
        suggestFor(field)
    }

    fun removeLast(field: RecipientField) {
        if (editing && field.removeLast()) changed()
    }

    fun attach(uris: List<Uri>) {
        if (!editing) return
        for (uri in uris) {
            val key = nextKey++
            viewModelScope.launch {
                val pending = PendingAttachment(key, withContext(Dispatchers.IO) { displayName(uri) })
                adding += pending
                try {
                    val added = write { app.mail.addAttachment(draftId, uri) }
                    sources[added.file] = Source(uri, original = false)
                    if (attachments.none { it.file == added.file }) attachments = attachments + added
                    changed()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed("attach", e)
                    noticeChannel.trySend(Notice.ATTACH_FAILED)
                } finally {
                    adding -= pending
                }
            }
        }
    }

    fun removeAttachment(a: DraftAttachment) {
        if (!editing) return
        attachments = attachments.filterNot { it.file == a.file }
        removed += a.file
        sources -= a.file
        // Not tied to this screen: the removal must happen even when the user leaves at once.
        background { app.mail.removeAttachment(draftId, a.file) }
        changed()
    }

    /** Adds the file again from its source, re-encoded or original (DESIGN §10), and removes the old copy. */
    fun setOriginalQuality(a: DraftAttachment, original: Boolean) {
        val source = sources[a.file] ?: return
        if (!editing || source.original == original || a.file in replacing) return
        replacing += a.file
        viewModelScope.launch {
            try {
                val added = write {
                    app.mail.addAttachment(draftId, source.uri, original).also {
                        app.mail.removeAttachment(draftId, a.file)
                    }
                }
                sources -= a.file
                sources[added.file] = source.copy(original = original)
                attachments = attachments.map { if (it.file == a.file) added else it }
                changed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("quality", e)
                noticeChannel.trySend(Notice.ATTACH_FAILED)
            } finally {
                replacing -= a.file
            }
        }
    }

    fun notice(notice: Notice) {
        noticeChannel.trySend(notice)
    }

    /** Checks the recipients, saves and queues the send; the screen then offers Undo. */
    fun send() {
        if (!editing) return
        listOf(to, cc, bcc).forEach(::commit)
        val all = to.chips + cc.chips + bcc.chips
        val problem = when {
            all.isEmpty() -> Notice.NO_RECIPIENT
            all.any { it is Recipient.Invalid } -> Notice.INVALID_RECIPIENTS
            adding.isNotEmpty() || replacing.isNotEmpty() -> Notice.WAIT_FOR_ATTACHMENTS
            else -> null
        }
        if (problem != null) {
            noticeChannel.trySend(problem)
            return
        }
        autosave?.cancel()
        sending = true
        val content = content()
        // Detached: a send the user tapped must not stop because the screen closes.
        val result = detached.async {
            try {
                write {
                    save(content)
                    app.mail.send(draftId)
                }
            } catch (e: Exception) {
                failed("send", e)
                false
            }
        }
        viewModelScope.launch {
            val queued = result.await()
            sending = false
            if (queued) {
                phase = Phase.QUEUED
                failure = null
            } else {
                noticeChannel.trySend(if (tooLarge) Notice.TOO_LARGE else Notice.NOT_SENDABLE)
            }
        }
    }

    /** Undo send: back to editing, or [onTooLate] when sending has started. */
    fun undoSend(onTooLate: () -> Unit) {
        if (phase != Phase.QUEUED) return
        viewModelScope.launch {
            val back = write {
                app.mail.cancelSend(draftId)
                app.drafts.get(draftId)?.state == DraftState.EDITING
            }
            if (back) phase = Phase.EDITING else onTooLate()
        }
    }

    /** Deletes the draft; the caller closes the screen. */
    fun discard() {
        if (phase != Phase.EDITING) return
        closed = true
        autosave?.cancel()
        background { app.mail.discardDraft(draftId) }
    }

    /** The activity stops (DESIGN §7.6): save locally now, without the server copy. */
    fun saveNow() {
        if (phase != Phase.EDITING || closed || !edited) return
        autosave?.cancel()
        val content = content()
        autosave = viewModelScope.launch { safeSave(content) }
    }

    override fun onCleared() {
        if (phase != Phase.EDITING || closed || sending) return
        val content = content()
        val empty = content.isEmpty() && attachments.isEmpty() && adding.isEmpty()
        when (DraftRules.leave(edited, empty)) {
            DraftRules.Leave.KEEP -> Unit
            DraftRules.Leave.DISCARD -> background { app.mail.discardDraft(draftId) }
            DraftRules.Leave.SAVE -> {
                Toast.makeText(app, R.string.compose_draft_saved, Toast.LENGTH_SHORT).show()
                background {
                    save(content)
                    app.mail.leaveDraft(draftId)
                }
            }
        }
    }

    private suspend fun load() {
        val data = withContext(Dispatchers.IO) {
            val draft = app.drafts.get(draftId)
            val options = DraftRules.senderOptions(app.accounts.all(), app.accounts::identities)
            Triple(draft, options, app.prefs.undoSendSeconds)
        }
        val (draft, options, undo) = data
        val sender = draft?.let { DraftRules.pickSender(options, it.accountId, it.fromEmail) }
        if (draft == null || sender == null || draft.state != DraftState.EDITING && draft.state != DraftState.FAILED) {
            phase = Phase.GONE
            return
        }
        senders = if (sender in options) options else options + sender
        from = sender
        to.set(Recipients.parse(draft.toList))
        cc.set(Recipients.parse(draft.ccList))
        bcc.set(Recipients.parse(draft.bccList))
        showCcBcc = cc.chips.isNotEmpty() || bcc.chips.isNotEmpty()
        subject = draft.subject
        body = TextFieldValue(draft.body, TextRange.Zero)
        attachments = draft.attachments
        failure = draft.error?.takeIf { draft.state == DraftState.FAILED }
        undoSeconds = undo
        phase = Phase.EDITING
        app.drafts.observe(draftId).collect { row ->
            when {
                // A removal waits for the lock; until then the row still lists the file.
                row != null -> attachments = row.attachments.filterNot { it.file in removed }
                phase == Phase.EDITING && !closed -> phase = Phase.GONE
            }
        }
    }

    private fun suggestFor(field: RecipientField) {
        suggest?.cancel()
        val query = field.text.trim()
        if (query.isEmpty()) {
            suggestions = Suggestions.NONE
            return
        }
        suggest = viewModelScope.launch {
            delay(SUGGEST_DELAY_MS)
            val found = withContext(Dispatchers.IO) { app.contacts.suggest(query, SUGGEST_LIMIT * 2) }
            val taken = (to.chips + cc.chips + bcc.chips).mapNotNullTo(HashSet()) {
                (it as? Recipient.Valid)?.address?.email?.lowercase()
            }
            suggestions = Suggestions(field, found.filter { it.email.lowercase() !in taken }.take(SUGGEST_LIMIT))
        }
    }

    private fun changed() {
        edited = true
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            safeSave(content())
        }
    }

    private fun content() = DraftContent(
        from = from?.email.orEmpty(),
        to = Recipients.format(to.all()),
        cc = Recipients.format(cc.all()),
        bcc = Recipients.format(bcc.all()),
        subject = subject,
        body = body.text,
    )

    private suspend fun safeSave(content: DraftContent) {
        try {
            write { save(content) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed("save", e)
        }
    }

    /** Blocking; call it inside [write]. */
    private fun save(c: DraftContent) = app.mail.saveDraft(draftId, c.from, c.to, c.cc, c.bcc, c.subject, c.body)

    private suspend fun <T> write(block: suspend () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    /** Work that must finish after the screen is gone, still in order with the other writes. */
    private fun background(block: suspend () -> Unit) {
        detached.launch {
            try {
                write(block)
            } catch (e: Exception) {
                failed("background", e)
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private data class DraftContent(
        val from: String,
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
    ) {
        fun isEmpty() = to.isBlank() && cc.isBlank() && bcc.isBlank() && subject.isBlank() &&
            DraftRules.isBlankBody(body)
    }

    private companion object {
        const val TAG = "Plico"
        const val AUTOSAVE_DELAY_MS = 1_500L
        const val SUGGEST_DELAY_MS = 150L
        const val SUGGEST_LIMIT = 5

        /** Types that DESIGN §10 re-encodes (and strips of metadata) unless "Original quality" is on. */
        val REENCODED = setOf("image/jpeg", "image/heic", "image/heif", "image/png", "image/webp")

        /** Outlives the screen; draft writes after leaving run here. */
        val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Privacy (DESIGN §12): the exception class only, never its message. */
        fun failed(what: String, e: Exception) {
            Log.w(TAG, "Compose $what failed: ${e.javaClass.simpleName}")
        }
    }
}
