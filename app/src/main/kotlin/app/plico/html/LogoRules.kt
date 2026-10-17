package app.plico.html

import app.plico.account.ProviderTable
import app.plico.data.Prefs

/**
 * Pure rules for sender logos (DESIGN.md §12): which domain a sender gets a logo for, what each source gets and
 * which hosts it uses, which bytes may be decoded, and how long the cache keeps an answer.
 */
object LogoRules {
    /** Largest logo response; also the byte budget of one domain. */
    const val MAX_BYTES = 262_144

    /** Smallest and largest logo side in pixels before scaling. */
    const val MIN_PX = 32
    const val MAX_PX = 512

    /** The largest side after scaling. */
    const val TARGET_PX = 128

    /** A logo file stays fresh for 30 days. */
    const val LOGO_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** A "no logo" answer stays fresh for 7 days. */
    const val NONE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** A failed fetch blocks a new try for 10 minutes (memory only). */
    const val FAILED_TTL_MS = 10L * 60 * 1000

    /**
     * The domain whose logo [email] may show, or null. Only the registrable domain of the address's host (never
     * the address): not for IP addresses, local or single-label hosts, mixed-script or lookalike names, suffixes
     * that the [psl] does not list, PRIVATE suffixes (free sites per user, such as `x.github.io`), and the
     * domains of mailbox providers (every sender at gmail.com would get the same logo).
     */
    fun domainOf(email: String, psl: Psl): String? {
        if ('@' !in email) return null
        val host = Idn.toAscii(email.substringAfterLast('@').trim()) ?: return null
        if (Idn.isIpAddress(host) || Idn.isMixedScript(host) || Idn.isLookalike(host) || !HostRules.allowed(host)) {
            return null
        }
        val rule = psl.matchedRule(host) ?: return null
        if (rule.private) return null
        val domain = psl.registrableDomain(host) ?: return null
        return domain.takeUnless(ProviderTable::isMailboxDomain)
    }

    /** The URLs to try for [domain] (from [domainOf]), in order. Each one carries only the domain. */
    fun urls(source: Prefs.LogoSource, domain: String): List<String> = when (source) {
        Prefs.LogoSource.DUCKDUCKGO -> listOf("https://icons.duckduckgo.com/ip3/$domain.ico")
        Prefs.LogoSource.GOOGLE -> listOf("https://www.google.com/s2/favicons?domain=$domain&sz=64")
        Prefs.LogoSource.WEBSITE -> listOf("https://$domain/apple-touch-icon.png", "https://$domain/favicon.ico")
    }

    /**
     * The hosts that a logo source connects to, for About. Empty for [Prefs.LogoSource.WEBSITE]: the website of
     * each sender (and the hosts it redirects to).
     */
    fun hosts(source: Prefs.LogoSource): List<String> = when (source) {
        Prefs.LogoSource.DUCKDUCKGO -> listOf("icons.duckduckgo.com")
        Prefs.LogoSource.GOOGLE -> listOf("www.google.com", "*.gstatic.com")
        Prefs.LogoSource.WEBSITE -> emptyList()
    }

    /** The image formats that a logo may have, with the MIME types that the platform decoder reports for each. */
    enum class ImageKind(private val mimeTypes: Set<String>) {
        PNG(setOf("image/png")),
        JPEG(setOf("image/jpeg")),
        GIF(setOf("image/gif")),
        WEBP(setOf("image/webp")),

        /** Android's decoder reports `image/x-ico`; the registered and the common names are accepted too. */
        ICO(setOf("image/x-ico", "image/x-icon", "image/vnd.microsoft.icon")),
        ;

        /** True when the decoder's [mimeType] is of this format. */
        fun matches(mimeType: String?): Boolean = mimeType?.lowercase() in mimeTypes
    }

    /**
     * The format of [bytes] from their first bytes, whatever the server says. Null for all other content (SVG,
     * HTML, text, BMP, …): it is never decoded.
     */
    fun sniff(bytes: ByteArray): ImageKind? {
        fun at(offset: Int, vararg expected: Int) = bytes.size >= offset + expected.size &&
            expected.indices.all { bytes[offset + it].toInt() and 0xFF == expected[it] }
        fun ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageKind.PNG
            at(0, 0xFF, 0xD8, 0xFF) -> ImageKind.JPEG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> ImageKind.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> ImageKind.WEBP
            // ICONDIR: reserved 0, type 1 (icon), then a count of at least one image.
            at(0, 0x00, 0x00, 0x01, 0x00) && bytes.size >= 6 && (bytes[4].toInt() or bytes[5].toInt()) != 0 ->
                ImageKind.ICO
            else -> null
        }
    }

    /** True when the size of an image passes the header check: both sides from [MIN_PX] to [MAX_PX]. */
    fun sizeAllowed(width: Int, height: Int): Boolean = width in MIN_PX..MAX_PX && height in MIN_PX..MAX_PX

    /** The decode size: the largest side at most [TARGET_PX], aspect kept, never larger than the source. */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val largest = maxOf(width, height)
        if (largest <= TARGET_PX) return width to height
        val scale = TARGET_PX.toDouble() / largest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** The kind of a cache file: a logo, or the answer "this domain has no logo". */
    enum class Entry(val extension: String, val ttlMs: Long) {
        LOGO("png", LOGO_TTL_MS),
        NONE("none", NONE_TTL_MS),
    }

    /**
     * True when an [entry] written at [writtenAt] is still valid at [now]. A time in the future (the clock went
     * back) counts as expired, so that a wrong clock cannot keep an entry for ever.
     */
    fun fresh(entry: Entry, writtenAt: Long, now: Long): Boolean = now - writtenAt in 0 until entry.ttlMs

    /** True while a failed fetch at [failedAt] still blocks a new try. */
    fun failureActive(failedAt: Long, now: Long): Boolean = now - failedAt in 0 until FAILED_TTL_MS

    /** The cache entry of a file name; null for a name that the cache never writes (such a file is deleted). */
    fun entryOf(fileName: String): Entry? {
        val hash = fileName.substringBefore('.')
        if (!isHash(hash)) return null
        return Entry.entries.firstOrNull { fileName == "$hash.${it.extension}" }
    }

    /** True for a lower-case hex SHA-256: the only base name of a logo cache file. */
    fun isHash(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }
}
