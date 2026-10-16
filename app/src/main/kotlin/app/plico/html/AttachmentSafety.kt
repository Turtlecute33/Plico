package app.plico.html

import androidx.annotation.StringRes
import app.plico.R
import java.net.URLConnection

/** A warning on an attachment card, shown before it opens (DESIGN.md §11.5). [text] is the warning text. */
enum class AttachmentWarning(@StringRes val text: Int) {
    /** The content (magic bytes) is of another type than the declared one; the sniffed type is used. */
    TYPE_MISMATCH(R.string.html_attachment_type_mismatch),

    /** The effective type is a web page, SVG, MHTML or a program. */
    DANGEROUS_TYPE(R.string.html_attachment_dangerous_type),

    /** The name ends in an extension that installs, runs or shows web content (`.apk`, `.exe`, `.html`, …). */
    DANGEROUS_EXTENSION(R.string.html_attachment_dangerous_extension),

    /** A document or media extension followed by another one (`invoice.pdf.html`). */
    DOUBLE_EXTENSION(R.string.html_attachment_double_extension),

    /** The name contains bidi controls such as U+202E, which can reverse how it reads. */
    HIDDEN_CHARACTERS(R.string.html_attachment_hidden_characters),
}

/** The type to open an attachment with, and the warnings to show first. */
data class AttachmentCheck(val mimeType: String, val warnings: Set<AttachmentWarning>)

/** Attachment type checks (DESIGN.md §11.5, privacy PS14). */
object AttachmentSafety {
    /** Bytes from the start of a file that [sniff] needs (fewer when the file is shorter). */
    const val SNIFF_BYTES = 4096

    private const val ZIP = "application/zip"
    private const val APK = "application/vnd.android.package-archive"
    private const val EXE = "application/x-msdownload"
    private const val HTML = "text/html"
    private const val XHTML = "application/xhtml+xml"
    private const val SVG = "image/svg+xml"
    private const val MHTML = "multipart/related"

