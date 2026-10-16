package app.plico.sync

import android.net.Uri
import app.plico.PlicoApp
import app.plico.data.DraftAttachment
import app.plico.data.Folder
import app.plico.data.MailView
import app.plico.data.NewAccount
import java.io.File

/** Token for the Undo snackbar of an undoable action. */
data class Undo(val opIds: List<Long>, val messageIds: List<Long>)

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

/**
 * The UI's single entry point for changes (owner: ops agent). Every action changes the DB first and
 * queues an op (DESIGN §7.4), then kicks the account's session. Reads use the DAOs directly.
 */
class MailActions(private val app: PlicoApp) {
    fun archive(ids: List<Long>, fromView: MailView): Undo? = TODO()

    /** To Trash; inside Trash/Spam use [deleteForever] (the UI confirms). */
    fun delete(ids: List<Long>): Undo? = TODO()
    fun deleteForever(ids: List<Long>): Unit = TODO()
    fun markSeen(ids: List<Long>, seen: Boolean): Unit = TODO()
    fun star(ids: List<Long>, on: Boolean): Unit = TODO()

    /** Gmail only. */
    fun important(ids: List<Long>, on: Boolean): Unit = TODO()

    /** Riseup: move to the mailbox. Gmail: label move from the view's label (DESIGN §7.3). */
    fun moveTo(ids: List<Long>, folderId: Long, fromView: MailView): Undo? = TODO()

    /** Gmail labels by label-view folder ids. */
    fun setLabels(ids: List<Long>, add: Set<Long>, remove: Set<Long>): Unit = TODO()
    fun spam(ids: List<Long>, isSpam: Boolean): Undo? = TODO()
    fun emptyFolder(folderId: Long): Unit = TODO()
    fun undo(undo: Undo): Unit = TODO()

    /** A notification button: same as the list action but with run_at = now (no undo), then kick. */
    fun notificationAction(messageId: Long, action: NotificationAction): Unit = TODO()

    /** Online: CREATE on the server, then a folder row. */
    suspend fun createFolder(accountId: Long, name: String): Folder = TODO()
    fun setFolderNotify(folderId: Long, notify: Boolean): Unit = TODO()

    /** Drops the rows of a guarded folder and resyncs it (DESIGN §7.2 step 6). */
    fun resyncFolder(folderId: Long): Unit = TODO()

    suspend fun loadBody(messageId: Long): Unit = TODO()
    suspend fun loadAttachment(attachmentId: Long, onProgress: (Float) -> Unit = {}): File = TODO()

    /** Raw header block for "View headers" (BODY.PEEK[HEADER], not stored). */
    suspend fun fetchHeaders(messageId: Long): String = TODO()

    /** Returns the number of hits. */
    suspend fun searchServer(query: String, accountId: Long?, folderId: Long?): Int = TODO()

    /** Pull to refresh / folder opened. */
    suspend fun refresh(view: MailView): Unit = TODO()

    /** RFC 8058 one-click POST when allowed (DESIGN §11.4); false when not possible. */
    suspend fun unsubscribeOneClick(messageId: Long): Boolean = TODO()

    suspend fun newDraft(accountId: Long?, prefill: DraftPrefill = DraftPrefill()): Long = TODO()
    suspend fun reply(messageId: Long, all: Boolean): Long = TODO()

    /** Downloads the original attachments into the draft (DESIGN §7.8). */
    suspend fun forward(messageId: Long): Long = TODO()
    suspend fun editServerDraft(messageId: Long): Long = TODO()
    fun saveDraft(draftId: Long, fromEmail: String, to: String, cc: String, bcc: String, subject: String, body: String): Unit = TODO()

    /** Compose closed: queue the server APPEND (DESIGN §7.6). */
    fun leaveDraft(draftId: Long): Unit = TODO()
    fun discardDraft(draftId: Long): Unit = TODO()

    /** Queues the send with the undo delay; returns false when the draft is not valid (no recipient, too large). */
    fun send(draftId: Long): Boolean = TODO()
    fun cancelSend(draftId: Long): Unit = TODO()
    fun retrySend(draftId: Long): Unit = TODO()

    /** Copies [uri] into the outbox (IncomingUris rules, image re-encoding per DESIGN §10). */
    suspend fun addAttachment(draftId: Long, uri: Uri, originalQuality: Boolean = false): DraftAttachment = TODO()
    fun removeAttachment(draftId: Long, file: String): Unit = TODO()

    /** Inserts a probed account, creates its channel, starts sync and push. */
    suspend fun addAccount(account: NewAccount): Long = TODO()

    /** Revokes credentials, purges data and files, deletes the channel. */
    suspend fun removeAccount(accountId: Long): Unit = TODO()

    /** Settings changed (push mode, body days, ...): reconfigure the session. */
    fun accountChanged(accountId: Long): Unit = TODO()
}
