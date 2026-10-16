package app.plico.mail.mime

/**
 * One node of a parsed BODYSTRUCTURE. [type], [subtype], [encoding], [disposition] and parameter names are
 * lower-case; parameter values are decoded. [contentId] has no angle brackets. [section] is the IMAP section
 * number ("1", "1.2"); for the top-level multipart it is "".
 */
data class MimePart(
    val section: String,
    val type: String,
    val subtype: String,
    val params: Map<String, String>,
    val contentId: String?,
    val encoding: String,
    val size: Long,
    val disposition: String?,
    val dispositionParams: Map<String, String>,
    val children: List<MimePart>,
) {
    val mimeType: String get() = "$type/$subtype"
    val isMultipart: Boolean get() = type == "multipart"

    /** Disposition `filename`, else Content-Type `name`; decoded. Null when neither is present or both are blank. */
    val fileName: String?
        get() = dispositionParams["filename"]?.takeIf { it.isNotBlank() } ?: params["name"]?.takeIf { it.isNotBlank() }
}

/** What the message view shows. Text parts are in display order. */
data class DisplayParts(
    val html: List<MimePart>,
    val plain: List<MimePart>,
    val inline: List<MimePart>,
    val attachments: List<MimePart>,
) {
    /** [html] and [plain] merged in document order, for joining into one body (plain parts HTML-escaped). */
    val textParts: List<MimePart> get() = (html + plain).sortedWith(sectionOrder)
}

/** Document order of IMAP section numbers: "1.2" < "1.10" < "2". */
internal val sectionOrder: Comparator<MimePart> = Comparator { a, b ->
    val x = a.section.split('.')
    val y = b.section.split('.')
    (0 until minOf(x.size, y.size)).firstNotNullOfOrNull { i ->
        (x[i].toIntOrNull() ?: 0).compareTo(y[i].toIntOrNull() ?: 0).takeIf { it != 0 }
    } ?: x.size.compareTo(y.size)
}
