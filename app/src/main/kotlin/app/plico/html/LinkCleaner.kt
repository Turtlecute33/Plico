package app.plico.html

import androidx.annotation.StringRes
import app.plico.R
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/** A warning on the link sheet (DESIGN.md §11.3). [text] is the sheet text. */
enum class LinkWarning(@StringRes val text: Int) {
    /** The link text shows a domain, and the link goes to another registrable domain. */
    OTHER_DOMAIN(R.string.html_link_other_domain),

    /** The host is an IP address. */
    IP_HOST(R.string.html_link_ip_host),

    /** A label of the host mixes scripts (other than Latin with CJK). */
    MIXED_SCRIPT(R.string.html_link_mixed_script),

    /** A non-ASCII label of the host has only letters that look like Latin letters. */
    LOOKALIKE(R.string.html_link_lookalike),
}

/** What a tapped link leads to, after [LinkCleaner.clean]. */
sealed interface Link {
    /**
     * An http or https link. Show and open exactly [url] with `Intent(ACTION_VIEW, uri)` and
     * `CATEGORY_BROWSABLE` (no component, no selector).
     *
     * @property url the cleaned URL, rebuilt from its parsed parts; the host is in ASCII (punycode) form.
     * @property host ASCII host of [url].
     * @property domain registrable domain of [host] for the bold part of the sheet; null for IP hosts and public
     *   suffixes.
     * @property original the link as tapped, when it is a valid http(s) URL that differs from [url] ("Open original").
     */
    data class Web(
        val url: String,
        val host: String,
        val domain: String?,
        val warnings: Set<LinkWarning>,
        val original: String?,
    ) : Link

    /** A `mailto:` link: open Compose. */
    data class Mail(val mailto: Mailto) : Link

    /** Any other scheme, or a URL that is not safe to open: show [text] with Copy only. [original] as in [Web]. */
    data class Unsupported(val text: String, val original: String?) : Link
}

/**
 * Link cleaning for the link sheet (DESIGN.md §11.3, privacy PS5/PS6): unwraps redirectors, strips tracking
 * parameters, then parses the result again and allows only http, https and mailto. URLs with userinfo, `\`,
 * whitespace or control characters are not opened.
 */
class LinkCleaner(private val psl: Psl) {
    /**
     * @param url the tapped URL (`WebResourceRequest.url`, or an `href` of a plain-text body).
     * @param linkText the visible text of the link when known (the WebView hit-test anchor text).
     */
    fun clean(url: String, linkText: String? = null): Link {
        val input = url.trim()
        var target = input
        for (i in 0 until MAX_UNWRAP) target = unwrap(target)?.trim() ?: break
        val tapped = WebUrl.parse(input)?.toString()
        if (target.startsWith("mailto:", ignoreCase = true)) {
            return Mailto.parse(target)?.let(Link::Mail) ?: Link.Unsupported(visible(target), tapped)
        }
        val parsed = WebUrl.parse(target) ?: return Link.Unsupported(visible(target), tapped)
        val clean = parsed.withQuery(withoutTracking(parsed.query))
        val host = clean.host
        val ip = Idn.isIpAddress(host)
        val domain = if (ip) null else psl.registrableDomain(host)
        val warnings = buildSet {
            val shown = linkText?.let(psl::domainsIn).orEmpty()
            if (shown.isNotEmpty() && (domain ?: host) !in shown) add(LinkWarning.OTHER_DOMAIN)
            if (ip) add(LinkWarning.IP_HOST)
            if (Idn.isMixedScript(host)) add(LinkWarning.MIXED_SCRIPT)
            if (Idn.isLookalike(host)) add(LinkWarning.LOOKALIKE)
        }
        val cleanUrl = clean.toString()
        return Link.Web(cleanUrl, host, domain, warnings, tapped?.takeIf { it != cleanUrl })
    }

    /** The URL inside a known redirector, still raw, or null when [url] is not one. */
    private fun unwrap(url: String): String? {
        val u = WebUrl.parse(url) ?: return null
        val host = u.host
        return when {
            google.matches(host) && u.path == "/url" -> (u.param("q") ?: u.param("url"))?.let(Percent::decode)
            host.endsWith(".safelinks.protection.outlook.com") -> u.param("url")?.let(Percent::decode)
            host == "urldefense.com" && u.path.startsWith("/v3/__") -> urldefense(url)
            host.endsWith(".awstrack.me") -> u.path.split('/').takeIf { it.size > 2 && awsLink.matches(it[1]) }
                ?.let { Percent.decode(it[2]) }
            (host == "mandrillapp.com" || host.endsWith(".mandrillapp.com")) && u.path.startsWith("/track/click") ->
                u.param("p")?.let(::mandrill)
            else -> null
        }
    }

    /**
     * Proofpoint URL Defense v3: `/v3/__<url>__;<base64>!…`. Each `*` in the URL stands for the next character of
     * the base64 data, and `**X` for a run of characters whose length X encodes.
     */
    private fun urldefense(url: String): String? {
        val m = urldefenseV3.find(url) ?: return null
        val embedded = Percent.decode(m.groupValues[1])
        val replacements = try {
            String(Base64.getUrlDecoder().decode(m.groupValues[2]), Charsets.UTF_8).codePoints().toArray()
        } catch (_: IllegalArgumentException) {
            return null
        }
        var next = 0
        val out = StringBuilder(embedded.length)
        var i = 0
        while (i < embedded.length) {
            val c = embedded[i]
            if (c != '*') {
                out.append(c)
                i++
                continue
            }
            val run = if (embedded.startsWith("**", i)) RUN_LENGTHS.indexOf(embedded.getOrElse(i + 2) { ' ' }) else -1
            val count = if (run >= 0) run + 2 else 1
            if (next + count > replacements.size) return null
            for (k in 0 until count) out.appendCodePoint(replacements[next++])
            i += if (run >= 0) 3 else 1
        }
        return out.toString()
    }

