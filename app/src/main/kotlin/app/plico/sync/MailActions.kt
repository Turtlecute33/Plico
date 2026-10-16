package app.plico.sync

import android.net.Uri
import app.plico.PlicoApp
import app.plico.R
import app.plico.account.Providers
import app.plico.account.credentialsFor
import app.plico.account.forgetCredentials
import app.plico.data.Account
import app.plico.data.Attachment
import app.plico.data.DraftAttachment
import app.plico.data.DraftState
import app.plico.data.Files
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MailView
import app.plico.data.Message
import app.plico.data.MessageDetail
import app.plico.data.NewAccount
import app.plico.data.NewDraft
import app.plico.data.NewFolder
import app.plico.data.NewOp
import app.plico.data.Tables
import app.plico.data.list
import app.plico.html.HtmlSanitizer
import app.plico.html.RemoteFetcher
import app.plico.html.SenderCheck
import app.plico.mail.imap.UidSet
import app.plico.mail.mime.Address
import app.plico.mail.mime.Addresses
import app.plico.mail.mime.HeaderSafety
import app.plico.mail.mime.TransferDecoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Token for the Undo snackbar of an undoable action. [memberships] maps each Gmail label view that the action
 * changed to the ids of [messageIds] that were in it before.
 */
data class Undo(
    val opIds: List<Long>,
    val messageIds: List<Long>,
    val memberships: Map<Long, Set<Long>> = emptyMap(),
)

/** Buttons on a new-mail notification (DESIGN §9). */
enum class NotificationAction { ARCHIVE, DELETE, MARK_READ }

/** Prefill for a new draft (share and mailto intents). */
data class DraftPrefill(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
)

/** A change the server refused (Plico undid it locally) or a send that failed; [text] is for a snackbar. */
data class ActionFailure(val accountId: Long, val text: String)

/** An action that cannot run; [message] is a text for the user. */
class ActionError(message: String) : Exception(message)

/** What [MailActions] needs from the running app: sessions, notifications, push. */
internal interface ActionHost {
    fun kick(accountId: Long)

    /** Messages became seen, archived or deleted: refresh the notifications of the account. */
    fun messagesChanged(accountId: Long)

    /** Runs [block] on the account's session loop with a connected client. */
    suspend fun <T> request(accountId: Long, block: suspend OpContext.() -> T): T

    val undoSendSeconds: Int

    /** Accounts were added or their settings changed: channels, push modes. */
    fun accountsChanged()

    /** Stops the session, revokes the sign-in, purges data and files, deletes the channel. */
    suspend fun removeAccount(account: Account)
}

/**
 * The UI's single entry point for changes (DESIGN.md §7.4–§7.8, §10, §13). Every action changes the database
 * first and queues an op, then kicks the account's session; reads use the DAOs directly.
 *
 * The functions that are not `suspend` write the database and block: call them off the main thread.
 */
class MailActions internal constructor(private val store: OpStore, private val host: ActionHost) {
    constructor(app: PlicoApp) : this(OpStore.of(app), AppHost(app))

    /** Changes the server refused and sends that failed, for a snackbar. */
    val failures: SharedFlow<ActionFailure> get() = Ops.failures

    // ---- List actions (DESIGN §7.3, §7.4)

    /** Gmail removes `\Inbox`; Riseup moves to the archive folder (created on first use). */
    fun archive(ids: List<Long>, fromView: MailView): Undo? = archive(ids, store.now() + UNDO_MS)

    /** To Trash; inside Trash/Spam use [deleteForever] (the UI confirms). */
    fun delete(ids: List<Long>): Undo? = delete(ids, store.now() + UNDO_MS)

    /** `\Deleted` + `UID EXPUNGE`. Gmail: only for messages in Trash or Spam. */
    fun deleteForever(ids: List<Long>) {
        apply(ids) {
            val doomed = if (account.isGmail) messages.filter { inTrashOrJunk(it) } else messages
            if (doomed.isEmpty()) return@apply emptyList()
            val rowIds = doomed.map { it.id }
            store.messages.setHidden(rowIds, true)
            listOf(Change(queue(account.id, rowIds, OpArg.Expunge(), store.now()), rowIds))
        }
    }

    fun markSeen(ids: List<Long>, seen: Boolean) {
        apply(ids) {
            listOfNotNull(flag(account, messages.filter { it.seen != seen }, SEEN, seen) { store.messages.setSeen(it, seen) })
        }
    }

    fun star(ids: List<Long>, on: Boolean) {
        apply(ids) {
            listOfNotNull(flag(account, messages.filter { it.flagged != on }, FLAGGED, on) { store.messages.setFlagged(it, on) })
        }
    }

