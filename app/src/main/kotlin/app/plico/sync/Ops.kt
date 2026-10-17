package app.plico.sync

import android.text.format.Formatter
import app.plico.R
import app.plico.account.traits
import app.plico.data.Account
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MessageRef
import app.plico.data.NewFolder
import app.plico.data.Op
import app.plico.data.OpKind
import app.plico.data.ServerFlags
import app.plico.mail.imap.Command
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.UidSet
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pending operations (DESIGN.md §7.4). Every user action changes the database first and queues an op; the
 * session loop runs the due ones in id order. AccountSession calls the work objects in the order of DESIGN.md
 * §7.1: fast path + notify, then [Ops.runDue], [Sender.runDue], queued requests, other folders,
 * [Bodies.fetchWindow], backfill.
 */
object Ops {
    private val failed = MutableSharedFlow<ActionFailure>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Changes the server refused (undone locally) and sends that failed; see [MailActions.failures]. */
    internal val failures: SharedFlow<ActionFailure> = failed.asSharedFlow()

    internal fun report(accountId: Long, text: String, draftId: Long? = null) {
        failed.tryEmit(ActionFailure(accountId, text, draftId))
    }

    /** Runs every due op of the account in id order (DESIGN §7.4). */
    internal suspend fun run(ctx: OpContext) {
        val store = ctx.store
        var after = 0L
        // Re-reads after each op: an op may queue a follow-up (a draft upload queues delete_draft).
        while (true) {
            val op = store.ops.due(ctx.account.id, store.now()).firstOrNull { it.id > after } ?: break
            after = op.id
            if (waits(store, op)) continue
            runOne(ctx, op)
            ctx.progress()
        }
    }

    /** Earliest run_at (epoch ms) of an op that can run, or null. Ops on a guarded folder wait for its resync. */
    internal fun nextRunAt(store: OpStore, accountId: Long): Long? =
        store.ops.byAccount(accountId).filterNot { waits(store, it) }.minOfOrNull { it.runAt }

    /** SELECTs [folder]; throws [UidValidityChanged] when the stored UIDs belong to another numbering. */
    internal fun selectChecked(ctx: OpContext, folder: Folder) {
        val result = ctx.select(folder)
        if (folder.uidValidity != 0L && result.uidValidity != folder.uidValidity) throw UidValidityChanged()
    }

    /** Runs [block]; on `[TRYCREATE]` creates [path] (and its folder row) once and runs it again. */
    internal fun <T> creating(ctx: OpContext, path: String, role: FolderRole?, block: () -> T): T = try {
        block()
    } catch (e: ImapNoException) {
        if (e.code?.name != "TRYCREATE") throw e
        ctx.client.create(path)
        ctx.store.folders.upsert(createdFolder(ctx.store, ctx.account, path, role))
        block()
    }

    /**
     * The row of a mailbox that Plico has just created: a Riseup mailbox with [role], or a Gmail label view (Gmail
     * makes a label of every CREATE; its mailboxes are found by SPECIAL-USE only, DESIGN §7.3).
     */
    internal fun createdFolder(store: OpStore, account: Account, path: String, role: FolderRole?): NewFolder {
        if (account.isGmail) return NewFolder(account.id, FolderKind.LABEL, path, FolderRules.leaf(path, "/"), null, "/")
        val delimiter = store.folders.byAccount(account.id)
            .firstOrNull { it.kind == FolderKind.MAILBOX && it.delimiter != null }?.delimiter
        return NewFolder(account.id, FolderKind.MAILBOX, path, FolderRules.leaf(path, delimiter), role, delimiter)
    }

    /** Throws [OpRefused] when [size] bytes exceed the server's APPENDLIMIT or the provider limit. */
    internal fun checkAppendSize(ctx: OpContext, size: Int) {
        val limit = appendLimit(ctx.client.caps, ctx.account.traits.maxMessageBytes)
        if (size > limit) throw OpRefused(ctx.store.text(R.string.ops_error_too_large, formatSize(ctx.store, limit)))
    }

