package app.plico.html

/**
 * The methods of a `List-Unsubscribe` header (RFC 2369): the URIs in angle brackets, else the comma-separated
 * values. [web] is the first https page, else the first http page; [mail] is the first mailto with an address.
 * The UI and the RFC 8058 POST read the header through here, so that they agree.
 */
data class ListUnsubscribe(val web: String?, val mail: Mailto?) {
    /** The page for the one-click POST: only an https one (RFC 8058). */
    val oneClick: String? get() = web?.takeIf { it.startsWith(HTTPS, ignoreCase = true) }

    companion object {
        private val BRACKETED = Regex("<([^<>]*)>")
        private const val HTTPS = "https://"

        /** Null when [header] has no usable method. */
        fun parse(header: String?): ListUnsubscribe? {
            if (header.isNullOrBlank()) return null
            val uris = BRACKETED.findAll(header).map { it.groupValues[1].trim() }.toList()
                .ifEmpty { header.split(',').map(String::trim) }
                .filter { it.isNotEmpty() && it.none(Char::isWhitespace) }
            val web = uris.firstOrNull { it.startsWith(HTTPS, ignoreCase = true) }
                ?: uris.firstOrNull { it.startsWith("http://", ignoreCase = true) }
            val mail = uris.firstNotNullOfOrNull { uri -> Mailto.parse(uri)?.takeIf { it.to.isNotEmpty() } }
            return if (web == null && mail == null) null else ListUnsubscribe(web, mail)
        }
    }
}
