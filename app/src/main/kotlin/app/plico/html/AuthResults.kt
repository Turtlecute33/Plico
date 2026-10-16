package app.plico.html

import app.plico.data.Auth

/** Reads Gmail's `Authentication-Results` header (RFC 8601; DESIGN.md §11.4, privacy PS4). */
object AuthResults {
    private const val GMAIL_AUTHSERV_ID = "mx.google.com"
    private val result = Regex("""^\s*([A-Za-z0-9_-]+)(?:\s*/\s*\d+)?\s*=\s*([A-Za-z]+)""")

    /**
     * The [Auth] value for a Gmail message. Pass only the **first** (topmost) `Authentication-Results` header:
     * Gmail adds its own at the top, and lower ones can come from the sender. The header counts only when its
     * authserv-id is `mx.google.com`. `dmarc=pass` gives [Auth.PASS]; `dmarc=fail`, or `spf=fail` together with
     * `dkim=fail` and no passing DKIM signature, gives [Auth.FAIL]; everything else [Auth.UNKNOWN].
     * Not for Riseup accounts, whose server does not remove forged copies of this header.
     */
    fun parse(firstAuthenticationResultsHeader: String?): Int {
        val value = firstAuthenticationResultsHeader?.let(::withoutCommentsAndQuotes) ?: return Auth.UNKNOWN
        val parts = value.split(';')
        val authservId = parts.first().trim().substringBefore(' ').substringBefore('\t').lowercase()
        if (authservId != GMAIL_AUTHSERV_ID) return Auth.UNKNOWN
        val results = parts.drop(1).mapNotNull { info ->
            result.find(info)?.destructured?.let { (method, value) -> method.lowercase() to value.lowercase() }
        }
        fun all(method: String) = results.filter { it.first == method }.map { it.second }
        val dmarc = all("dmarc").firstOrNull()
        val dkim = all("dkim")
        return when {
            dmarc == "pass" -> Auth.PASS
            dmarc == "fail" -> Auth.FAIL
            all("spf").firstOrNull() == "fail" && "fail" in dkim && "pass" !in dkim -> Auth.FAIL
            else -> Auth.UNKNOWN
        }
    }

    /**
     * Drops comments `( … )` (nested) and the content of quoted strings, so that text the sender controls (an
     * envelope address such as `"x;dmarc=pass"@evil.example`) cannot look like a result.
     */
    private fun withoutCommentsAndQuotes(value: String): String {
        val out = StringBuilder(value.length)
        var depth = 0
        var quoted = false
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && (quoted || depth > 0) -> i++
                quoted -> if (c == '"') quoted = false
                c == '(' -> depth++
                depth > 0 -> if (c == ')') depth--
                c == '"' -> {
                    quoted = true
                    out.append("\"\"")
                }
                else -> out.append(if (c.isWhitespace()) ' ' else c)
            }
            i++
        }
        return out.toString()
    }
}