    /** The smaller of `APPENDLIMIT=n` in [caps] and [providerLimit]. */
    internal fun appendLimit(caps: Set<String>, providerLimit: Long): Long {
        val advertised = caps.firstNotNullOfOrNull { cap ->
            if (cap.startsWith("APPENDLIMIT=")) cap.substringAfter('=').toLongOrNull()?.takeIf { it > 0 } else null
        }
        return minOf(advertised ?: Long.MAX_VALUE, providerLimit)
    }

    /**
     * True while one of the op's mailboxes is frozen by the mass-delete guard (DESIGN §7.2 step 6), and while an
     * Empty Trash/Spam has a restore out of that folder queued before it (MailActions.emptyFolder): a restore
     * that must retry would else lose its message to the expunge.
     */
    private fun waits(store: OpStore, op: Op): Boolean {
        val folderIds = store.messages.refs(op.messageIds).mapTo(HashSet()) { it.folderId }
        val emptied = (OpArg.decode(op.kind, op.arg) as? OpArg.Expunge)?.folderId
        emptied?.let(folderIds::add)
        if (folderIds.any { store.folders.get(it)?.guard == true }) return true
        return emptied != null && store.ops.byAccount(op.accountId).any { earlier ->
            earlier.id < op.id && earlier.kind == OpKind.MOVE &&
                store.messages.refs(earlier.messageIds).any { it.folderId == emptied }
        }
    }