    /** Gmail only. */
    fun important(ids: List<Long>, on: Boolean) {
        apply(ids) {
            if (!account.isGmail) return@apply emptyList()
            val rows = messages.filter { role(it) == FolderRole.ALL }
            val view = label(FolderRole.IMPORTANT)
            val change = when {
                view == null && rows.isNotEmpty() -> {
                    // No view row yet: only the server changes.
                    val arg = if (on) OpArg.Labels(listOf(IMPORTANT), emptyList()) else OpArg.Labels(emptyList(), listOf(IMPORTANT))
                    Change(queue(account.id, rows.map { it.id }, arg, store.now()), rows.map { it.id })
                }
                view == null -> null
                on -> relabel(account, rows, listOf(view), emptyList(), store.now())
                else -> relabel(account, rows, emptyList(), listOf(view), store.now())
            }
            listOfNotNull(change)
        }
    }

    /** Riseup: move to the mailbox. Gmail: label move from the view's label (DESIGN §7.3). */
    fun moveTo(ids: List<Long>, folderId: Long, fromView: MailView): Undo? {
        val target = store.folders.get(folderId) ?: return null
        val runAt = store.now() + UNDO_MS
        return undoable(ids) {
            when {
                account.id != target.accountId -> emptyList()
                !account.isGmail || target.kind == FolderKind.MAILBOX && isTrashOrJunk(target) -> {
                    val rows = messages.filter { it.folderId != target.id }
                    if (target.kind != FolderKind.MAILBOX) emptyList()
                    else listOfNotNull(move(account, rows, Target(target.path, target.role), runAt))
                }
                else -> {
                    // MOVE only out of Trash and Spam; All Mail rows change labels (protocol PS8).
                    val path = if (target.kind == FolderKind.LABEL && target.role == FolderRole.INBOX) INBOX else target.path
                    val restored = move(account, messages.filter { inTrashOrJunk(it) }, Target(path, null), runAt)
                    val add = if (target.kind == FolderKind.LABEL) listOf(target) else emptyList()
                    val remove = listOfNotNull(viewLabel(fromView)?.takeIf { it.id != target.id })
                    val relabeled = relabel(account, messages.filter { role(it) == FolderRole.ALL }, add, remove, runAt)
                    listOfNotNull(restored, relabeled)
                }
            }
        }
    }

    /** Gmail labels by label-view folder ids. */
    fun setLabels(ids: List<Long>, add: Set<Long>, remove: Set<Long>) {
        apply(ids) {
            if (!account.isGmail) return@apply emptyList()
            val adds = add.mapNotNull { folders[it] }.filter { it.kind == FolderKind.LABEL }
            val removes = (remove - add).mapNotNull { folders[it] }.filter { it.kind == FolderKind.LABEL }
            listOfNotNull(relabel(account, messages.filter { role(it) == FolderRole.ALL }, adds, removes, store.now()))
        }
    }

    /** Spam: MOVE to Spam (Riseup creates exactly "Spam"). Not spam: MOVE from Spam to INBOX. */
    fun spam(ids: List<Long>, isSpam: Boolean): Undo? {
        val runAt = store.now() + UNDO_MS
        return undoable(ids) {
            if (isSpam) {
                val junk = roleTarget(FolderRole.JUNK, if (account.isGmail) null else SPAM) ?: return@undoable emptyList()
                listOfNotNull(move(account, messages.filter { role(it) != FolderRole.JUNK && path(it) != junk.path }, junk, runAt))
            } else {
                val inbox = if (account.isGmail) Target(INBOX, FolderRole.INBOX) else roleTarget(FolderRole.INBOX, INBOX)
                listOfNotNull(inbox?.let { move(account, messages.filter { m -> role(m) == FolderRole.JUNK }, it, runAt) })
            }
        }
    }

    /** Empty Trash or Spam: every message up to the newest stored one, so that newer mail stays. */
    fun emptyFolder(folderId: Long) {
        val folder = store.folders.get(folderId) ?: return
        if (folder.kind != FolderKind.MAILBOX || !isTrashOrJunk(folder)) return
        val uids = store.messages.uids(folder.id)
        val upTo = uids.lastOrNull() ?: return
        store.db.tx(Tables.MESSAGE, Tables.OP) {
            store.messages.setHidden(store.messages.idsByUid(folder.id, uids).values, true)
            queue(folder.accountId, emptyList(), OpArg.Expunge(folder.id, upTo), store.now())
        }
        host.messagesChanged(folder.accountId)
        host.kick(folder.accountId)
    }

