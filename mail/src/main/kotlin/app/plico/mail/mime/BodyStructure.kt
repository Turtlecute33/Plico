package app.plico.mail.mime

import app.plico.mail.imap.ImapValue
import app.plico.mail.imap.listOrEmpty
import java.util.Locale

/** Deeper multiparts keep no children (Dovecot itself stops at 100 levels). */
private const val MAX_DEPTH = 100

/**
 * Parses an IMAP BODYSTRUCTURE (or BODY) value (RFC 3501 §7.4.2, RFC 9051), with Gmail and Dovecot quirks.
 * See DESIGN.md §4.5.
 *
 * Tolerant and never throws: missing or NIL fields take RFC 2045 defaults (a value that is not a list is an
 * empty `text/plain` part), extension data is optional, and a multipart without a subtype is `mixed`. The
 * body inside a `message/rfc822` part is not parsed: that part is an attachment and has no children.
 */
fun parseBodyStructure(value: ImapValue): MimePart = body(value.listOrEmpty(), "", 0)

/**
 * Chooses the parts to display. See DESIGN.md §4.5 and `protocol PS19`.
 *
 * - `alternative`: the last child that gives HTML, else the last that gives plain text. Children that give
 *   no text at all (a `text/calendar` invite) are kept.
 * - `related`: the root (the `start` parameter, else the first child), then the other children as in
 *   `mixed`, except that every image with a Content-ID is inline.
 * - `signed`: the first child. `report`: the first child; every other part is an attachment.
 * - `mixed` and unknown subtypes: every child in order. Inline `text/html` and `text/plain` parts (no
 *   `attachment` disposition, no filename) are text; images with a Content-ID and no `attachment`
 *   disposition are inline; everything else is an attachment.
 *
 * Without HTML no cid reference can show an inline image, so such images become attachments.
 * Attachments are in document order.
 */
fun MimePart.displayParts(): DisplayParts {
    val out = Collector().apply { add(this@displayParts) }
    return if (out.html.isEmpty()) {
        DisplayParts(emptyList(), out.plain, emptyList(), (out.attachments + out.inline).sortedWith(sectionOrder))
    } else {
        DisplayParts(out.html, out.plain, out.inline, out.attachments.sortedWith(sectionOrder))
    }
}

/**
 * [displayParts] once the HTML is known: [referencedCids] are the Content-IDs its `cid:` URLs name (as in
 * [MimePart.contentId], without angle brackets). An inline image that the HTML does not reference becomes
 * an attachment, because nothing else can show it.
 */
fun MimePart.displayParts(referencedCids: Set<String>): DisplayParts {
    val parts = displayParts()
    val (shown, unused) = parts.inline.partition { it.contentId in referencedCids }
    if (unused.isEmpty()) return parts
    return parts.copy(inline = shown, attachments = (parts.attachments + unused).sortedWith(sectionOrder))
}

private fun body(items: List<ImapValue>, section: String, depth: Int): MimePart = when (items.firstOrNull()) {
    is ImapValue.ListV -> multipart(items, section, depth)
    else -> single(items, section.ifEmpty { "1" })
}

/** `body-type-mpart`: one or more bodies, the subtype, then optional params, disposition, language, location. */
private fun multipart(items: List<ImapValue>, section: String, depth: Int): MimePart {
    val bodies = items.takeWhile { it is ImapValue.ListV }
    val children = if (depth >= MAX_DEPTH) emptyList() else bodies.mapIndexed { i, child ->
        body(child.listOrEmpty(), if (section.isEmpty()) "${i + 1}" else "$section.${i + 1}", depth + 1)
    }
    val ext = items.drop(bodies.size)
    val (disposition, dispositionParams) = disposition(ext.getOrNull(2))
    return MimePart(
        section = section,
        type = "multipart",
        subtype = ext.getOrNull(0).lower() ?: "mixed",
        params = params(ext.getOrNull(1)),
        contentId = null,
        encoding = "7bit",
        // BODYSTRUCTURE gives a multipart no size; the sum of its children is close.
        size = children.sumOf { it.size },
        disposition = disposition,
        dispositionParams = dispositionParams,
        children = children,
    )
}