    private fun runOne(ctx: OpContext, op: Op) {
        val store = ctx.store
        // An unknown kind (written by a newer version) has nothing this version could undo.
        val arg = OpArg.decode(op.kind, op.arg) ?: return store.ops.delete(op.id)
        try {
            execute(ctx, op, arg)
            store.ops.delete(op.id)
            if (arg is OpArg.Append && arg.role == FolderRole.SENT) Sender.cleanup(store, arg.draftId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UidValidityChanged) {
            // The sync drops the folder's rows; their old UIDs must not be fetched again.
            drop(ctx, op, arg, store.text(R.string.ops_error_folder_changed), refetch = false)
        } catch (e: Exception) {
            val attempts = op.attempts + 1
            val now = store.now()
            val ends = OpErrors.endsConnection(e, ctx.client)
            if (OpErrors.classify(e) == Failure.TRANSIENT && now - op.created < OpErrors.GIVE_UP_MS) {
                store.ops.retryLater(op.id, attempts, now + OpErrors.backoffMs(attempts), OpErrors.text(e))
            } else {
                drop(ctx, op, arg, failureText(store, arg, e), refetch = !ends)
            }
            if (ends) throw e
        }
    }

    private fun execute(ctx: OpContext, op: Op, arg: OpArg) {
        val client = ctx.client
        when (arg) {
            is OpArg.Flags -> forEachMailbox(ctx, op) { _, rows ->
                client.uidStoreFlags(uids(rows), arg.on, listOf(arg.flag))
            }
            is OpArg.Labels -> forEachMailbox(ctx, op) { _, rows ->
                client.uidStoreLabels(uids(rows), true, arg.add)
                client.uidStoreLabels(uids(rows), false, arg.remove)
            }
            is OpArg.Move -> move(ctx, op, arg)
            is OpArg.Expunge -> expunge(ctx, op, arg)
            is OpArg.Append -> if (arg.role == FolderRole.SENT) Sender.uploadSentCopy(ctx, arg) else Drafts.upload(ctx, arg)
            is OpArg.DeleteDraft -> Drafts.deleteRevisions(ctx, arg)
        }
    }

    private fun move(ctx: OpContext, op: Op, arg: OpArg.Move) {
        val store = ctx.store
        forEachMailbox(ctx, op, skip = { it.path == arg.path }) { source, rows ->
            // Gmail turns a MOVE or expunge out of All Mail into a deletion (protocol PS8).
            if (ctx.account.isGmail && source.role == FolderRole.ALL &&
                arg.role != FolderRole.TRASH && arg.role != FolderRole.JUNK
            ) {
                throw OpRefused(store.text(R.string.ops_error_gmail_move))
            }
            val moved = creating(ctx, arg.path, arg.role) { ctx.client.uidMove(uids(rows), arg.path) }
            val target = store.folders.byPath(ctx.account.id, FolderKind.MAILBOX, arg.path)
            // Gmail: out of Trash or Spam into INBOX or a label, the message is back in All Mail with a new UID.
            val (folder, newUids) = when {
                target != null -> target to moved
                ctx.account.isGmail -> allMailUids(ctx, rows)
                else -> null to emptyMap()
            }
            for (row in rows) {
                val uid = newUids[row.uid]
                // The row keeps its body and notified state (protocol PS26); without a UID the target's sync brings it.
                if (folder != null && uid != null) store.messages.moveRow(row.id, folder.id, uid)
                else store.messages.delete(listOf(row.id))
            }
            if (folder != null && folder.id != target?.id) {
                // The next All Mail fetch writes every label; the label view of the target shows the rows at once.
                val view = labelIds(store, ctx.account.id, listOf(if (arg.path == FolderRules.INBOX) INBOX_LABEL else arg.path))
                view.forEach { store.messages.addToFolder(rows.filter { it.uid in newUids }.map { it.id }, it) }
            }
        }
    }

    /** The All Mail UIDs (by old UID) of Gmail [rows] that a MOVE has taken out of Trash or Spam, by X-GM-MSGID. */
    private fun allMailUids(ctx: OpContext, rows: List<MessageRef>): Pair<Folder?, Map<Long, Long>> {
        val all = ctx.store.mailbox(ctx.account.id, FolderRole.ALL) ?: return null to emptyMap()
        if (ctx.select(all).uidValidity.let { all.uidValidity != 0L && it != all.uidValidity }) return null to emptyMap()
        val uids = HashMap<Long, Long>()
        for (row in rows) {
            val msgId = ctx.store.messages.get(row.id)?.gmMsgId ?: continue
            val criteria = Command.build {
                raw("X-GM-MSGID")
                raw(msgId.toString())
            }
            ctx.client.uidSearch(criteria).toList().maxOrNull()?.let { uids[row.uid] = it }
        }
        return all to uids
    }

    private fun expunge(ctx: OpContext, op: Op, arg: OpArg.Expunge) {
        val store = ctx.store
        if (arg.folderId == null) {
            forEachMailbox(ctx, op) { folder, rows ->
                refuseAllMail(ctx, folder)
                deleteForever(ctx, uids(rows))
                store.messages.delete(rows.map { it.id })
            }
            return
        }
        val folder = store.folders.get(arg.folderId) ?: return
        if (arg.upTo < 1) return
        refuseAllMail(ctx, folder)
        selectChecked(ctx, folder)
        deleteForever(ctx, UidSet.range(1, arg.upTo))
        store.messages.deleteUids(folder.id, store.messages.uids(folder.id, 1, arg.upTo))
    }

    /** `\Deleted` + `UID EXPUNGE` in the selected mailbox. */
    internal fun deleteForever(ctx: OpContext, uids: UidSet) {
        ctx.client.uidStoreFlags(uids, true, listOf(DELETED))
        ctx.client.uidExpunge(uids)
    }

    private fun refuseAllMail(ctx: OpContext, folder: Folder) {
        if (ctx.account.isGmail && folder.role == FolderRole.ALL) {
            throw OpRefused(ctx.store.text(R.string.ops_error_gmail_move))
        }
    }

    /** Runs [block] per source mailbox of the op's rows that still exist, after a checked SELECT. */
    private fun forEachMailbox(
        ctx: OpContext,
        op: Op,
        skip: (Folder) -> Boolean = { false },
        block: (Folder, List<MessageRef>) -> Unit,
    ) {
        for ((folderId, rows) in ctx.store.messages.refs(op.messageIds).groupBy { it.folderId }) {
            val folder = ctx.store.folders.get(folderId) ?: continue
            if (skip(folder)) continue
            selectChecked(ctx, folder)
            block(folder, rows)
        }
    }

    /**
     * Drops [op] and undoes its local change by refetching the truth (DESIGN §7.4): flags and labels from the
     * server, rows that are gone deleted, the rest visible again. Then reports [text].
     */
    private fun drop(ctx: OpContext, op: Op, arg: OpArg, text: String, refetch: Boolean) {
        val store = ctx.store
        store.ops.delete(op.id)
        try {
            when (arg) {
                is OpArg.Flags, is OpArg.Labels, is OpArg.Move -> restore(ctx, op.messageIds, refetch)
                is OpArg.Expunge -> if (arg.folderId == null) {
                    restore(ctx, op.messageIds, refetch)
                } else {
                    val ids = store.messages.idsByUid(arg.folderId, store.messages.uids(arg.folderId, 1, arg.upTo))
                    store.messages.setHidden(ids.values - store.ops.referencedMessageIds(ctx.account.id), false)
                }
                is OpArg.Append -> if (arg.role == FolderRole.SENT) Sender.cleanup(store, arg.draftId)
                // The copies stay on the server, so they show again.
                is OpArg.DeleteDraft -> if (arg.keep == 0L) Drafts.hideServerCopies(store, ctx.account.id, arg.messageId, false)
            }
        } finally {
            report(ctx.account.id, text)
        }
    }

    private fun restore(ctx: OpContext, ids: List<Long>, refetch: Boolean) {
        val store = ctx.store
        val refs = store.messages.refs(ids)
        try {
            if (refetch) refetchFlags(ctx, refs)
        } finally {
            // Rows that another op still hides stay hidden.
            store.messages.setHidden(refs.map { it.id } - store.ops.referencedMessageIds(ctx.account.id), false)
        }
    }

    private fun refetchFlags(ctx: OpContext, refs: List<MessageRef>) {
        val store = ctx.store
        val gmail = ctx.account.isGmail
        for ((folderId, rows) in refs.groupBy { it.folderId }) {
            val folder = store.folders.get(folderId) ?: continue
            if (ctx.select(folder).uidValidity != folder.uidValidity && folder.uidValidity != 0L) continue
            val wanted = rows.mapTo(HashSet()) { it.uid }
            val found = ArrayList<ServerFlags>()
            // As in the sync: only All Mail rows have labels; Trash and Spam rows have none (MailboxSync.labelsOf).
            val withLabels = gmail && folder.role == FolderRole.ALL
            ctx.client.uidFetch(UidSet.of(wanted), HeaderParser.flagItems(withLabels)) { item ->
                if (item.uid !in wanted) return@uidFetch
                val labels = when {
                    !gmail -> null
                    withLabels -> labelIds(store, ctx.account.id, item.gmLabels.orEmpty())
                    else -> emptySet()
                }
                HeaderParser.serverFlags(item, labels)?.let(found::add)
            }
            store.messages.syncFlags(folder.id, found)
            store.messages.deleteUids(folder.id, wanted - found.mapTo(HashSet()) { it.uid })
        }
    }

    /** Folder ids of the views of Gmail [labels], by the sync's rules; a new label gets a hidden view (protocol PS6). */
    internal fun labelIds(store: OpStore, accountId: Long, labels: List<String>): Set<Long> =
        labels.mapNotNullTo(HashSet()) { label ->
            val path = FolderRules.viewPath(label) ?: return@mapNotNullTo null
            store.folders.byPath(accountId, FolderKind.LABEL, path)?.id
                ?: store.folders.ensureLabel(accountId, path, FolderRules.leaf(path, "/"))
        }

    private fun failureText(store: OpStore, arg: OpArg, e: Exception): String {
        val server = OpErrors.text(e)
        return when {
            e is OpRefused -> server
            arg is OpArg.Append && arg.role == FolderRole.SENT -> store.text(R.string.ops_error_sent_not_saved, server)
            arg is OpArg.Append -> store.text(R.string.ops_error_draft_not_saved, server)
            else -> store.text(R.string.ops_error_refused, server)
        }
    }

    internal fun formatSize(store: OpStore, bytes: Long): String = Formatter.formatShortFileSize(store.context, bytes)

    private fun uids(rows: List<MessageRef>) = UidSet.of(rows.map { it.uid })

    private const val DELETED = "\\Deleted"
    private const val INBOX_LABEL = "\\Inbox"
}