    /** Deletes the ops of [undo] that have not run and restores their rows. */
    fun undo(undo: Undo) {
        val accounts = store.db.tx(Tables.MESSAGE, Tables.MESSAGE_FOLDER, Tables.OP) {
            val pending = undo.opIds.mapNotNull(store.ops::get)
            pending.forEach { store.ops.delete(it.id) }
            val ids = pending.flatMapTo(HashSet()) { it.messageIds }
            store.messages.setHidden(ids, false)
            for ((label, members) in undo.memberships) {
                store.messages.addToFolder(members.filter { it in ids }, label)
                store.messages.removeFromFolder(ids - members, label)
            }
            pending.map { it.accountId }.distinct()
        }
        accounts.forEach(host::messagesChanged)
    }

    /** A notification button: same as the list action but with run_at = now (no undo), then kick. */
    fun notificationAction(messageId: Long, action: NotificationAction) {
        val ids = listOf(messageId)
        when (action) {
            NotificationAction.ARCHIVE -> archive(ids, store.now())
            NotificationAction.DELETE -> delete(ids, store.now())
            NotificationAction.MARK_READ -> markSeen(ids, true)
        }
    }

    // ---- Folders

    /** Online: CREATE on the server (Gmail: a label), then a folder row. */
    suspend fun createFolder(accountId: Long, name: String): Folder {
        val path = HeaderSafety.clean(name)
        if (path.isEmpty()) throw ActionError(store.text(R.string.ops_error_folder_name))
        return host.request(accountId) {
            client.create(path)
            val delimiter = if (account.isGmail) "/" else store.folders.byAccount(account.id).firstNotNullOfOrNull { it.delimiter }
            val kind = if (account.isGmail) FolderKind.LABEL else FolderKind.MAILBOX
            val leaf = if (delimiter.isNullOrEmpty()) path else path.substringAfterLast(delimiter)
            store.folders.get(store.folders.upsert(NewFolder(account.id, kind, path, leaf, null, delimiter)))!!
        }
    }

    fun setFolderNotify(folderId: Long, notify: Boolean): Unit = store.folders.setNotify(folderId, notify)

    /** Drops the rows of a guarded folder and resyncs it (DESIGN §7.2 step 6). */
    fun resyncFolder(folderId: Long) {
        val folder = store.folders.get(folderId) ?: return
        store.folders.resync(folderId)
        host.messagesChanged(folder.accountId)
        host.kick(folder.accountId)
    }

    // ---- Online reads

    suspend fun loadBody(messageId: Long) {
        val message = withContext(Dispatchers.IO) { store.messages.get(messageId) } ?: return
        if (message.hasBody) return
        host.request(message.accountId) { Bodies.fetch(this, listOf(messageId)) }
    }

    suspend fun loadAttachment(attachmentId: Long, onProgress: (Float) -> Unit = {}): File {
        val (message, cached) = withContext(Dispatchers.IO) {
            val attachment = store.messages.attachment(attachmentId) ?: throw gone()
            val message = store.messages.get(attachment.messageId) ?: throw gone()
            message to store.files.attachment(message.id, attachment.id)
        }
        if (cached.isFile) return cached
        return host.request(message.accountId) { Attachments.load(this, attachmentId, onProgress) }
    }

    /** Raw header block for "View headers" (BODY.PEEK[HEADER], not stored). */
    suspend fun fetchHeaders(messageId: Long): String {
        val message = withContext(Dispatchers.IO) { store.messages.get(messageId) } ?: throw gone()
        return host.request(message.accountId) {
            val folder = store.folders.get(message.folderId) ?: throw gone()
            Ops.selectChecked(this, folder)
            var header: ByteArray? = null
            client.uidFetch(UidSet.of(message.uid), "BODY.PEEK[HEADER]") { item ->
                if (item.uid == message.uid) item.sections["HEADER"]?.let { header = it }
            }
            TransferDecoding.text(header ?: throw gone(), null)
        }
    }

    /** Returns the number of hits. Riseup searches [folderId] (INBOX for the unified view), Gmail All Mail. */
    suspend fun searchServer(query: String, accountId: Long?, folderId: Long?): Int {
        val (folder, accounts) = withContext(Dispatchers.IO) {
            val folder = folderId?.let(store.folders::get)
            folder to (folder?.let { listOf(it.accountId) } ?: accountId?.let { listOf(it) } ?: allAccounts().map { it.id })
        }
        return forAll(accounts) { id ->
            host.request(id) { Search.server(this, query, folder?.takeIf { it.accountId == id }).size }
        }.sum()
    }

