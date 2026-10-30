package app.plico.sync

import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.MessageRef
import app.plico.data.NewAttachment
import app.plico.html.HtmlSanitizer
import app.plico.mail.imap.ImapBadException
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.ImapProtocolException
import app.plico.mail.imap.UidSet
import app.plico.mail.mime.MimePart
import app.plico.mail.mime.TransferDecoding
import app.plico.mail.mime.displayParts
import app.plico.mail.mime.parseBodyStructure

/**
 * Message bodies (DESIGN.md §7.2 step 7): BODYSTRUCTURE, then the first 50 display text parts of at most 2 MB
 * each, joined into one body with its search text, snippet and attachment rows. Inline images and attachments
 * download on demand ([Attachments]).
 */
object Bodies {
    private const val BATCH = 25
    private const val DAY_MS = 86_400_000L

    /** Fetches BODYSTRUCTURE + display text parts for [messageIds] and stores body, attachments, snippet and FTS. */
    internal fun fetch(ctx: OpContext, messageIds: List<Long>) {
        val store = ctx.store
        for ((folderId, rows) in store.messages.refs(messageIds).groupBy { it.folderId }) {
            val folder = store.folders.get(folderId) ?: continue
            Ops.selectChecked(ctx, folder)
            for (batch in rows.chunked(BATCH)) {
                fetchBatch(ctx, batch)
                ctx.progress()
            }
        }
    }

    /**
     * Fetches missing bodies in the body window of [folder], at most [limit] messages, without the rows that
     * [skips] skips. Returns how many bodies it saved.
     */
    internal fun fetchWindow(
        ctx: OpContext,
        folder: Folder,
        limit: Int = BATCH,
        skips: BodySkips = BodySkips(),
    ): Int {
        if (folder.kind != FolderKind.MAILBOX || folder.hidden || folder.guard || limit <= 0) return 0
        val days = ctx.account.bodyDays
        val since = if (days == 0) 0L else ctx.store.now() - days * DAY_MS
        val rows = ctx.store.messages.needingBodies(folder.id, since, limit + skips.skipped)
        val batches = skips.batches(rows, limit, BATCH)
        if (batches.isEmpty()) return 0
        Ops.selectChecked(ctx, folder)
        return batches.sumOf { fetchGuarded(ctx, it, skips) }
    }

    /**
     * [fetchBatch], and a failed batch goes to [skips] (PLICO-SEC-001, PLICO-PERF-001). A NO or BAD keeps the
     * connection, so the rows of the batch are tried again alone at once. A protocol error (a response that the
     * reader refuses) closes it, so the next connection tries them alone.
     */
    private fun fetchGuarded(ctx: OpContext, batch: List<MessageRef>, skips: BodySkips): Int = try {
        fetchBatch(ctx, batch).also { skips.passed(batch) }
    } catch (e: ImapProtocolException) {
        skips.failed(batch)
        throw e
    } catch (e: ImapNoException) {
        // A busy or throttled server: the session backs off, as before.
        if (OpErrors.classify(e) == Failure.TRANSIENT) throw e
        retryAlone(ctx, batch, skips)
    } catch (_: ImapBadException) {
        retryAlone(ctx, batch, skips)
    }

    private fun retryAlone(ctx: OpContext, batch: List<MessageRef>, skips: BodySkips): Int {
        skips.failed(batch)
        if (batch.size == 1) return 0
        return batch.sumOf { row ->
            ctx.progress()
            fetchGuarded(ctx, listOf(row), skips)
        }
    }

    /**
     * Two round trips per batch: the structures, then the text parts (one FETCH per set of sections). Returns
     * how many bodies it saved.
     */
    private fun fetchBatch(ctx: OpContext, rows: List<MessageRef>): Int {
        val byUid = rows.associateBy { it.uid }
        val plans = HashMap<Long, BodyPlan>()
        ctx.client.uidFetch(UidSet.of(byUid.keys), "BODYSTRUCTURE") { item ->
            if (item.uid in byUid) item.bodyStructure?.let { plans[item.uid] = BodyPlan(parseBodyStructure(it)) }
        }
        var saved = 0
        for ((sections, group) in plans.entries.groupBy { it.value.sections }) {
            if (sections.isEmpty()) continue
            // A server refuses a long command line (Dovecot: 64 KiB), so the items go in lines of about 4 KB.
            val lines = runs(sections, MAX_ITEM_CHARS) { it.length + ITEM_CHARS }.map { run ->
                run.joinToString(" ") { "BODY.PEEK[$it]" }
            }
            // A response stays far below the reader's 64 MiB limit, which would otherwise fail on every retry.
            // Each run is saved before the next one downloads: at most about 8 MB of raw text in memory
            // (PLICO-PERF-005).
            for (run in runs(group, MAX_FETCH_BYTES) { it.value.bytes }) {
                val uids = run.mapTo(HashSet()) { it.key }
                val data = HashMap<Long, MutableMap<String, ByteArray>>()
                for (items in lines) {
                    ctx.client.uidFetch(UidSet.of(uids), items) { item ->
                        if (item.uid in uids) data.getOrPut(item.uid) { HashMap() }.putAll(item.sections)
                    }
                }
                for ((uid, plan) in run) saved += save(ctx, byUid.getValue(uid), plan, data[uid].orEmpty())
            }
        }
        for ((uid, plan) in plans) {
            if (plan.sections.isEmpty()) saved += save(ctx, byUid.getValue(uid), plan, emptyMap())
        }
        return saved
    }

    /** Assembles and stores one body; 1 when saved, 0 when none of its text parts arrived. */
    private fun save(ctx: OpContext, row: MessageRef, plan: BodyPlan, data: Map<String, ByteArray>): Int {
        val body = plan.assemble(data) ?: return 0
        ctx.store.messages.saveBody(row.id, body.html, body.text, body.text, body.snippet, body.attachments)
        return 1
    }
}

