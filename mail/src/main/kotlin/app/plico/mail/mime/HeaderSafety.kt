package app.plico.mail.mime

/** Rules that keep attacker-controlled text from changing the structure of outgoing mail. */
object HeaderSafety {
    /** `<[^\s<>]+>`; the `@` check is separate so that matching stays linear on hostile input. */
    private val bracketed = Regex("<[^\\s<>]+>")

    /** C0 controls (CR and LF included) and DEL become a space; runs of spaces collapse; trimmed. */
    fun clean(value: String): String {
        val out = StringBuilder(value.length)
        var gap = false
        for (c in value) {
            if (c <= ' ' || c == '\u007F') {
                gap = true
            } else {
                if (gap && out.isNotEmpty()) out.append(' ')
                gap = false
                out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * Message-ID tokens `<x@y>` (`<[^\s<>]+@[^\s<>]+>`) from an In-Reply-To or References value, in order and
     * without duplicates; everything else is dropped.
     */
    fun messageIds(value: String): List<String> = bracketed.findAll(value).map { it.value }
        .filter { it.indexOf('@', 2) in 2..it.length - 3 }
        .distinct()
        .toList()
}