    /** Pull to refresh / folder opened: syncs the mailboxes of the view now. */
    suspend fun refresh(view: MailView) {
        val mailboxes = withContext(Dispatchers.IO) {
            when (view) {
                is MailView.Search -> emptyList()
                is MailView.InFolder -> listOfNotNull(
                    store.folders.get(view.folderId)?.let { if (it.kind == FolderKind.MAILBOX) it else mainMailbox(it.accountId, true) },
                )
                is MailView.Starred -> allAccounts().filter { view.accountId == null || it.id == view.accountId }
                    .mapNotNull { mainMailbox(it.id, it.isGmail) }
                MailView.Unified -> allAccounts().mapNotNull { mainMailbox(it.id, it.isGmail) }
            }
        }
        forAll(mailboxes) { folder -> host.request(folder.accountId) { syncFolder(folder) } }
    }

    /** RFC 8058 one-click POST when allowed (DESIGN §11.4); false when not possible. */
    suspend fun unsubscribeOneClick(messageId: Long): Boolean = withContext(Dispatchers.IO) {
        val message = store.messages.get(messageId) ?: return@withContext false
        val account = store.accounts.get(message.accountId) ?: return@withContext false
        val inJunk = store.folders.get(message.folderId)?.role == FolderRole.JUNK
        val url = unsubscribeUrl(message.unsubscribe)
        val allowed = SenderCheck.trust(account.isGmail, message.auth, inJunk).oneClickUnsubscribe
        url != null && message.unsubscribePost && allowed && RemoteFetcher.unsubscribe(url)
    }

    // ---- Drafts (DESIGN §7.5, §7.6)

    suspend fun newDraft(accountId: Long?, prefill: DraftPrefill = DraftPrefill()): Long = withContext(Dispatchers.IO) {
        val account = (accountId?.let(store.accounts::get) ?: allAccounts().firstOrNull())
            ?: throw ActionError(store.text(R.string.ops_error_no_account))
        store.drafts.create(
            NewDraft(
                accountId = account.id,
                fromEmail = account.email,
                toList = addressList(prefill.to),
                ccList = addressList(prefill.cc),
                bccList = addressList(prefill.bcc),
                subject = HeaderSafety.clean(prefill.subject),
                body = ReplyRules.newBody(prefill.body, account.signature),
            ),
        )
    }

    /** A reply draft (simplicity EM16): From, recipients, "Re:" subject, attribution and quote. */
    suspend fun reply(messageId: Long, all: Boolean): Long {
        val detail = detail(messageId, needBody = false)
        return withContext(Dispatchers.IO) {
            val message = detail.message
            val (account, identities, source) = sourceOf(detail)
            val recipients = ReplyRules.recipients(source, own(account, identities), all)
            val attribution = ReplyRules.attribution(message.date, message.fromName.ifBlank { message.fromEmail })
            store.drafts.create(
                NewDraft(
                    accountId = account.id,
                    fromEmail = ReplyRules.from(source, account.email, identities),
                    toList = HeaderParser.format(recipients.to),
                    ccList = HeaderParser.format(recipients.cc),
                    subject = ReplyRules.replySubject(message.subject),
                    body = ReplyRules.replyBody(account.signature, attribution, bodyText(detail)),
                    inReplyTo = message.messageId,
                    refs = ReplyRules.references(message.refs, message.inReplyTo, message.messageId),
                    sourceMessage = message.id,
                ),
            )
        }
    }

    /** Downloads the original attachments into the draft (DESIGN §7.8, protocol PS24). */
    suspend fun forward(messageId: Long): Long {
        val detail = detail(messageId, needBody = true)
        val draftId = withContext(Dispatchers.IO) {
            val message = detail.message
            val (account, identities, source) = sourceOf(detail)
            store.drafts.create(
                NewDraft(
                    accountId = account.id,
                    fromEmail = ReplyRules.from(source, account.email, identities),
                    subject = ReplyRules.forwardSubject(message.subject),
                    body = ReplyRules.forwardBody(
                        account.signature, source.from, message.date, message.subject, source.to, source.cc,
                        bodyText(detail),
                    ),
                ),
            )
        }
        copyAttachments(draftId, detail.attachments)
        return draftId
    }

