package app.plico.sync

import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.NewMessage
import app.plico.data.ServerFlags
import app.plico.mail.imap.Command
import app.plico.mail.imap.FetchItem
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.MailboxStatus
import app.plico.mail.imap.SelectResult
import app.plico.mail.imap.UidSet
import app.plico.push.policy.NotifyRule

/**
 * Sync of one kind-0 mailbox, on Gmail and on every other server (DESIGN.md §7.2). Blocking; runs on the session
 * loop. Rows that a pending op references keep their local flags and labels: MessageDao.upsert and syncFlags skip
 * them (protocol PS2). Without ESEARCH, searches go in UID windows ([MailboxMath.searchWindows]).
 */
internal class MailboxSync(private val env: SyncEnv, private val labels: GmailLabels?) {

    /**
     * Steps 2–6. [status] is the cheap check (step 2); null syncs without it (the folder was opened). [fullFlags]:
     * a flag check is due, so a mailbox of a server without CONDSTORE is not skipped (DESIGN §7.2 step 2).
     */
    fun sync(folder: Folder, status: MailboxStatus?, fullFlags: Boolean, report: SyncReport) {
        if (!folder.syncable) return
        if (status != null && MailboxMath.unchanged(status, folder, knownMessages(folder), flagsDue = fullFlags)) return
        val current = newMail(folder, report, statusUidNext = status?.uidNext) ?: return
        flagsAndExpunge(current, fullFlags, report)
    }

    /**
     * Steps 3 and 4: SELECT (or [selected], a SELECT the caller has just done), the UIDVALIDITY check, then the
     * new UIDs. Returns the updated row, or null when the folder does not sync now (guard, gone). The message
     * count for the STATUS skip is kept only after step 6, so that an expunge check that fails is not skipped.
     * [statusUidNext]: the UIDNEXT of a STATUS just before; the stored UIDNEXT is never below it (a SELECT without
     * UIDNEXT gives only an estimate, and the STATUS skip compares with the stored value).
     */
    fun newMail(folder: Folder, report: SyncReport, selected: SelectResult? = null, statusUidNext: Long? = null): Folder? {
        if (!folder.syncable) return null
        val sel = selected ?: select(folder, report) ?: return null
        val valid = validate(folder, sel, report) ?: return null
        fetchNew(valid, sel, statusUidNext ?: 0, report)
        return env.folders.get(valid.id)
    }

    /** Steps 5 and 6. Uses the selected mailbox, or SELECTs it again when another one is selected. */
    fun flagsAndExpunge(folder: Folder, fullFlags: Boolean, report: SyncReport) {
        val (current, sel) = open(folder, report) ?: return
        if (current.backfillUid == 0L) return
        flags(current, sel, fullFlags, report)
        expunge(env.folders.get(current.id) ?: return, report)
        remember(current)
    }

    /** Step 8: headers of the next 500 older UIDs. Returns false when there was nothing to do. */
    fun backfillStep(folder: Folder, report: SyncReport): Boolean {
        if (!folder.syncable || folder.backfillUid <= 1) return false
        val (current, _) = open(folder, report) ?: return false
        val range = MailboxMath.backfillRange(current.backfillUid) ?: return false
        var from = range.first
        if (fetchRange(current, range, report) == 0 && range.first > 1) {
            // UIDs are sparse (Gmail): continue at the next older UID that exists.
            val max = highestUidBelow(range.first)
            from = if (max == null) 1 else MailboxMath.backfillRangeBelow(max).also { fetchRange(current, it, report) }.first
        }
        env.folders.setSyncState(current.id, backfillUid = from)
        return true
    }

    /**
     * The highest UID below [below] in the selected mailbox, or null. ESEARCH: one `RETURN (MAX)`. Without it: plain
     * searches in windows of 50,000 UIDs downward, up to the first window with a hit.
     */
    private fun highestUidBelow(below: Long): Long? {
        if (env.esearch) return env.client.uidSearchMax(Command.raw("UID", "1:${below - 1}"))
        for (window in MailboxMath.searchWindows(below)) {
            env.client.uidSearch(Command.raw("UID", "${window.first}:${window.last}")).max?.let { return it }
            env.progress()
        }
        return null
    }

    private val Folder.syncable: Boolean get() = kind == FolderKind.MAILBOX && !hidden && !guard

    private fun knownMessages(folder: Folder): Long? =
        env.knownMessages[folder.id] ?: env.messages.count(folder.id).toLong().takeIf { folder.backfillUid == 1L }

    private fun remember(folder: Folder) {
        env.selection.current(folder.path)?.let { env.knownMessages[folder.id] = it.exists }
    }

    /** A mailbox deleted on the server answers NO: skip it and LIST again. */
    private fun select(folder: Folder, report: SyncReport): SelectResult? = try {
        env.selection.select(folder.path)
    } catch (_: ImapNoException) {
        report.relist = true
        null
    }

