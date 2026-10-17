package app.plico.sync

import android.text.format.Formatter
import app.plico.R
import app.plico.account.traits
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
import app.plico.mail.imap.Namespace
import app.plico.mail.imap.SelectResult
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
    internal fun selectChecked(ctx: OpContext, folder: Folder): SelectResult {
        val result = ctx.select(folder)
        if (folder.uidValidity != 0L && result.uidValidity != folder.uidValidity) throw UidValidityChanged()
        return result
    }

    /**
     * Runs [block]; on `[TRYCREATE]` creates [path] (and its folder row) once and runs it again. TRYCREATE is only
     * a SHOULD, so a NO without a response code does the same when no folder row of the account has that path.
     */
    internal fun <T> creating(ctx: OpContext, path: String, role: FolderRole?, block: () -> T): T = try {
        block()
    } catch (e: ImapNoException) {
        val code = e.code
        val create = if (code == null) {
            ctx.store.folders.byAccount(ctx.account.id).none { it.path == path }
        } else {
            code.name == "TRYCREATE"
        }
        if (!create) throw e
        ctx.client.create(path)
        ctx.store.folders.upsert(createdFolder(ctx, path, role))
        block()
    }

    /**
     * The row of a mailbox that Plico has just created: a mailbox with [role] (the delimiter of the personal
     * namespace when the server reports one, else that of the stored mailboxes), or a Gmail label view (Gmail makes
     * a label of every CREATE; its mailboxes are found by SPECIAL-USE only, DESIGN §7.3).
     */
    internal fun createdFolder(ctx: OpContext, path: String, role: FolderRole?): NewFolder {
        val account = ctx.account
        if (account.isGmail) return NewFolder(account.id, FolderKind.LABEL, path, FolderRules.leaf(path, "/"), null, "/")
        val delimiter = personalNamespace(ctx)?.delimiter?.takeIf { it.isNotEmpty() } ?: storedDelimiter(mailboxRows(ctx))
        return NewFolder(account.id, FolderKind.MAILBOX, path, FolderRules.leaf(path, delimiter), role, delimiter)
    }

    /**
     * The path of a mailbox that Plico creates under a default name ("Trash", "Spam", "Archive", "Sent", "Drafts", or
     * a new folder of the user): below the personal root ([FolderRules.prefixOf], e.g. `INBOX.Trash` on Courier) on
     * servers other than Riseup and Gmail. Riseup and Gmail keep [leaf] (their root is "").
     */
    internal fun defaultPath(ctx: OpContext, leaf: String): String {
        if (!ctx.account.generic) return leaf
        val personal = personalNamespace(ctx)
        val rows = mailboxRows(ctx)
        val delimiter = personal?.delimiter?.takeIf { it.isNotEmpty() } ?: storedDelimiter(rows)
        return FolderRules.withPrefix(FolderRules.prefixOf(personal?.prefix, rows.map { it.path }, delimiter), leaf)
    }

    /** The personal namespace of a server other than Riseup and Gmail (their rules use none); null without NAMESPACE. */
    private fun personalNamespace(ctx: OpContext): Namespace? =
        if (ctx.account.generic) ctx.client.namespace()?.personal?.firstOrNull() else null

    private fun mailboxRows(ctx: OpContext): List<Folder> =
        ctx.store.folders.byAccount(ctx.account.id).filter { it.kind == FolderKind.MAILBOX }

    /** The delimiter of the INBOX row, else of the first mailbox row that has one. */
    private fun storedDelimiter(mailboxes: List<Folder>): String? =
        mailboxes.firstOrNull { it.role == FolderRole.INBOX && it.delimiter != null }?.delimiter
            ?: mailboxes.firstOrNull { it.delimiter != null }?.delimiter

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
        val path = targetPath(ctx, arg)
        forEachMailbox(ctx, op, skip = { it.path == path }) { source, rows ->
            // Gmail turns a MOVE or expunge out of All Mail into a deletion (protocol PS8).
            if (ctx.account.isGmail && source.role == FolderRole.ALL &&
                arg.role != FolderRole.TRASH && arg.role != FolderRole.JUNK
            ) {
                throw OpRefused(store.text(R.string.ops_error_gmail_move))
            }
            val moved = creating(ctx, path, arg.role) {
                // Without MOVE: COPY, \Deleted, then the guarded expunge of the source mailbox.
                ctx.client.uidMove(uids(rows), path) { expungeFrom(ctx, source, it, guard = true) }
            }
            val target = store.folders.byPath(ctx.account.id, FolderKind.MAILBOX, path)
            // Gmail: out of Trash or Spam into INBOX or a label, the message is back in All Mail with a new UID.
            val (folder, newUids) = when {
                target != null -> target to moved
                ctx.account.isGmail -> allMailUids(ctx, rows)
                else -> null to emptyMap()
            }
            if (!ctx.account.isGmail) {
                // Without COPYUID the target's sync brings the message back as a new row, which must not notify.
                val gone = rows.filter { folder == null || newUids[it.uid] == null }
                ctx.movedIn.add(path, gone.mapNotNull { store.messages.get(it.id)?.messageId })
            }
            for (row in rows) {
                val uid = newUids[row.uid]
                // The row keeps its body and notified state (protocol PS26); without a UID the target's sync brings it.
                if (folder != null && uid != null) store.messages.moveRow(row.id, folder.id, uid)
                else store.messages.delete(listOf(row.id))
            }
            if (folder != null && folder.id != target?.id) {
                // The next All Mail fetch writes every label; the label view of the target shows the rows at once.
                val view = labelIds(store, ctx.account.id, listOf(if (path == FolderRules.INBOX) INBOX_LABEL else path))
                view.forEach { store.messages.addToFolder(rows.filter { it.uid in newUids }.map { it.id }, it) }
            }
        }
    }

    /**
     * The op's target path. A default name (an op with a role, and no mailbox row with that path) goes below the
     * personal root ([defaultPath]): the action queued only the bare name, maybe offline.
     */
    private fun targetPath(ctx: OpContext, arg: OpArg.Move): String {
        if (arg.role == null || ctx.store.folders.byPath(ctx.account.id, FolderKind.MAILBOX, arg.path) != null) return arg.path
        return defaultPath(ctx, arg.path)
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
                deleteForever(ctx, uids(rows), folder)
                store.messages.delete(rows.map { it.id })
            }
            return
        }
        val folder = store.folders.get(arg.folderId) ?: return
        if (arg.upTo < 1) return
        refuseAllMail(ctx, folder)
        selectChecked(ctx, folder)
        // Empty Trash/Spam: every message of the folder goes, so a plain EXPUNGE is right without UIDPLUS (DESIGN §7.4).
        deleteForever(ctx, UidSet.range(1, arg.upTo), folder, guard = false)
        store.messages.deleteUids(folder.id, store.messages.uids(folder.id, 1, arg.upTo))
    }

    /**
     * `\Deleted` + expunge of [uids] in the selected [folder]. Outside Trash and Spam the expunge is guarded on a
     * server without UIDPLUS: it never removes a `\Deleted` message of another client (DESIGN §4.4). When the guard skips it,
     * the messages stay `\Deleted` on the server, and their rows are not shown (DESIGN §7.2).
     */
    internal fun deleteForever(ctx: OpContext, uids: UidSet, folder: Folder) =
        deleteForever(ctx, uids, folder, guard = folder.role != FolderRole.TRASH && folder.role != FolderRole.JUNK)

    private fun deleteForever(ctx: OpContext, uids: UidSet, folder: Folder, guard: Boolean) {
        ctx.client.uidStoreFlags(uids, true, listOf(DELETED))
        expungeFrom(ctx, folder, uids, guard)
    }

    /**
     * Expunges [uids] (already `\Deleted`) from the selected [folder] (DESIGN §7.4). When the guard skips the
     * expunge, the UIDs are kept as the folder's own `\Deleted` messages, so that they never stop a later guarded
     * expunge as if they were another client's. A plain EXPUNGE removes them all, so the record is cleared then.
     */
    private fun expungeFrom(ctx: OpContext, folder: Folder, uids: UidSet, guard: Boolean) {
        // With UIDPLUS (Gmail, Riseup) UID EXPUNGE removes only [uids]: no guard, no record.
        if (uids.isEmpty || "UIDPLUS" in ctx.client.caps) {
            ctx.client.uidExpunge(uids, guard)
            return
        }
        val folders = ctx.store.folders
        val own = folders.ownDeleted(folder.id)
        if (!ctx.client.uidExpunge(uids, guard, allowed = own)) {
            folders.setOwnDeleted(folder.id, own.union(uids))
        } else if (!own.isEmpty) {
            folders.setOwnDeleted(folder.id, UidSet.EMPTY)
        }
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
                HeaderParser.serverFlags(item, labels, gmail)?.let(found::add)
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