/**
 * The rows whose body prefetch failed on one session; pure, loop-only. A row of a failed batch is fetched alone
 * next time, and a row that fails alone is skipped until the process restarts, so that one bad message cannot
 * stop the cycle before IDLE on each connection. Opening the message still loads its body ([Bodies.fetch]).
 */
internal class BodySkips {
    private val suspects = HashSet<Long>()
    private val skips = HashSet<Long>()

    /** How many rows are skipped. */
    val skipped: Int get() = skips.size

    /** At most [limit] of [rows] that are not skipped, in batches of at most [size]; a suspect row goes alone. */
    fun batches(rows: List<MessageRef>, limit: Int, size: Int): List<List<MessageRef>> {
        val (alone, other) = rows.filter { it.id !in skips }.take(limit).partition { it.id in suspects }
        return alone.map { listOf(it) } + other.chunked(size)
    }

    /** The fetch of [batch] ended without an error. */
    fun passed(batch: List<MessageRef>) {
        batch.forEach { suspects -= it.id }
    }

    /** The fetch of [batch] failed: its rows go alone next time; a row that was alone is skipped. */
    fun failed(batch: List<MessageRef>) {
        val row = batch.singleOrNull()
        if (row == null) {
            batch.forEach { suspects += it.id }
        } else {
            suspects -= row.id
            skips += row.id
        }
    }
}

private const val MAX_FETCH_BYTES = 8L * 1024 * 1024

/** Characters of `BODY.PEEK[...]` items in one FETCH command. */
private const val MAX_ITEM_CHARS = 4096L

/** Characters of one item without its section: `BODY.PEEK[]` and a space. */
private const val ITEM_CHARS = 12L

/** Splits [items] into runs whose [weight] adds up to at most [max]; one heavy item runs alone. */
private inline fun <T> runs(items: List<T>, max: Long, weight: (T) -> Long): List<List<T>> {
    val runs = ArrayList<List<T>>()
    var run = ArrayList<T>()
    var total = 0L
    for (item in items) {
        val w = weight(item)
        if (run.isNotEmpty() && total + w > max) {
            runs += run
            run = ArrayList()
            total = 0
        }
        run += item
        total += w
    }
    if (run.isNotEmpty()) runs += run
    return runs
}

/** A body ready for MessageDao.saveBody. [html] is not sanitized yet: the message view does that. */
internal data class StoredBody(val html: String?, val text: String, val snippet: String, val attachments: List<NewAttachment>)

/** What to download for one message and how to store it; pure. */
internal class BodyPlan(root: MimePart) {
    private val parts = root.displayParts()

    /**
     * Text parts small enough to download, at most [MAX_TEXT_PARTS] and at most [MAX_FETCH_BYTES] in total, so that
     * one message also keeps the run bound (PLICO-PERF-005); the others are listed as attachments.
     */
    private val texts = parts.textParts.filter { it.size <= MAX_TEXT_PART }.take(MAX_TEXT_PARTS).let { small ->
        var sum = 0L
        small.takeWhile { sum += it.size; sum <= MAX_FETCH_BYTES }
    }

    /** IMAP sections to fetch, in document order. */
    val sections: List<String> = texts.map { it.section }

    /** Encoded size of those sections. */
    val bytes: Long = texts.sumOf { it.size }

    /**
     * Joins the downloaded [data] (keyed by section) into a body. Null when text parts were planned but none
     * arrived (the message was expunged meanwhile).
     */
    fun assemble(data: Map<String, ByteArray>): StoredBody? {
        val decoded = texts.mapNotNull { part ->
            data[part.section]?.let { raw ->
                part to TransferDecoding.text(TransferDecoding.decode(raw, part.encoding), part.params["charset"])
            }
        }
        if (texts.isNotEmpty() && decoded.isEmpty()) return null
        val joined = HtmlSanitizer.join(decoded, parts.inline)
        val unreferenced = joined.unreferencedImages.toSet()
        val listed = (parts.attachments + (parts.textParts - texts.toSet()) + unreferenced).sortedWith(sectionOrder)
        val attachments = listed.map { it.toAttachment(inline = false) } +
            parts.inline.filter { it !in unreferenced }.map { it.toAttachment(inline = true) }
        return StoredBody(joined.html, joined.text, HtmlSanitizer.snippet(joined.text), attachments)
    }

    private fun MimePart.toAttachment(inline: Boolean) =
        NewAttachment(section, fileName ?: defaultName(this), mimeType, size, encoding, contentId, inline)

    companion object {
        const val MAX_TEXT_PART = 2L * 1024 * 1024

        /** A crafted message can have thousands of text parts; each one costs a FETCH item (PLICO-SEC-001). */
        const val MAX_TEXT_PARTS = 50

        /** A name for a part that has none, e.g. "message.eml", "image.png"; else "attachment". */
        fun defaultName(part: MimePart): String = when {
            part.mimeType == "message/rfc822" -> "message.eml"
            part.mimeType == "text/calendar" -> "invite.ics"
            part.mimeType == "text/html" -> "message.html"
            part.mimeType == "text/plain" -> "message.txt"
            part.type == "image" && part.subtype.all { it.isLetterOrDigit() } -> "image.${part.subtype}"
            else -> "attachment"
        }

        /** Document order of IMAP sections: "1.2" < "1.10" < "2". */
        private val sectionOrder = Comparator<MimePart> { a, b ->
            val x = a.section.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.section.split('.').map { it.toIntOrNull() ?: 0 }
            x.zip(y).firstOrNull { (p, q) -> p != q }?.let { (p, q) -> p.compareTo(q) } ?: x.size.compareTo(y.size)
        }
    }
}
