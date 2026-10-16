package app.plico.sync

import app.plico.data.DraftState
import app.plico.data.FolderRole
import app.plico.data.NewOp
import app.plico.data.OpKind
import app.plico.data.Tables
import app.plico.mail.imap.Command
import app.plico.mail.imap.UidSet

/**
 * Server copies of drafts (DESIGN.md §7.6, protocol PS7). Every revision has the draft's Message-ID; an
 * upload APPENDs the current content with `(\Draft \Seen)` and Bcc, then queues the deletion of the older
 * revisions by that Message-ID.
 */
internal object Drafts {
    private const val DEFAULT_PATH = "Drafts"
    private val FLAGS = listOf("\\Draft", "\\Seen")

    /** Queues the upload of draft [draftId]; its content is read when the op runs. */
    fun queueUpload(store: OpStore, accountId: Long, draftId: Long): Long =
        store.ops.insert(op(accountId, OpArg.Append(draftId, FolderRole.DRAFTS), store.now()))

    /** Queues the deletion of every server copy of [messageId]. */
    fun queueDelete(store: OpStore, accountId: Long, messageId: String): Long =
        store.ops.insert(op(accountId, OpArg.DeleteDraft(messageId), store.now()))

    /** Removes the queued uploads of [draftId]; true when there was one. */
    fun dropUploads(store: OpStore, accountId: Long, draftId: Long): Boolean = store.db.tx(Tables.OP) {
        val uploads = store.ops.byAccount(accountId).filter { op ->
            op.kind == OpKind.APPEND &&
                (OpArg.decode(op.kind, op.arg) as? OpArg.Append)?.let { it.draftId == draftId && it.role == FolderRole.DRAFTS } == true
        }
        uploads.forEach { store.ops.delete(it.id) }
        uploads.isNotEmpty()
    }

    /** Runs an [OpArg.Append] to Drafts. A draft that is gone, queued or sent needs no upload. */
    fun upload(ctx: OpContext, arg: OpArg.Append) {
        val store = ctx.store
        val draft = store.drafts.get(arg.draftId) ?: return
        if (draft.accountId != ctx.account.id || draft.state != DraftState.EDITING && draft.state != DraftState.FAILED) return
        val bytes = DraftMime.builder(store, ctx.account, draft, strict = false, date = store.now()).build(includeBcc = true)
        Ops.checkAppendSize(ctx, bytes.size)
        val path = store.mailbox(ctx.account.id, FolderRole.DRAFTS)?.path ?: DEFAULT_PATH
        val uid = Ops.creating(ctx, path, FolderRole.DRAFTS) { ctx.client.append(path, FLAGS, bytes) }
        store.db.tx(Tables.DRAFT, Tables.OP) {
            // Discarded meanwhile: no copy may stay, this one included.
            val kept = store.drafts.get(draft.id)
            if (kept != null) store.drafts.setOnServer(draft.id, true)
            val keep = if (kept != null) uid else 0L
            if (keep != null) store.ops.insert(op(ctx.account.id, OpArg.DeleteDraft(draft.messageId, keep), store.now()))
        }
    }

    /**
     * Runs an [OpArg.DeleteDraft]: `UID SEARCH HEADER Message-ID` in Drafts, then Riseup `\Deleted` +
     * `UID EXPUNGE`; Gmail `UID MOVE` to Trash and the expunge of the COPYUID there, because an expunge in
     * Drafts would leave the revision in Trash.
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
        val uids = ctx.client.uidSearch(criteria).toList().filter { it != arg.keep }
        if (uids.isEmpty()) return
        val trash = if (ctx.account.isGmail) store.mailbox(ctx.account.id, FolderRole.TRASH) else null
        if (trash == null) {
            Ops.deleteForever(ctx, UidSet.of(uids))
            store.messages.deleteUids(drafts.id, uids)
            return
        }
        val moved = ctx.client.uidMove(UidSet.of(uids), trash.path)
        if (moved.isEmpty()) return
        ctx.select(trash)
        Ops.deleteForever(ctx, UidSet.of(moved.values))
    }

    private fun op(accountId: Long, arg: OpArg, runAt: Long) = NewOp(accountId, arg.kind, arg = OpArg.encode(arg), runAt = runAt)
}