    /** Copies a server draft (a row in the Drafts view) into a local draft with its Message-ID. */
    suspend fun editServerDraft(messageId: Long): Long {
        val existing = withContext(Dispatchers.IO) {
            store.messages.get(messageId)?.let { m -> m.messageId?.let { store.drafts.byMessageId(m.accountId, it) } }
        }
        if (existing != null) return existing.id
        val detail = detail(messageId, needBody = true)
        val draftId = withContext(Dispatchers.IO) {
            val message = detail.message
            val (account, identities) = sourceOf(detail)
            val id = store.drafts.create(
                NewDraft(
                    accountId = account.id,
                    fromEmail = (identities + account.email).firstOrNull { it.equals(message.fromEmail, ignoreCase = true) }
                        ?: account.email,
                    toList = message.toList,
                    ccList = message.ccList,
                    bccList = message.bccList,
                    subject = message.subject,
                    body = bodyText(detail),
                    inReplyTo = message.inReplyTo,
                    refs = message.refs,
                    sourceMessage = message.inReplyTo?.let { store.messages.byMessageId(account.id, it).firstOrNull()?.id },
                    messageId = message.messageId,
                ),
            )
            if (message.messageId != null) store.drafts.setOnServer(id, true)
            id
        }
        copyAttachments(draftId, detail.attachments)
        return draftId
    }

    /** Saves the draft locally; a new From of another account moves the draft to that account. */
    fun saveDraft(draftId: Long, fromEmail: String, to: String, cc: String, bcc: String, subject: String, body: String) {
        val draft = store.drafts.get(draftId) ?: return
        val accountId = accountOf(fromEmail) ?: draft.accountId
        val changed = draft.copy(
            accountId = accountId, fromEmail = fromEmail, toList = to, ccList = cc, bccList = bcc, subject = subject,
            body = body,
        )
        if (changed == draft) return
        val moved = accountId != draft.accountId
        val saved = store.db.tx(Tables.DRAFT, Tables.OP) {
            val saved = store.drafts.update(changed)
            // The copy on the old account's server goes.
            if (saved && moved && (Drafts.dropUploads(store, draft.accountId, draftId) || draft.onServer)) {
                Drafts.queueDelete(store, draft.accountId, draft.messageId)
                store.drafts.setOnServer(draftId, false)
            }
            saved
        }
        if (saved && moved) host.kick(draft.accountId)
    }

    /**
     * Compose closed after an edit: queue the server APPEND (DESIGN §7.6). The caller decides that the user
     * edited the draft, so that a shared draft stays local until then. An empty draft is discarded instead.
     */
    fun leaveDraft(draftId: Long) {
        val draft = store.drafts.get(draftId) ?: return
        if (draft.state != DraftState.EDITING && draft.state != DraftState.FAILED) return
        val account = store.accounts.get(draft.accountId) ?: return
        val empty = draft.toList.isBlank() && draft.ccList.isBlank() && draft.bccList.isBlank() &&
            draft.subject.isBlank() && draft.attachments.isEmpty() &&
            draft.body.trim() == ReplyRules.newBody("", account.signature).trim()
        if (empty && !draft.onServer) {
            discardDraft(draftId)
        } else {
            Drafts.queueUpload(store, draft.accountId, draftId)
            host.kick(draft.accountId)
        }
    }

    fun discardDraft(draftId: Long) {
        val draft = store.drafts.get(draftId) ?: return
        if (draft.state == DraftState.QUEUED && !store.drafts.unqueue(draftId)) return
        if (draft.state == DraftState.SENDING || draft.state == DraftState.SENT) return
        val deleteOnServer = store.db.tx(Tables.DRAFT, Tables.OP) {
            val uploading = Drafts.dropUploads(store, draft.accountId, draftId)
            val onServer = store.drafts.get(draftId)?.onServer == true
            store.drafts.delete(draftId)
            (uploading || onServer).also { if (it) Drafts.queueDelete(store, draft.accountId, draft.messageId) }
        }
        store.files.deleteOutbox(draftId)
        if (deleteOnServer) host.kick(draft.accountId)
    }

    /** Queues the send with the undo delay; returns false when the draft is not valid (no recipient, too large). */
    fun send(draftId: Long): Boolean {
        val draft = store.drafts.get(draftId) ?: return false
        val account = store.accounts.get(draft.accountId) ?: return false
        val recipients = DraftMime.addresses(draft)
        val valid = recipients.isNotEmpty() && (recipients.map { it.email } + draft.fromEmail).all(Addresses::isValidAddrSpec)
        if (!valid || DraftMime.estimatedSize(draft) > Providers.of(account.provider).maxMessageBytes) return false
        if (!store.drafts.queue(draftId, store.now() + host.undoSendSeconds * 1000L)) return false
        host.kick(account.id)
        return true
    }

    fun cancelSend(draftId: Long) {
        store.drafts.unqueue(draftId)
    }

    fun retrySend(draftId: Long) {
        val draft = store.drafts.get(draftId) ?: return
        if (draft.state == DraftState.FAILED && store.drafts.queue(draftId, store.now())) host.kick(draft.accountId)
    }

