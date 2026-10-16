package app.plico.sync

import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.MessageRef
import app.plico.data.NewAttachment
import app.plico.data.list
import app.plico.html.HtmlSanitizer
import app.plico.mail.imap.UidSet
import app.plico.mail.mime.MimePart
import app.plico.mail.mime.TransferDecoding
import app.plico.mail.mime.displayParts
import app.plico.mail.mime.parseBodyStructure

/**
 * Message bodies (DESIGN.md §7.2 step 7): BODYSTRUCTURE, then the display text parts of at most 2 MB each,
 * joined into one body with its search text, snippet and attachment rows. Inline images and attachments
 * download on demand ([Attachments]).
 */
object Bodies {
    private const val BATCH = 25
    private const val DAY_MS = 86_400_000L

    /** Fetches BODYSTRUCTURE + display text parts for [messageIds] and stores body, attachments, snippet and FTS. */
    suspend fun fetch(ctx: SyncContext, messageIds: List<Long>): Unit = fetch(SessionOpContext(ctx), messageIds)

    /** Fetches missing bodies in the body window of [folder], at most [limit] messages. Returns how many. */
    suspend fun fetchWindow(ctx: SyncContext, folder: Folder, limit: Int = 25): Int =
        fetchWindow(SessionOpContext(ctx), folder, limit)

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

    internal fun fetchWindow(ctx: OpContext, folder: Folder, limit: Int): Int {
        if (folder.kind != FolderKind.MAILBOX || folder.hidden || folder.guard || limit <= 0) return 0
        val days = ctx.account.bodyDays
        val since = if (days == 0) 0L else ctx.store.now() - days * DAY_MS
        val rows = ctx.store.db.sql.list(
            "SELECT id, folder_id, uid FROM message WHERE folder_id = ? AND body = 0 AND hidden = 0 AND date >= ? " +
                "ORDER BY date DESC LIMIT $limit",
            folder.id, since,
        ) { c -> MessageRef(c.getLong(0), c.getLong(1), c.getLong(2)) }
        if (rows.isEmpty()) return 0
        Ops.selectChecked(ctx, folder)
        rows.chunked(BATCH).forEach { fetchBatch(ctx, it) }
        return rows.size
    }

    /** Two round trips per batch: the structures, then the text parts (one FETCH per set of sections). */
    private fun fetchBatch(ctx: OpContext, rows: List<MessageRef>) {
        val byUid = rows.associateBy { it.uid }
        val plans = HashMap<Long, BodyPlan>()
        ctx.client.uidFetch(UidSet.of(byUid.keys), "BODYSTRUCTURE") { item ->
            if (item.uid in byUid) item.bodyStructure?.let { plans[item.uid] = BodyPlan(parseBodyStructure(it)) }
        }
        val data = HashMap<Long, MutableMap<String, ByteArray>>()
        for ((sections, group) in plans.entries.groupBy { it.value.sections }) {
            if (sections.isEmpty()) continue
            val items = sections.joinToString(" ") { "BODY.PEEK[$it]" }
            // A response stays far below the reader's 64 MiB limit, which would otherwise fail on every retry.
            for (uids in byWeight(group.map { it.key to it.value.bytes })) {
                ctx.client.uidFetch(UidSet.of(uids), items) { item ->
                    if (item.uid in plans) data.getOrPut(item.uid) { HashMap() }.putAll(item.sections)
                }
            }
        }
        for ((uid, plan) in plans) {
            val body = plan.assemble(data[uid].orEmpty()) ?: continue
            ctx.store.messages.saveBody(byUid.getValue(uid).id, body.html, body.text, body.text, body.snippet, body.attachments)
        }
    }
}

private const val MAX_FETCH_BYTES = 8L * 1024 * 1024

/** Splits (uid, size) pairs into runs of at most [MAX_FETCH_BYTES]; one large item runs alone. */
private fun byWeight(items: List<Pair<Long, Long>>): List<List<Long>> {
    val runs = ArrayList<List<Long>>()
    var run = ArrayList<Long>()
    var total = 0L
    for ((uid, size) in items) {
        if (run.isNotEmpty() && total + size > MAX_FETCH_BYTES) {
            runs += run
            run = ArrayList()
            total = 0
        }
        run += uid
        total += size
    }
    if (run.isNotEmpty()) runs += run
    return runs
}

/** A body ready for MessageDao.saveBody. [html] is not sanitized yet: the message view does that. */
internal data class StoredBody(val html: String?, val text: String, val snippet: String, val attachments: List<NewAttachment>)

/** What to download for one message and how to store it; pure. */
internal class BodyPlan(root: MimePart) {
    private val parts = root.displayParts()

    /** Text parts small enough to download; larger ones are listed as attachments. */
    private val texts = parts.textParts.filter { it.size <= MAX_TEXT_PART }

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
