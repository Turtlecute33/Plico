package app.plico.mail.mime

/** Rules that keep attacker-controlled text from changing the structure of outgoing mail or hiding in addresses. */
object HeaderSafety {
    /** `<[^\s<>]+>`; the `@` check is separate so that matching stays linear on hostile input. */
    private val bracketed = Regex("<[^\\s<>]+>")

    /**
     * Controls, format characters and the other code points that text layout does not show (CGJ, variation
     * selectors, plane 14). Also U+1806, which IDNA removes. The match is on code points, not on UTF-16 units.
     */
    private val hidden = Regex(
        "[\\p{Cc}\\p{Cf}\\u034F\\u17B4\\u17B5\\u1806\\u180B-\\u180F\\u2065\\uFE00-\\uFE0F\\uFFF0-\\uFFF8" +
            "\\x{E0000}-\\x{E0FFF}]",
    )

    /**
     * Each [hidden] character becomes U+FFFD, so that an address cannot look like a different address.
     * The characters are not removed, because "pay<U+200B>pal.com" must not become "paypal.com".
     */
    fun revealHidden(value: String): String = hidden.replace(value, "\uFFFD")

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