    private val dangerousTypes = setOf(HTML, XHTML, SVG, MHTML, APK, EXE)
    private val dangerousExtensions = setOf(
        "apk", "apks", "apkm", "xapk", "exe", "msi", "bat", "cmd", "js", "jar", "scr", "vbs", "ps1", "lnk", "iso",
        "img", "html", "htm", "xhtml", "xht", "svg", "mht", "mhtml", "shtml",
    )
    private val documentExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "csv",
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "tif", "tiff", "mp3", "mp4", "mov", "wav",
    )
    private const val BIDI_CONTROLS = "\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"

    /** Declared types that make no claim about the content. */
    private val genericTypes = setOf(
        "application/octet-stream", "binary/octet-stream", "application/unknown", "application/x-download",
        "application/force-download",
    )
    private val aliases = mapOf(
        "image/jpg" to "image/jpeg", "image/pjpeg" to "image/jpeg", "image/x-png" to "image/png",
        "application/x-pdf" to "application/pdf", "application/x-zip-compressed" to ZIP, "application/x-zip" to ZIP,
        "application/vnd.microsoft.portable-executable" to EXE, "application/x-dosexec" to EXE,
        "application/x-msdos-program" to EXE,
    )

    /** Declared types whose files are ZIP containers. */
    private val zipFamily = listOf(
        "zip", "openxmlformats", "opendocument", "macroenabled", "epub", "java-archive", APK, "xpinstall",
        "xpsdocument",
    )
    private val htmlTags = listOf(
        "html", "head", "body", "script", "iframe", "h1", "div", "font", "table", "a", "style", "title", "b", "br", "p",
    )
    private val headerLine = Regex("^[a-z][a-z0-9-]*:")

    /**
     * The type to use and the warnings for an attachment. A generic declared type (`application/octet-stream`)
     * makes no claim, so the type that the file name suggests stands in for it.
     *
     * @param name the sender's file name (display only).
     * @param declaredMime the part's Content-Type.
     * @param head the first [SNIFF_BYTES] bytes of the decoded file.
     */
    fun check(name: String, declaredMime: String, head: ByteArray): AttachmentCheck {
        val claimed = normalize(declaredMime)
            ?: normalize(URLConnection.guessContentTypeFromName(name.trim()).orEmpty())
        val sniffed = sniff(head)
        val mismatch = sniffed != null && claimed != null && !compatible(claimed, sniffed)
        val type = when {
            sniffed != null && (claimed == null || mismatch) -> sniffed
            else -> claimed ?: "application/octet-stream"
        }
        val parts = name.lowercase().trimEnd('.', ' ').split('.').map(String::trim)
        val extension = if (parts.size > 1) parts.last() else ""
        val inner = if (parts.size > 2) parts[parts.size - 2] else ""
        val warnings = buildSet {
            if (mismatch) add(AttachmentWarning.TYPE_MISMATCH)
            if (type in dangerousTypes) add(AttachmentWarning.DANGEROUS_TYPE)
            if (extension in dangerousExtensions) add(AttachmentWarning.DANGEROUS_EXTENSION)
            if (inner in documentExtensions && extension != inner) add(AttachmentWarning.DOUBLE_EXTENSION)
            if (name.any { it in BIDI_CONTROLS }) add(AttachmentWarning.HIDDEN_CHARACTERS)
        }
        return AttachmentCheck(type, warnings)
    }

    /** The type that the magic bytes of [head] show, or null when unknown. */
    fun sniff(head: ByteArray): String? = when {
        head.startsWith("%PDF-") -> "application/pdf"
        head.startsWith("\u0089PNG\r\n\u001A\n") -> "image/png"
        head.startsWith("\u00FF\u00D8\u00FF") -> "image/jpeg"
        head.startsWith("GIF87a") || head.startsWith("GIF89a") -> "image/gif"
        head.startsWith("RIFF") && head.startsWith("WEBP", 8) -> "image/webp"
        head.startsWith("PK\u0003\u0004") || head.startsWith("PK\u0005\u0006") -> {
            val names = String(head, Charsets.ISO_8859_1)
            if ("AndroidManifest.xml" in names || "classes.dex" in names) APK else ZIP
        }
        head.startsWith("MZ") && isPortableExecutable(head) -> EXE
        else -> markup(text(head))
    }

    /** Lower-case `type/subtype` without parameters and with aliases resolved; null for generic or invalid types. */
    private fun normalize(mime: String): String? {
        val type = mime.substringBefore(';').trim().lowercase().let { aliases[it] ?: it }
        return type.takeIf { '/' in it && it !in genericTypes }
    }

    /** The `PE\0\0` signature at the offset that the DOS header gives; "MZ" alone also starts plain text. */
    private fun isPortableExecutable(head: ByteArray): Boolean {
        if (head.size < 64) return false
        val offset = (0..3).sumOf { (head[0x3C + it].toLong() and 0xFF) shl (8 * it) }
        return offset < head.size && head.startsWith("PE\u0000\u0000", offset.toInt())
    }

    private fun compatible(declared: String, sniffed: String): Boolean = when (sniffed) {
        declared -> true
        ZIP -> zipFamily.any { it in declared }
        MHTML -> declared == "message/rfc822"
        else -> false
    }

    /** HTML, XHTML, SVG or MHTML from the first tag after an XML prolog, comments and a doctype. */
    private fun markup(text: String): String? {
        var s = text.trimStart().lowercase()
        val xml = s.startsWith("<?xml")
        while (true) {
            s = when {
                s.startsWith("<?") -> s.substringAfter("?>", "")
                s.startsWith("<!--") -> s.substringAfter("-->", "")
                s.startsWith("<!doctype svg") -> return SVG
                s.startsWith("<!doctype html") && !xml -> return HTML
                s.startsWith("<!") -> s.substringAfter('>', "")
                else -> break
            }.trimStart()
        }
        return when {
            isTag(s, "svg") -> SVG
            isTag(s, "html") -> if (xml) XHTML else HTML
            !xml && htmlTags.any { isTag(s, it) } -> HTML
            headerLine.containsMatchIn(s) && "multipart/related" in s -> MHTML
            else -> null
        }
    }

    private fun isTag(s: String, name: String): Boolean =
        s.startsWith("<$name") && (s.length == name.length + 1 || s[name.length + 1] in " \t\r\n\u000C/>")

    /** The head as text for markup sniffing: UTF-8 or UTF-16 by BOM, else ISO-8859-1. */
    private fun text(head: ByteArray): String = when {
        head.startsWith("\u00EF\u00BB\u00BF") -> String(head, 3, head.size - 3, Charsets.UTF_8)
        head.startsWith("\u00FF\u00FE") -> String(head, 2, head.size - 2, Charsets.UTF_16LE)
        head.startsWith("\u00FE\u00FF") -> String(head, 2, head.size - 2, Charsets.UTF_16BE)
        else -> String(head, Charsets.ISO_8859_1)
    }

    /** True when the bytes at [offset] are [prefix] (each char one byte, ISO-8859-1). */
    private fun ByteArray.startsWith(prefix: String, offset: Int = 0): Boolean =
        size >= offset + prefix.length && prefix.indices.all { this[offset + it] == prefix[it].code.toByte() }
}
