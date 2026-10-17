package app.plico.data

import android.content.Context
import java.io.File

/**
 * Every on-disk path the app writes (DESIGN.md §5 `Files`, `privacy PS1`): `<base>/<ownerId>/<itemId>` or
 * `<base>/<ownerId>/<safeName>`. Names from senders or other apps never become path parts as they are;
 * the original name lives only in the database.
 */
class Files internal constructor(cacheDir: File, filesDir: File) {
    constructor(context: Context) : this(context.cacheDir, context.filesDir)

    private val attBase = File(cacheDir, "att")
    private val outboxBase = File(filesDir, "outbox")
    private val logoBase = File(cacheDir, "logos")

    /** cacheDir/att/<messageId>/<attachmentId> (parent dir created) */
    fun attachment(messageId: Long, attachmentId: Long): File =
        inside(attBase, messageId.toString(), attachmentId.toString()).also { it.parentFile?.mkdirs() }

    /** cacheDir/att/<messageId>/<attachmentId>.part: the raw download before decoding (parent dir created). */
    fun attachmentPart(messageId: Long, attachmentId: Long): File =
        inside(attBase, messageId.toString(), "$attachmentId.part").also { it.parentFile?.mkdirs() }

    /** filesDir/outbox/<draftId>/ (created) */
    fun outboxDir(draftId: Long): File = inside(outboxBase, draftId.toString()).also { it.mkdirs() }

    /** filesDir/outbox/<draftId>/message.eml: the built message (DESIGN.md §7.5). No attachment gets this name. */
    fun outboxMessage(draftId: Long): File = inside(outboxDir(draftId), MESSAGE_FILE)

    /** A new empty file in the draft's outbox dir; [name] is made safe and unique. */
    fun newOutboxFile(draftId: Long, name: String): File {
        val dir = outboxDir(draftId)
        val safe = safeName(name)
        // The counter goes in front, so truncation at the end cannot make two names equal.
        return generateSequence(1) { it + 1 }
            .map { n -> inside(dir, if (n == 1) safe else safeName("$n-$safe")) }
            .first { it.name != MESSAGE_FILE && it.createNewFile() }
    }

    /** The draft ids that have an outbox dir. */
    fun outboxDraftIds(): List<Long> = outboxBase.list().orEmpty().mapNotNull(String::toLongOrNull)

    fun deleteOutbox(draftId: Long) {
        inside(outboxBase, draftId.toString()).deleteRecursively()
    }

    fun deleteAttachments(messageId: Long) {
        inside(attBase, messageId.toString()).deleteRecursively()
    }

    /** Deletes cached attachment files older than [maxAgeMs], and old dirs left empty. */
    fun pruneAttachmentCache(maxAgeMs: Long = 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        attBase.listFiles()?.forEach { dir ->
            // Deleting a file updates the dir's time, so read it first.
            val oldDir = dir.lastModified() < cutoff
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
            if (oldDir && dir.list().isNullOrEmpty()) dir.delete()
        }
    }

    /** cacheDir/logos/: the sender logo cache (DESIGN.md §12). Not created here. */
    fun logoDir(): File = logoBase

    /**
     * cacheDir/logos/<hash>.<ext> (parent dir created). [hash] is the lower-case hex SHA-256 of a domain, never
     * the domain itself; [ext] is a plain extension such as `png` or `none`.
     */
    fun logo(hash: String, ext: String): File {
        require(hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }) { "Not a SHA-256 hex" }
        require(ext.isNotEmpty() && ext.all { it in 'a'..'z' }) { "Not a plain extension" }
        return inside(logoBase, "$hash.$ext").also { it.parentFile?.mkdirs() }
    }

    companion object {
        private const val MESSAGE_FILE = "message.eml"
        private const val MAX_CHARS = 100

        /** File systems limit one name to 255 bytes. */
        private const val MAX_BYTES = 255

        // Path separators, C0/C1 controls (NUL included) and the bidi controls that can disguise an
        // extension (U+202A–202E, U+2066–2069).
        private fun unsafe(c: Char) = c == '/' || c == '\\' || c == ':' || Character.isISOControl(c) ||
            c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

        /**
         * DESIGN.md §5 `Files`: replaces separators, controls and bidi controls with `_`, strips leading dots,
         * keeps at most 100 chars (and 255 UTF-8 bytes); `attachment` when nothing is left.
         */
        fun safeName(name: String): String {
            val cleaned = buildString(name.length) { for (c in name) append(if (unsafe(c)) '_' else c) }.trimStart('.')
            var end = minOf(cleaned.length, MAX_CHARS)
            // Never split a surrogate pair, and stay within the byte limit.
            if (end < cleaned.length && Character.isLowSurrogate(cleaned[end])) end--
            while (cleaned.substring(0, end).toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                end -= if (end >= 2 && Character.isLowSurrogate(cleaned[end - 1])) 2 else 1
            }
            return cleaned.substring(0, end).ifEmpty { "attachment" }
        }

        /** [base]/[parts...] after a canonical-path check; throws SecurityException when the result leaves [base]. */
        fun inside(base: File, vararg parts: String): File {
            val file = parts.fold(base) { dir, part -> File(dir, part) }.canonicalFile
            if (!file.path.startsWith(base.canonicalPath + File.separator)) {
                throw SecurityException("Path leaves its base directory")
            }
            return file
        }
    }
}
