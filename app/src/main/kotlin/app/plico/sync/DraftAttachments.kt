package app.plico.sync

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import app.plico.R
import app.plico.account.traits
import app.plico.data.DraftAttachment
import app.plico.html.IncomingUris
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Files from other apps for a draft (DESIGN.md §10, §11.6, privacy PS2/PS17): only allowed content URIs, copied
 * at once into the draft's outbox dir with the provider's size cap; images re-encoded without metadata.
 */
internal object DraftAttachments {
    private const val SNIFF_BYTES = 4096
    private const val COPY_BUFFER = 64 * 1024
    private const val OCTET_STREAM = "application/octet-stream"
    private const val DEFAULT_NAME = "attachment"

    /** Adds [uri] to draft [draftId]. Blocking. Throws [ActionError] with a text for the user. */
    fun add(store: OpStore, draftId: Long, uri: Uri, originalQuality: Boolean): DraftAttachment {
        val context = store.context
        if (!IncomingUris.allowed(context, uri)) throw ActionError(store.text(R.string.ops_error_file_not_allowed))
        val draft = store.drafts.get(draftId) ?: throw ActionError(store.text(R.string.ops_error_message_gone))
        val account = store.accounts.get(draft.accountId) ?: throw ActionError(store.text(R.string.ops_error_message_gone))
        val limit = account.traits.maxMessageBytes
        val resolver = context.contentResolver
        val name = displayName(resolver, uri) ?: DEFAULT_NAME
        val declared = try {
            resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
        val created = ArrayList<File>()
        try {
            val raw = store.files.newOutboxFile(draftId, name).also(created::add)
            copy(store, resolver, uri, raw, limit)
            val head = raw.inputStream().use { it.readNBytes(SNIFF_BYTES) }
            val type = ImageRules.sniff(head) ?: declared?.takeIf { it.isNotEmpty() && it != OCTET_STREAM }
            val image = type?.startsWith("image/") == true
            val output = if (image && !originalQuality) ImageRules.output(type, head) else null
            val shown = if (image) ImageRules.fileName(name, type, output, draft.attachments.map { it.name }) else name
            val file = if (output == null) raw else cleaned(store, draftId, raw, shown, output).also(created::add)
            val mime = output?.mime ?: type ?: OCTET_STREAM
            if (file.length() > limit) throw tooLarge(store, limit)
            val attachment = DraftAttachment(file.name, shown, mime, file.length(), image && output == null)
            if (!store.updateDraft(draftId) { it.copy(attachments = it.attachments + attachment) }) {
                throw ActionError(store.text(R.string.ops_error_draft_locked))
            }
            created -= file
            return attachment
        } finally {
            created.forEach(File::delete)
        }
    }

    /**
     * [raw] re-encoded as [output] into a new outbox file [name], without metadata. An image that the platform
     * cannot re-encode is refused: sent as it is, it would keep its location and camera data (privacy PS17).
     */
    internal fun cleaned(store: OpStore, draftId: Long, raw: File, name: String, output: ImageRules.Output): File {
        val file = store.files.newOutboxFile(draftId, name)
        if (ImageEncoder.encode(raw, file, output)) return file
        file.delete()
        throw ActionError(store.text(R.string.ops_error_image_not_cleaned))
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0)?.trim()?.ifEmpty { null } else null
        }
    } catch (_: Exception) {
        null
    }

    /** Copies [uri] into [target]; more than [limit] bytes is an error. */
    private fun copy(store: OpStore, resolver: ContentResolver, uri: Uri, target: File, limit: Long) {
        val unreadable = ActionError(store.text(R.string.ops_error_file_unreadable))
        val input = try {
            resolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: throw unreadable
        try {
            input.use { source ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw tooLarge(store, limit)
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (_: IOException) {
            throw unreadable
        }
    }

    private fun tooLarge(store: OpStore, limit: Long) =
        ActionError(store.text(R.string.ops_error_too_large, Ops.formatSize(store, limit)))
}
