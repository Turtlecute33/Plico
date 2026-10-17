package app.plico.html

import android.content.Context

/**
 * Public Suffix List matcher (DESIGN.md §11.3, privacy PS6): ICANN and PRIVATE sections, normal, wildcard and
 * exception rules, as in the algorithm at publicsuffix.org/list. Hosts may be Unicode or ASCII; results are ASCII.
 *
 * @param text the content of `public_suffix_list.dat`.
 */
class Psl(text: String) {
    /** One rule of the list that matched a host: its public [suffix] and its section ([private] = PRIVATE DOMAINS). */
    class Rule(val suffix: String, val private: Boolean)

    // Rule (ASCII, without `*.` or `!`) → true when it is in the PRIVATE DOMAINS section.
    private val rules = HashMap<String, Boolean>()
    private val wildcards = HashMap<String, Boolean>()
    private val exceptions = HashMap<String, Boolean>()

    init {
        var private = false
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//")) {
                when {
                    PRIVATE_BEGIN in trimmed -> private = true
                    PRIVATE_END in trimmed -> private = false
                }
                continue
            }
            val rule = trimmed.substringBefore(' ').substringBefore('\t')
            if (rule.isEmpty()) continue
            when {
                rule.startsWith('!') -> Idn.toAscii(rule.substring(1))?.let { exceptions[it] = private }
                rule.startsWith("*.") -> Idn.toAscii(rule.substring(2))?.let { wildcards[it] = private }
                else -> Idn.toAscii(rule)?.let { rules[it] = private }
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
    fun hasListedSuffix(host: String): Boolean = labels(host)?.let { match(it).rule != null } == true

    /**
     * The rule of the list that decides the public suffix of [host], with its section. Null when only the
     * implicit `*` rule matches (an unlisted suffix), and for IP addresses and invalid names.
     */
    fun matchedRule(host: String): Rule? = labels(host)?.let { match(it).rule }

    /** The registrable domain of [host], else its ASCII form; for comparing two sites. */
    internal fun site(host: String): String =
        registrableDomain(host) ?: Idn.toAscii(host) ?: host.lowercase()

    /** Registrable domains of the domain names in free text (link text, display names), in order. */
    internal fun domainsIn(text: String): List<String> = domainLike.findAll(text)
        .mapNotNull { m -> Idn.toAscii(m.value)?.takeIf { !Idn.isIpAddress(it) && hasListedSuffix(it) } }
        .mapNotNull(::registrableDomain)
        .toList()

    /** [size] labels of the host form the public suffix; [rule] null = only the implicit `*` rule matched. */
    private class Match(val size: Int, val rule: Rule?)

    private fun match(labels: List<String>): Match {
        fun suffix(from: Int) = labels.subList(from, labels.size).joinToString(".")
        // An exception rule wins over every other rule; its leftmost label is not part of the suffix.
        for (i in labels.indices) {
            val exception = suffix(i)
            exceptions[exception]?.let { return Match(labels.size - i - 1, Rule(suffix(i + 1), it)) }
        }
        // Longest match first.
        for (i in labels.indices) {
            val wildcard = if (i + 1 < labels.size) wildcards[suffix(i + 1)] else null
            val section = wildcard ?: rules[suffix(i)]
            if (section != null) return Match(labels.size - i, Rule(suffix(i), section))
        }
        return Match(1, null)
    }

    private fun labels(host: String): List<String>? =
        Idn.toAscii(host)?.takeIf { !Idn.isIpAddress(it) }?.split('.')

    companion object {
        private const val ASSET = "public_suffix_list.dat"
        private const val PRIVATE_BEGIN = "===BEGIN PRIVATE DOMAINS==="
        private const val PRIVATE_END = "===END PRIVATE DOMAINS==="
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