    private fun open(folder: Folder, report: SyncReport): Pair<Folder, SelectResult>? {
        val row = env.folders.get(folder.id)?.takeIf { it.syncable } ?: return null
        val sel = env.selection.current(row.path) ?: select(row, report) ?: return null
        return validate(row, sel, report)?.let { it to sel }
    }

    /** Step 3: a new UIDVALIDITY drops the pending ops of the folder and its rows, through the guard. */
    private fun validate(folder: Folder, sel: SelectResult, report: SyncReport): Folder? {
        if (folder.uidValidity == sel.uidValidity) return folder
        if (folder.uidValidity == 0L) {
            env.folders.setSyncState(folder.id, uidValidity = sel.uidValidity)
            return folder.copy(uidValidity = sel.uidValidity)
        }
        dropOps(folder)
        val rows = env.messages.count(folder.id)
        if (MailboxMath.guarded(folder.role) && rows > MailboxMath.GUARD_MIN_ROWS) {
            env.folders.setGuard(folder.id, true)
            return null
        }
        env.folders.resync(folder.id)
        env.folders.setSyncState(folder.id, uidValidity = sel.uidValidity)
        env.knownMessages.remove(folder.id)
        if (rows > 0) report.changed = true
        return env.folders.get(folder.id)
    }

    /**
     * Ops on stale UIDs must never run (protocol PS11): an op with rows only in [folder] goes, and its hidden rows
     * show again, and so does an Empty Trash/Spam of it. An op that also has rows in other folders stays for
     * them (as in MailActions.resyncFolder): the resync deletes this folder's rows, or the guard makes it wait.
     */
    private fun dropOps(folder: Folder) {
        for (op in env.ops.byAccount(env.accountId)) {
            val rows = env.messages.refs(op.messageIds)
            val empties = (OpArg.decode(op.kind, op.arg) as? OpArg.Expunge)?.folderId == folder.id
            if (!empties && (rows.isEmpty() || rows.any { it.folderId != folder.id })) continue
            env.ops.delete(op.id)
            env.messages.setHidden(rows.map { it.id }, false)
        }
    }

    /**
     * Step 4. A first sync takes the newest 200 by sequence number; later syncs `UID FETCH <uidnext>:*`. Items
     * without the header section are unsolicited flag updates (DESIGN §4.3) and are left out.
     */
    private fun fetchNew(folder: Folder, sel: SelectResult, statusUidNext: Long, report: SyncReport) {
        val notified = NotifyRule.insertNotified(fromNewUidFetch = true, uidNextBefore = folder.uidNext)
        if (folder.backfillUid == 0L) {
            val start = MailboxMath.firstSyncStart(sel.exists)
            var lowest: Long? = null
            if (start != null) {
                insert(folder, notified, newMail = true, report) { each ->
                    env.client.fetch("$start:*", items(folder)) { item ->
                        if (!HeaderParser.hasHeaderFields(item)) return@fetch
                        lowest = minOf(lowest ?: item.uid, item.uid)
                        each(item)
                    }
                }
            }
            env.folders.setSyncState(
                folder.id,
                uidNext = maxOf(sel.uidNext, statusUidNext),
                modSeq = sel.highestModSeq ?: 0,
                backfillUid = start?.let { MailboxMath.backfillAfterFirstSync(it, lowest) } ?: 1,
            )
            return
        }
        if (sel.uidNext <= folder.uidNext) {
            if (statusUidNext > folder.uidNext) env.folders.setSyncState(folder.id, uidNext = statusUidNext)
            return
        }
        val from = maxOf(1, folder.uidNext)
        var highest = 0L
        insert(folder, notified, newMail = true, report) { each ->
            env.client.uidFetch(UidSet.range(from), items(folder)) { item ->
                // `n:*` always returns the last message, also when it is older than n (protocol PS10).
                if (item.uid >= from && HeaderParser.hasHeaderFields(item)) {
                    highest = maxOf(highest, item.uid)
                    each(item)
                }
            }
        }
        env.folders.setSyncState(folder.id, uidNext = maxOf(sel.uidNext, highest + 1, statusUidNext))
    }

    private fun fetchRange(folder: Folder, range: LongRange, report: SyncReport): Int =
        insert(folder, notified = true, newMail = false, report) { each ->
            env.client.uidFetch(UidSet.range(range.first, range.last), items(folder)) {
                if (it.uid in range && HeaderParser.hasHeaderFields(it)) each(it)
            }
        }

