package app.plico.sync

import app.plico.R
import app.plico.data.DraftState
import app.plico.data.Folder
import app.plico.data.FolderRole
import app.plico.data.OpKind
import app.plico.data.Tables
import app.plico.mail.imap.Command
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.UidSet
import java.io.IOException

/**
 * Server copies of drafts (DESIGN.md §7.6, protocol PS7). Every revision has the draft's Message-ID; an
 * upload APPENDs the current content with `(\Draft \Seen)` and Bcc, then queues the deletion of the older
 * revisions by that Message-ID.
 */
internal object Drafts {
    private const val DEFAULT_PATH = "Drafts"
    private val FLAGS = listOf("\\Draft", "\\Seen")

    /** Queues the upload of draft [draftId]; its content is read when the op runs, so one queued upload is enough. */
    fun queueUpload(store: OpStore, accountId: Long, draftId: Long): Long = store.db.tx(Tables.OP) {
        dropUploads(store, accountId, draftId)
        store.ops.insert(OpArg.Append(draftId, FolderRole.DRAFTS).toNewOp(accountId, store.now()))
    }

    /**
     * Queues the deletion of every server copy of [messageId]. Until it runs, the local rows of those copies are
     * hidden: without the local draft nothing else hides them (a sent draft must not show as a server draft).
     */
    fun queueDelete(store: OpStore, accountId: Long, messageId: String): Long =
        store.db.tx(Tables.OP, Tables.MESSAGE) {
            hideServerCopies(store, accountId, messageId, true)
            store.ops.insert(OpArg.DeleteDraft(messageId).toNewOp(accountId, store.now()))
        }

    /** Hides or shows the local rows of the server drafts of [messageId] (`\Draft`; a Sent copy has no `\Draft`). */
    fun hideServerCopies(store: OpStore, accountId: Long, messageId: String, hidden: Boolean) {
        val rows = store.messages.byMessageId(accountId, messageId).filter { it.draft }.map { it.id }
        store.messages.setHidden(if (hidden) rows else rows - store.ops.referencedMessageIds(accountId), hidden)
    }

    /** Removes the queued uploads of [draftId]; true when there was one. */
    fun dropUploads(store: OpStore, accountId: Long, draftId: Long): Boolean = store.db.tx(Tables.OP) {
        val uploads = store.ops.byAccount(accountId).filter { op ->
            op.kind == OpKind.APPEND &&
                (OpArg.decode(op.kind, op.arg) as? OpArg.Append)?.let { it.draftId == draftId && it.role == FolderRole.DRAFTS } == true
        }
        uploads.forEach { store.ops.delete(it.id) }
        uploads.isNotEmpty()
    }

    /**
     * Runs an [OpArg.Append] to Drafts. A draft that is gone, queued or sent needs no upload. A Gmail account
     * whose Drafts mailbox is hidden from IMAP keeps its drafts local: Gmail mailboxes are never created.
     */
    fun upload(ctx: OpContext, arg: OpArg.Append) {
        val store = ctx.store
        val draft = store.drafts.get(arg.draftId) ?: return
        if (draft.accountId != ctx.account.id || draft.state != DraftState.EDITING && !DraftState.failed(draft.state)) return
        val mailbox = store.mailbox(ctx.account.id, FolderRole.DRAFTS)
        if (mailbox == null && ctx.account.isGmail) return
        val bytes = try {
            DraftMime.builder(store, ctx.account, draft, strict = false, date = store.now()).build(includeBcc = true)
        } catch (_: IOException) {
            // A local file, not the connection: this upload can never work.
            throw OpRefused(store.text(R.string.ops_error_attachment_missing))
        }
        Ops.checkAppendSize(ctx, bytes.size)
        val path = mailbox?.path ?: Ops.defaultPath(ctx, DEFAULT_PATH)
        val before = if (mailbox != null && ctx.account.generic) uidNextBefore(ctx, mailbox) else null
        ctx.progress(uploadMs(bytes.size))
        val uid = Ops.creating(ctx, path, FolderRole.DRAFTS) { ctx.client.append(path, FLAGS, bytes) } ?: before
        store.db.tx(Tables.DRAFT, Tables.OP) {
            // Discarded meanwhile: no copy may stay, this one included.
            val kept = store.drafts.get(draft.id)
            if (kept != null) store.drafts.setOnServer(draft.id, true)
            val keep = if (kept != null) uid else 0L
            if (keep != null) store.ops.insert(OpArg.DeleteDraft(draft.messageId, keep).toNewOp(ctx.account.id, store.now()))
        }
    }

    /**
     * Without APPENDUID, the UIDNEXT before the APPEND is a UID that the new revision is at or above (§7.6). Null when
     * the server refuses the SELECT (the mailbox is gone: the APPEND creates it, and no revision is deleted).
     */
    private fun uidNextBefore(ctx: OpContext, mailbox: Folder): Long? = try {
        ctx.select(mailbox).uidNext
    } catch (_: ImapNoException) {
        null
    }

    /**
     * Runs an [OpArg.DeleteDraft]: `UID SEARCH HEADER Message-ID` in Drafts (plus the local Drafts rows with that
     * Message-ID, for a search index that is late), then `\Deleted` + expunge (other servers); Gmail `UID MOVE` to
     * Trash and the expunge of the COPYUID there, because an expunge in Drafts would leave the revision in Trash.
     * With a kept UID only older revisions go: APPENDUIDs grow (and without APPENDUID the kept UID is the UIDNEXT
     * before the APPEND), so two uploads in a row cannot delete each other's revision.
     */
    fun deleteRevisions(ctx: OpContext, arg: OpArg.DeleteDraft) {
        val store = ctx.store
        val drafts = store.mailbox(ctx.account.id, FolderRole.DRAFTS) ?: return
        Ops.selectChecked(ctx, drafts)
        val criteria = Command.build {
            raw("HEADER")
            string("Message-ID")
            string(arg.messageId)
        }
        val local = store.messages.byMessageId(ctx.account.id, arg.messageId).filter { it.folderId == drafts.id }.map { it.uid }
        val uids = (ctx.client.uidSearch(criteria).toList() + local).distinct().sorted()
            .filter { arg.keep == 0L || it < arg.keep }
        val trash = if (ctx.account.isGmail) store.mailbox(ctx.account.id, FolderRole.TRASH) else null
        when {
            uids.isEmpty() -> Unit
            trash == null -> {
                Ops.deleteForever(ctx, UidSet.of(uids), drafts)
                store.messages.deleteUids(drafts.id, uids)
            }
            else -> {
                val moved = ctx.client.uidMove(UidSet.of(uids), trash.path)
                if (moved.isNotEmpty()) {
                    ctx.select(trash)
                    Ops.deleteForever(ctx, UidSet.of(moved.values), trash)
                }
            }
        }
        // Gmail does not sync its Drafts mailbox: the revisions are All Mail rows, which only the draft hid.
        if (ctx.account.isGmail && arg.keep == 0L) {
            val rows = store.messages.byMessageId(ctx.account.id, arg.messageId).filter { it.draft }
            store.messages.delete(rows.map { it.id })
        }
    }
}
