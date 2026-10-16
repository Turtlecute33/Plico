package app.plico.html

import android.content.Context

/**
 * Public Suffix List matcher (DESIGN.md §11.3, privacy PS6): ICANN and PRIVATE sections, normal, wildcard and
 * exception rules, as in the algorithm at publicsuffix.org/list. Hosts may be Unicode or ASCII; results are ASCII.
 *
 * @param text the content of `public_suffix_list.dat`.
 */
class Psl(text: String) {
    private val rules = HashSet<String>()
    private val wildcards = HashSet<String>()
    private val exceptions = HashSet<String>()

    init {
        for (line in text.lineSequence()) {
            val rule = line.trim().substringBefore(' ').substringBefore('\t')
            if (rule.isEmpty() || rule.startsWith("//")) continue
            when {
                rule.startsWith('!') -> Idn.toAscii(rule.substring(1))?.let(exceptions::add)
                rule.startsWith("*.") -> Idn.toAscii(rule.substring(2))?.let(wildcards::add)
                else -> Idn.toAscii(rule)?.let(rules::add)
            }
        }
    }

    /** `co.uk` for `www.example.co.uk`. Null for IP addresses and invalid names. */
    fun publicSuffix(host: String): String? {
        val labels = labels(host) ?: return null
        return labels.takeLast(match(labels).size).joinToString(".")
    }

    /**
     * `example.co.uk` for `www.example.co.uk`. Null when [host] is itself a public suffix, an IP address or
     * invalid.
     */
    fun registrableDomain(host: String): String? {
        val labels = labels(host) ?: return null
        val size = match(labels).size
        return if (labels.size > size) labels.takeLast(size + 1).joinToString(".") else null
    }

    /** True when a rule of the list (not only the implicit `*` rule) matches [host]. */
    fun hasListedSuffix(host: String): Boolean = labels(host)?.let { match(it).listed } == true

    /** The registrable domain of [host], else its ASCII form; for comparing two sites. */
    internal fun site(host: String): String =
        registrableDomain(host) ?: Idn.toAscii(host) ?: host.lowercase()

    /** Registrable domains of the domain names in free text (link text, display names), in order. */
    internal fun domainsIn(text: String): List<String> = domainLike.findAll(text)
        .mapNotNull { m -> Idn.toAscii(m.value)?.takeIf { !Idn.isIpAddress(it) && hasListedSuffix(it) } }
        .mapNotNull(::registrableDomain)
        .toList()

    private class Match(val size: Int, val listed: Boolean)

    private fun match(labels: List<String>): Match {
        fun suffix(from: Int) = labels.subList(from, labels.size).joinToString(".")
        // An exception rule wins over every other rule; its leftmost label is not part of the suffix.
        for (i in labels.indices) {
            if (suffix(i) in exceptions) return Match(labels.size - i - 1, true)
        }
        // Longest match first.
        for (i in labels.indices) {
            val wildcard = i + 1 < labels.size && suffix(i + 1) in wildcards
            if (wildcard || suffix(i) in rules) return Match(labels.size - i, true)
        }
        return Match(1, false)
    }

    private fun labels(host: String): List<String>? =
        Idn.toAscii(host)?.takeIf { !Idn.isIpAddress(it) }?.split('.')

    companion object {
        private const val ASSET = "public_suffix_list.dat"
        private val domainLike = Regex("""[\p{L}\p{N}_-]+(?:\.[\p{L}\p{N}_-]+)+""")

        @Volatile
        private var shared: Psl? = null

        /** The bundled list, parsed on first use (about 16,000 rules). Call it off the main thread. */
        fun get(context: Context): Psl = shared ?: synchronized(this) {
            shared ?: context.applicationContext.assets.open(ASSET).bufferedReader().use { Psl(it.readText()) }
                .also { shared = it }
        }
    }
}