    /** Copies [uri] into the outbox (IncomingUris rules, image re-encoding per DESIGN §10). */
    suspend fun addAttachment(draftId: Long, uri: Uri, originalQuality: Boolean = false): DraftAttachment =
        withContext(Dispatchers.IO) { DraftAttachments.add(store, draftId, uri, originalQuality) }

    fun removeAttachment(draftId: Long, file: String) {
        val draft = store.drafts.get(draftId) ?: return
        val kept = draft.attachments.filterNot { it.file == file }
        if (kept.size == draft.attachments.size || !store.drafts.update(draft.copy(attachments = kept))) return
        Files.inside(store.files.outboxDir(draftId), file).delete()
    }

    // ---- Accounts

    /** Inserts a probed account (address in lower case), creates its channel, starts sync and push. */
    suspend fun addAccount(account: NewAccount): Long = withContext(Dispatchers.IO) {
        val id = store.accounts.insert(account.copy(email = account.email.lowercase(Locale.ROOT)))
        host.accountsChanged()
        host.kick(id)
        id
    }

    /** Revokes credentials, purges data and files, deletes the channel. */
    suspend fun removeAccount(accountId: Long) {
        val account = withContext(Dispatchers.IO) { store.accounts.get(accountId) } ?: return
        host.removeAccount(account)
    }

    /** Settings changed (push mode, body days, ...): reconfigure the session. */
    fun accountChanged(accountId: Long) {
        host.accountsChanged()
        host.kick(accountId)
    }

    // ---- Helpers

    /** A mailbox path and the role it gets when it must be created. */
    private data class Target(val path: String, val role: FolderRole?)

    private class Change(val opId: Long, val messageIds: List<Long>, val memberships: Map<Long, Set<Long>> = emptyMap())

    /** One account's part of an action: its rows and folders. */
    private inner class Rows(val account: Account, val messages: List<Message>, val folders: Map<Long, Folder>) {
        fun role(m: Message) = folders[m.folderId]?.role
        fun path(m: Message) = folders[m.folderId]?.path
        fun inTrashOrJunk(m: Message) = folders[m.folderId]?.let { isTrashOrJunk(it) } == true
        fun label(role: FolderRole) = folders.values.firstOrNull { it.kind == FolderKind.LABEL && it.role == role }

        /** The mailbox with [role], else a new one named [fallback] (created on first use), else null. */
        fun roleTarget(role: FolderRole, fallback: String?): Target? =
            folders.values.firstOrNull { it.kind == FolderKind.MAILBOX && it.role == role }?.let { Target(it.path, role) }
                ?: fallback?.let { Target(it, role) }

        /** Riseup (protocol PS5): the chosen folder, the \Archive mailbox, "Archive" or "Archives", else a new "Archive". */
        fun archiveTarget(): Target {
            val boxes = folders.values.filter { it.kind == FolderKind.MAILBOX }
            val folder = boxes.firstOrNull { it.id == account.archiveFolder }
                ?: boxes.firstOrNull { it.role == FolderRole.ARCHIVE }
                ?: boxes.firstOrNull { it.path.equals(ARCHIVE, true) || it.path.equals("Archives", true) }
            return Target(folder?.path ?: ARCHIVE, FolderRole.ARCHIVE)
        }

        /** The Gmail label a view shows (`\Inbox` for the inboxes); null for All Mail, Starred and search. */
        fun viewLabel(view: MailView): Folder? = when (view) {
            MailView.Unified -> label(FolderRole.INBOX)
            is MailView.InFolder -> folders[view.folderId]?.takeIf { it.kind == FolderKind.LABEL }
            else -> null
        }
    }

    private fun archive(ids: List<Long>, runAt: Long): Undo? = undoable(ids) {
        if (account.isGmail) {
            val inbox = label(FolderRole.INBOX) ?: return@undoable emptyList()
            val inInbox = members(inbox.id, messages.map { it.id })
            val rows = messages.filter { role(it) == FolderRole.ALL && it.id in inInbox }
            listOfNotNull(relabel(account, rows, emptyList(), listOf(inbox), runAt))
        } else {
            val target = archiveTarget()
            listOfNotNull(move(account, messages.filter { path(it) != target.path }, target, runAt))
        }
    }

    private fun delete(ids: List<Long>, runAt: Long): Undo? = undoable(ids) {
        val trash = roleTarget(FolderRole.TRASH, if (account.isGmail) null else TRASH) ?: return@undoable emptyList()
        listOfNotNull(move(account, messages.filter { path(it) != trash.path }, trash, runAt))
    }

