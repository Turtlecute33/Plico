package app.plico.sync

import app.plico.PlicoApp
import app.plico.data.Folder
import java.io.File

/**
 * Work that runs on a session loop (owner: ops agent). AccountSession calls these in the order of
 * DESIGN.md §7.1: fast path + notify, then Ops.runDue, Sender.runDue, queued requests, other folders,
 * Bodies.fetchWindow, backfill.
 */
object Ops {
    /** Runs every due op of the account in id order (DESIGN §7.4). */
    suspend fun runDue(ctx: SyncContext): Unit = TODO()

    /** Earliest run_at of a pending op (epoch ms converted by the caller), or null. */
    fun nextRunAt(app: PlicoApp, accountId: Long): Long? = TODO()

    /** Message ids that pending ops reference; sync must not overwrite their local state (§7.2). */
    fun protectedIds(app: PlicoApp, accountId: Long): Set<Long> = TODO()
}

object Sender {
    /** Sends every due draft of the account (DESIGN §7.5). */
    suspend fun runDue(ctx: SyncContext): Unit = TODO()

    /** Earliest send_at of a queued draft (epoch ms), or null. */
    fun nextSendAt(app: PlicoApp, accountId: Long): Long? = TODO()

    /** At process start: drafts left in SENDING become FAILED "may have been sent". */
    fun onProcessStart(app: PlicoApp): Unit = TODO()
}

object Bodies {
    /** Fetches BODYSTRUCTURE + display text parts for [messageIds] and stores body, attachments, snippet and FTS. */
    suspend fun fetch(ctx: SyncContext, messageIds: List<Long>): Unit = TODO()

    /** Fetches missing bodies in the body window of [folder], at most [limit] messages. Returns how many. */
    suspend fun fetchWindow(ctx: SyncContext, folder: Folder, limit: Int = 25): Int = TODO()
}

object Attachments {
    /** Chunked download into the attachment cache (DESIGN §7.8). */
    suspend fun load(ctx: SyncContext, attachmentId: Long, onProgress: (Float) -> Unit): File = TODO()
}

object Search {
    /** Server search (DESIGN §7.7); inserts missing hits as header rows; returns local message ids. */
    suspend fun server(ctx: SyncContext, query: String, folder: Folder?): List<Long> = TODO()
}