    /** Stores the rows that [fetch] yields in batches; returns their number. */
    private fun insert(
        folder: Folder,
        notified: Boolean,
        newMail: Boolean,
        report: SyncReport,
        fetch: (each: (FetchItem) -> Unit) -> Unit,
    ): Int {
        val batch = ArrayList<NewMessage>()
        var count = 0
        val inbox = if (newMail) labels?.inboxId(report) else null
        fun flush() {
            if (batch.isEmpty()) return
            env.messages.upsert(batch)
            count += batch.size
            if (inbox != null) report.newInbox += batch.count { inbox in it.labels.orEmpty() }
            report.newRows += folder.id
            batch.clear()
            env.progress()
        }
        val now = env.clock()
        fetch { item ->
            val row = HeaderParser.newMessage(
                item, env.accountId, folder.id, env.gmail, labelsOf(folder, item, report), notified, now,
            )
            // A MOVE without COPYUID brought it here ("Not spam", a restore): it is no new mail (DESIGN §7.4).
            val moved = newMail && !notified && row.messageId?.let { env.movedIn.take(folder.path, it) } == true
            batch += if (moved) row.copy(notified = true) else row
            if (batch.size >= BATCH) flush()
        }
        flush()
        return count
    }

    /**
     * Step 5: `CHANGEDSINCE` with CONDSTORE. Without it (NOMODSEQ, or HIGHESTMODSEQ went backwards), the
     * newest 200 local rows, or the whole local range when [full].
     */
    private fun flags(folder: Folder, sel: SelectResult, full: Boolean, report: SyncReport) {
        val highest = sel.highestModSeq?.takeIf { env.condstore && !sel.noModSeq }
        val items = HeaderParser.flagItems(gmLabels = withLabels(folder))
        if (highest != null && folder.modSeq in 1..highest) {
            if (highest > folder.modSeq) {
                apply(folder, report) { each ->
                    env.client.uidFetch(UidSet.range(1), items, changedSince = folder.modSeq, each = each)
                }
            }
        } else {
            val uids = if (full) env.messages.uids(folder.id) else env.messages.newestUids(folder.id, MailboxMath.PUSH_FLAG_ROWS)
            if (uids.isNotEmpty()) {
                apply(folder, report) { each ->
                    env.client.uidFetch(UidSet.range(uids.first(), uids.last()), items, each = each)
                }
            }
        }
        if (highest != null && highest != folder.modSeq) env.folders.setSyncState(folder.id, modSeq = highest)
    }

    private fun apply(folder: Folder, report: SyncReport, fetch: (each: (FetchItem) -> Unit) -> Unit) {
        val batch = ArrayList<ServerFlags>()
        fun flush() {
            if (batch.isEmpty()) return
            env.messages.syncFlags(folder.id, batch)
            report.changed = true
            batch.clear()
            env.progress()
        }
        fetch { item ->
            HeaderParser.serverFlags(item, labelsOf(folder, item, report), env.gmail)?.let(batch::add)
            if (batch.size >= FLAGS_BATCH) flush()
        }
        flush()
    }

    /**
     * Step 6 (protocol PS4): skipped when every server message is local. Else `RETURN (COUNT)` per window of
     * 5,000 local rows, and `RETURN (ALL)` only for windows that differ. Without ESEARCH both forms return the full
     * list, so one plain search per window (of at most 50,000 UIDs) is compared by its size. The guard keeps a
     * mass delete.
     */
    private fun expunge(folder: Folder, report: SyncReport) {
        val sel = env.selection.current(folder.path) ?: return
        if (MailboxMath.canSkipExpunge(folder.backfillUid, sel.exists, env.messages.count(folder.id))) return
        val uids = env.messages.uids(folder.id)
        val missing = ArrayList<Long>()
        for (window in MailboxMath.windows(uids, if (env.esearch) Long.MAX_VALUE else MailboxMath.SEARCH_WINDOW)) {
            val range = Command.raw("UID", "${window.first()}:${window.last()}")
            val onServer = if (env.esearch) {
                if (env.client.uidSearchCount(range) == window.size.toLong()) continue
                env.client.uidSearch(range)
            } else {
                env.client.uidSearch(range).takeIf { it.count != window.size.toLong() } ?: continue
            }
            window.filterTo(missing) { it !in onServer }
            env.progress()
        }
        if (missing.isEmpty()) return
        if (MailboxMath.guarded(folder.role) && MailboxMath.guardTrips(missing.size, uids.size)) {
            env.folders.setGuard(folder.id, true)
            return
        }
        env.messages.deleteUids(folder.id, missing)
        report.changed = true
    }

    private fun items(folder: Folder) =
        HeaderParser.items(modSeq = env.condstore, gmMsgId = env.gmail, gmLabels = withLabels(folder))

    /** Only Gmail All Mail carries label memberships; Trash and Spam rows have none. */
    private fun withLabels(folder: Folder) = env.gmail && folder.role == FolderRole.ALL

    private fun labelsOf(folder: Folder, item: FetchItem, report: SyncReport): Set<Long>? = when {
        labels == null -> null
        withLabels(folder) -> item.gmLabels?.let { labels.ids(it, report) }
        else -> emptySet()
    }

    private companion object {
        const val BATCH = 100
        const val FLAGS_BATCH = 500
    }
}
