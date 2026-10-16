package app.plico.sync

import app.plico.data.Files
import app.plico.mail.imap.UidSet
import app.plico.mail.mime.TransferDecoding
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Attachment downloads (DESIGN.md §7.8, protocol PS20): 1 MB partial fetches into a temp file, then a
 * streaming decode into the attachment cache, so no part is held in memory whole.
 */
object Attachments {
    private const val CHUNK = 1_048_576

    /** Chunked download into the attachment cache (DESIGN §7.8). */
    suspend fun load(ctx: SyncContext, attachmentId: Long, onProgress: (Float) -> Unit): File =
        load(SessionOpContext(ctx), attachmentId, onProgress)

    internal fun load(ctx: OpContext, attachmentId: Long, onProgress: (Float) -> Unit): File {
        val store = ctx.store
        val attachment = store.messages.attachment(attachmentId) ?: throw FileNotFoundException("Attachment is gone")
        val target = store.files.attachment(attachment.messageId, attachment.id)
        // The decode writes a temp file and renames it, so an existing file is complete.
        if (target.isFile) return target
        val message = store.messages.get(attachment.messageId) ?: throw FileNotFoundException("Message is gone")
        val folder = store.folders.get(message.folderId) ?: throw FileNotFoundException("Folder is gone")
        Ops.selectChecked(ctx, folder)
        val raw = store.files.attachmentPart(message.id, attachment.id)
        val decoded = Files.inside(target.parentFile!!, "${attachment.id}.tmp")
        try {
            raw.outputStream().use { out ->
                var offset = 0L
                while (true) {
                    var chunk: ByteArray? = null
                    ctx.client.uidFetch(UidSet.of(message.uid), "BODY.PEEK[${attachment.section}]<$offset.$CHUNK>") { item ->
                        // The server echoes the origin (BODY[2]<0>); accept a reply without it too.
                        val section = item.sections["${attachment.section}<$offset>"] ?: item.sections[attachment.section]
                        if (item.uid == message.uid && section != null) chunk = section
                    }
                    val bytes = chunk ?: if (offset == 0L) throw FileNotFoundException("Message is gone") else break
                    out.write(bytes)
                    offset += bytes.size
                    if (attachment.size > 0) onProgress((offset.toFloat() / attachment.size).coerceAtMost(1f))
                    ctx.progress()
                    if (bytes.size < CHUNK) break
                }
            }
            TransferDecoding.decodingStream(raw.inputStream().buffered(), attachment.encoding).use { input ->
                decoded.outputStream().use { input.copyTo(it) }
            }
            if (!decoded.renameTo(target)) throw IOException("Cannot store the attachment")
            onProgress(1f)
            return target
        } finally {
            raw.delete()
            decoded.delete()
        }
    }
}