/** `body-type-1part`: type, subtype, params, id, description, encoding, size, type fields, extension data. */
private fun single(items: List<ImapValue>, section: String): MimePart {
    val type = items.getOrNull(0).lower() ?: "text"
    // Extension data: md5, disposition, language, location.
    val (disposition, dispositionParams) = disposition(items.getOrNull(extensionStart(items, type) + 1))
    return MimePart(
        section = section,
        type = type,
        subtype = items.getOrNull(1).lower() ?: if (type == "text") "plain" else "octet-stream",
        params = params(items.getOrNull(2)),
        contentId = items.getOrNull(3).str()?.let(::bareId),
        encoding = items.getOrNull(5).lower() ?: "7bit",
        size = items.getOrNull(6).str()?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: 0,
        disposition = disposition,
        dispositionParams = dispositionParams,
        children = emptyList(),
    )
}

/**
 * Index of the md5 field. After the seven basic fields come the envelope and body of a parsed
 * `message/rfc822` (Dovecot sends a base64 one without them), then the line count of text and messages.
 * The fields are found by their form, so a missing one does not shift the extension data.
 */
private fun extensionStart(items: List<ImapValue>, type: String): Int {
    var i = 7
    if (type == "message" && items.getOrNull(i) is ImapValue.ListV) i += 2
    if (items.getOrNull(i) is ImapValue.Atom) i++
    return i
}

/** `(name (params))`, NIL, or a bare name. */
private fun disposition(value: ImapValue?): Pair<String?, Map<String, String>> = when (value) {
    is ImapValue.ListV -> value.items.getOrNull(0).lower() to params(value.items.getOrNull(1))
    else -> value.lower() to emptyMap()
}

/** `(name value ...)` or NIL. Names lower-case; values decoded (RFC 2231 and 2047). Pairs with NIL are dropped. */
private fun params(value: ImapValue?): Map<String, String> {
    val pairs = value?.listOrEmpty().orEmpty().chunked(2).mapNotNull { pair ->
        val name = pair[0].str() ?: return@mapNotNull null
        name to (pair.getOrNull(1).str() ?: return@mapNotNull null)
    }
    return if (pairs.isEmpty()) emptyMap() else ContentType.decodeParams(pairs)
}

/** Text of an atom or string. Raw 8-bit strings are UTF-8, else windows-1252. */
private fun ImapValue?.str(): String? = when (this) {
    is ImapValue.Str -> TransferDecoding.text(bytes, null)
    is ImapValue.Atom -> text
    else -> null
}

private fun ImapValue?.lower(): String? = str()?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }

/** `<id@host>` -> `id@host`; null when blank. */
private fun bareId(value: String): String? = value.trim().removePrefix("<").removeSuffix(">").trim().ifEmpty { null }

private val MimePart.isCidImage: Boolean get() = type == "image" && contentId != null

private fun MimePart.leaves(): List<MimePart> = if (isMultipart) children.flatMap { it.leaves() } else listOf(this)

/** The parts of one subtree by role, in display order. Mutable, so that big flat messages stay linear. */
private class Collector {
    val html = ArrayList<MimePart>()
    val plain = ArrayList<MimePart>()
    val inline = ArrayList<MimePart>()
    val attachments = ArrayList<MimePart>()

    private val hasText: Boolean get() = html.isNotEmpty() || plain.isNotEmpty()

    fun add(part: MimePart) {
        val children = part.children
        when {
            !part.isMultipart -> leaf(part)
            part.subtype == "alternative" -> alternative(children)
            part.subtype == "related" -> related(children, part.params["start"]?.let(::bareId))
            part.subtype == "signed" -> children.firstOrNull()?.let(::add)
            part.subtype == "report" -> {
                children.firstOrNull()?.let(::add)
                children.drop(1).forEach { attachments += it.leaves() }
            }
            else -> children.forEach(::add)
        }
    }

    private fun leaf(part: MimePart) {
        val attached = part.disposition == "attachment"
        val text = !attached && part.fileName == null
        when {
            text && part.mimeType == "text/html" -> html += part
            text && part.mimeType == "text/plain" -> plain += part
            !attached && part.isCidImage -> inline += part
            else -> attachments += part
        }
    }

    private fun alternative(children: List<MimePart>) {
        val options = children.map { Collector().apply { add(it) } }
        val best = options.lastOrNull { it.html.isNotEmpty() } ?: options.lastOrNull { it.plain.isNotEmpty() }
        options.filter { it === best || !it.hasText }.forEach(::addAll)
    }

    private fun related(children: List<MimePart>, start: String?) {
        val root = children.firstOrNull { start != null && it.contentId == start } ?: children.firstOrNull() ?: return
        add(root)
        for (child in children) {
            when {
                child === root -> Unit
                child.isCidImage -> inline += child
                else -> add(child)
            }
        }
    }

    private fun addAll(other: Collector) {
        html += other.html
        plain += other.plain
        inline += other.inline
        attachments += other.attachments
    }
}