    /** Mandrill `track/click?p=`: base64 JSON whose `p` member is JSON with the `url`. */
    private fun mandrill(p: String): String? = try {
        val base64 = Percent.decode(p).trimEnd('=').replace('+', '-').replace('/', '_')
        val outer = JSONObject(String(Base64.getUrlDecoder().decode(base64), Charsets.UTF_8))
        JSONObject(outer.getString("p")).getString("url")
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: JSONException) {
        null
    }

    private fun withoutTracking(query: String?): String? = query?.split('&')
        ?.filterNot { field ->
            val name = Percent.decode(field.substringBefore('=')).lowercase()
            name.startsWith("utm_") || name in trackingParams
        }
        ?.joinToString("&")
        ?.ifEmpty { null }

    /** Text for the sheet: control and format characters (bidi overrides included) become U+FFFD. */
    private fun visible(text: String): String = WebUrl.hidden.replace(text, "\uFFFD")

    private companion object {
        const val MAX_UNWRAP = 5
        const val RUN_LENGTHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val google = Regex("""(www\.)?google\.[a-z]{2,3}(\.[a-z]{2})?""")
        val awsLink = Regex("""L\d+""")
        val urldefenseV3 = Regex("""/v3/__(.+?)__;([A-Za-z0-9_-]*)!""")
        val trackingParams = setOf(
            "gclid", "gclsrc", "dclid", "_ga", "fbclid", "igshid", "msclkid", "mc_cid", "mc_eid", "yclid", "twclid",
            "gbraid", "wbraid", "_hsenc", "__hssc", "__hstc", "__hsfp", "hsctatracking", "ml_subscriber", "vero_id",
            "oly_anon_id", "oly_enc_id", "icid", "ef_id", "s_cid",
        )
    }
}

/**
 * An http or https URL rebuilt from its parsed parts (privacy PS5). Parsing rejects userinfo, `\`, whitespace,
 * control and format characters; the host becomes lower-case ASCII; path, query and fragment are
 * percent-encoded where RFC 3986 needs it.
 */
internal class WebUrl private constructor(
    val scheme: String,
    val host: String,
    private val port: Int,
    val path: String,
    val query: String?,
    private val fragment: String?,
) {
    fun withQuery(query: String?) = WebUrl(scheme, host, port, path, query, fragment)

    /** Raw (still percent-encoded) value of the first query parameter [name]. */
    fun param(name: String): String? =
        query?.split('&')?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")

    override fun toString(): String = buildString {
        append(scheme).append("://").append(host)
        if (port >= 0) append(':').append(port)
        append(path)
        if (query != null) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    }

    companion object {
        private const val SUB_DELIMS = "!$&'()*+,;=:@/%"
        private val lonePercent = Regex("%(?![0-9A-Fa-f]{2})")
        private val ipv6 = Regex("""\[[0-9a-f:.]+]""")

        /** Control and format characters (zero-width and bidi controls included). */
        val hidden = Regex("""[\p{Cc}\p{Cf}]""")

        fun parse(s: String): WebUrl? {
            if ('\\' in s || s.any(Char::isWhitespace) || hidden.containsMatchIn(s)) return null
            val colon = s.indexOf(':')
            if (colon < 0 || !s.startsWith("//", colon + 1)) return null
            val scheme = s.substring(0, colon).lowercase()
            if (scheme != "http" && scheme != "https") return null
            val start = colon + 3
            val end = s.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) s.length else it }
            val authority = s.substring(start, end)
            if ('@' in authority) return null
            val portStart = if (authority.startsWith('[')) {
                authority.indexOf("]:").let { if (it < 0) -1 else it + 1 }
            } else {
                authority.lastIndexOf(':')
            }
            val rawHost = if (portStart < 0) authority else authority.substring(0, portStart)
            val rawPort = if (portStart < 0) "" else authority.substring(portStart + 1)
            val port = when {
                rawPort.isEmpty() -> -1
                rawPort.length <= 5 && rawPort.all { it in '0'..'9' } && rawPort.toInt() in 1..65535 -> rawPort.toInt()
                else -> return null
            }
            val host = if (rawHost.startsWith('[')) {
                rawHost.lowercase().takeIf { ipv6.matches(it) } ?: return null
            } else {
                Idn.toAscii(Percent.decode(rawHost)) ?: return null
            }
            val defaultPort = if (scheme == "https") 443 else 80
            var rest = s.substring(end)
            val fragment = rest.substringAfter('#', "").takeIf { '#' in rest }
            rest = rest.substringBefore('#')
            val query = rest.substringAfter('?', "").takeIf { '?' in rest }
            val path = rest.substringBefore('?').ifEmpty { "/" }
            return WebUrl(
                scheme = scheme,
                host = host,
                port = port.takeIf { it != defaultPort } ?: -1,
                path = encode(path, ""),
                query = query?.let { encode(it, "?") }?.ifEmpty { null },
                fragment = fragment?.let { encode(it, "?") }?.ifEmpty { null },
            )
        }

        private fun encode(part: String, extra: String): String =
            Percent.encode(lonePercent.replace(part, "%25")) {
                Percent.isUnreserved(it) || it in SUB_DELIMS || it in extra
            }
    }
}