    /** Runs [block] per account in one transaction, then refreshes notifications and kicks the session. */
    private fun apply(ids: List<Long>, block: Rows.() -> List<Change>): List<Change> {
        val changes = ArrayList<Change>()
        for ((accountId, messages) in ids.distinct().mapNotNull(store.messages::get).groupBy { it.accountId }) {
            val account = store.accounts.get(accountId) ?: continue
            val rows = Rows(account, messages, store.folders.byAccount(accountId).associateBy { it.id })
            val done = store.db.tx(Tables.MESSAGE, Tables.MESSAGE_FOLDER, Tables.OP) { rows.block() }
            if (done.isEmpty()) continue
            changes += done
            host.messagesChanged(accountId)
            host.kick(accountId)
        }
        return changes
    }

    private fun undoable(ids: List<Long>, block: Rows.() -> List<Change>): Undo? {
        val changes = apply(ids, block)
        if (changes.isEmpty()) return null
        return Undo(
            opIds = changes.map { it.opId },
            messageIds = changes.flatMap { it.messageIds },
            memberships = changes.fold(emptyMap()) { all, c -> all + c.memberships },
        )
    }

    /** Hides [messages] and queues their MOVE to [target]. */
    private fun move(account: Account, messages: List<Message>, target: Target, runAt: Long): Change? {
        if (messages.isEmpty()) return null
        val ids = messages.map { it.id }
        store.messages.setHidden(ids, true)
        return Change(queue(account.id, ids, OpArg.Move(target.path, target.role), runAt), ids)
    }

    /** Changes the label memberships of [messages] and queues the Gmail label op. */
    private fun relabel(account: Account, messages: List<Message>, add: List<Folder>, remove: List<Folder>, runAt: Long): Change? {
        if (messages.isEmpty() || add.isEmpty() && remove.isEmpty()) return null
        val ids = messages.map { it.id }
        val before = (add + remove).associate { it.id to members(it.id, ids) }
        add.forEach { store.messages.addToFolder(ids, it.id) }
        remove.forEach { store.messages.removeFromFolder(ids, it.id) }
        val arg = OpArg.Labels(add.map { it.path }, remove.map { it.path })
        return Change(queue(account.id, ids, arg, runAt), ids, before)
    }

    private fun flag(account: Account, messages: List<Message>, flag: String, on: Boolean, local: (List<Long>) -> Unit): Change? {
        if (messages.isEmpty()) return null
        val ids = messages.map { it.id }
        local(ids)
        return Change(queue(account.id, ids, OpArg.Flags(flag, on), store.now()), ids)
    }

    private fun queue(accountId: Long, ids: List<Long>, arg: OpArg, runAt: Long): Long =
        store.ops.insert(NewOp(accountId, arg.kind, ids, OpArg.encode(arg), runAt))

    /** The ids among [ids] that are in label view [folderId]. */
    private fun members(folderId: Long, ids: List<Long>): Set<Long> = ids.chunked(500).flatMapTo(HashSet()) { batch ->
        store.db.sql.list(
            "SELECT message_id FROM message_folder WHERE folder_id = ? AND message_id IN (${batch.joinToString(",")})",
            folderId,
        ) { it.getLong(0) }
    }

    private fun isTrashOrJunk(folder: Folder) = folder.role == FolderRole.TRASH || folder.role == FolderRole.JUNK

    private fun allAccounts(): List<Account> = store.accounts.all()

    /** The mailbox that holds a view's mail: Gmail All Mail, Riseup INBOX. */
    private fun mainMailbox(accountId: Long, gmail: Boolean): Folder? =
        store.mailbox(accountId, if (gmail) FolderRole.ALL else FolderRole.INBOX)

    /** The account whose address or identity is [email]. */
    private fun accountOf(email: String): Long? = store.accounts.byEmail(email)?.id
        ?: allAccounts().firstOrNull { a -> store.accounts.identities(a.id).any { it.email.equals(email, ignoreCase = true) } }?.id

    private fun own(account: Account, identities: List<String>): Set<String> =
        (identities + account.email).mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    /** The account, its identity addresses and the reply source of a message. */
    private fun sourceOf(detail: MessageDetail): Triple<Account, List<String>, ReplyRules.Source> {
        val message = detail.message
        val account = store.accounts.get(message.accountId) ?: throw gone()
        val identities = store.accounts.identities(account.id).map { it.email }
        val source = ReplyRules.Source(
            from = message.fromEmail.takeIf { it.isNotBlank() }?.let { Address(message.fromName.ifBlank { null }, it) },
            replyTo = Addresses.parseList(message.replyTo),
            to = Addresses.parseList(message.toList),
            cc = Addresses.parseList(message.ccList),
            fromSelf = message.fromEmail.lowercase(Locale.ROOT) in own(account, identities) ||
                detail.folders.any { it.role == FolderRole.SENT },
        )
        return Triple(account, identities, source)
    }

    /** The message with its body; loads a missing body first (waiting at most [BODY_WAIT_MS]). */
    private suspend fun detail(messageId: Long, needBody: Boolean): MessageDetail {
        val detail = store.messages.observeDetail(messageId).first() ?: throw gone()
        if (detail.body != null) return detail
        val loaded = try {
            withTimeoutOrNull(BODY_WAIT_MS) { loadBody(messageId) } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (needBody) throw e
            false
        }
        if (!loaded && needBody) throw ActionError(store.text(R.string.ops_error_offline))
        return store.messages.observeDetail(messageId).first() ?: throw gone()
    }

    private fun bodyText(detail: MessageDetail): String =
        detail.body?.let { it.text ?: it.html?.let(HtmlSanitizer::text) }.orEmpty()

    /** Downloads [attachments] (not the inline images) into the draft; on failure the draft is discarded. */
    private suspend fun copyAttachments(draftId: Long, attachments: List<Attachment>) {
        val originals = attachments.filterNot { it.inline }
        if (originals.isEmpty()) return
        try {
            val copies = originals.map { a ->
                val cached = loadAttachment(a.id)
                withContext(Dispatchers.IO) {
                    val file = store.files.newOutboxFile(draftId, a.name)
                    cached.copyTo(file, overwrite = true)
                    DraftAttachment(file.name, a.name, a.mime, file.length())
                }
            }
            withContext(Dispatchers.IO) {
                val draft = store.drafts.get(draftId) ?: throw gone()
                store.drafts.update(draft.copy(attachments = draft.attachments + copies))
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { discardDraft(draftId) }
            throw e
        }
    }

    /** Runs [block] for every item at once; waits for all, then throws the first failure. */
    private suspend fun <T, R> forAll(items: List<T>, block: suspend (T) -> R): List<R> = supervisorScope {
        items.map { async { block(it) } }.map { runCatching { it.await() } }.map { it.getOrThrow() }
    }

    private fun addressList(value: String) = HeaderParser.format(Addresses.parseList(value))

    private fun gone() = ActionError(store.text(R.string.ops_error_message_gone))

    private class AppHost(private val app: PlicoApp) : ActionHost {
        override fun kick(accountId: Long) = app.sessions[accountId].kick()

        override fun messagesChanged(accountId: Long) = app.notifier.refresh(accountId)

        override suspend fun <T> request(accountId: Long, block: suspend OpContext.() -> T): T =
            app.sessions[accountId].request { SessionOpContext(this).block() }

        override val undoSendSeconds: Int get() = app.prefs.undoSendSeconds

        override fun accountsChanged() {
            app.notifier.ensureChannels(app.accounts.all())
            app.push.onAccountsChanged()
        }

        override suspend fun removeAccount(account: Account) = withContext(NonCancellable + Dispatchers.IO) {
            // The session stops first, so that nothing writes rows of the account while it is purged.
            app.sessions.remove(account.id)
            try {
                // Reads the stored secret, so it runs before the purge.
                credentialsFor(app, account).revoke()
            } catch (_: Exception) {
                // Best effort: a missing secret or no network leaves the grant for the user to remove.
            }
            app.accounts.purge(account.id, app.files)
            app.notifier.cancelAccount(account.id)
            app.notifier.deleteChannel(account.id)
            forgetCredentials(account.id)
            app.push.onAccountsChanged()
        }
    }

    private companion object {
        /** The undo window of archive, delete, move and spam (DESIGN §7.4). */
        const val UNDO_MS = 4_000L
        const val BODY_WAIT_MS = 15_000L
        const val SEEN = "\\Seen"
        const val FLAGGED = "\\Flagged"
        const val IMPORTANT = "\\Important"
        const val INBOX = "INBOX"
        const val ARCHIVE = "Archive"
        const val TRASH = "Trash"
        const val SPAM = "Spam"
    }
}

/** The https URL of a `List-Unsubscribe` value (`<https://…>, <mailto:…>`), or null. */
internal fun unsubscribeUrl(value: String?): String? {
    if (value.isNullOrBlank()) return null
    Regex("""<\s*(https://[^>\s]+)\s*>""", RegexOption.IGNORE_CASE).find(value)?.let { return it.groupValues[1] }
    return value.trim().takeIf { it.startsWith("https://", ignoreCase = true) && it.none(Char::isWhitespace) }
}
