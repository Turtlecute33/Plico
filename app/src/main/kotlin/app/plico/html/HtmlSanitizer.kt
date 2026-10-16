package app.plico.html

import app.plico.mail.mime.MimePart
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Output of [HtmlSanitizer.sanitize].
 *
 * @property html a complete document for `loadDataWithBaseURL(null, html, "text/html", "utf-8", null)`.
 * @property blockedRemote remote resources removed because remote content is not allowed.
 * @property trackersRemoved tracking pixels and resources on tracker hosts, removed in every case.
 * @property hasRemote true when the message has remote resources other than trackers (blocked now, or allowed).
 * @property cids Content-IDs that the document shows through [HtmlSanitizer.CID_HOST] URLs, as written in the message.
 */
data class SanitizedHtml(
    val html: String,
    val blockedRemote: Int,
    val trackersRemoved: Int,
    val hasRemote: Boolean,
    val cids: Set<String>,
)

/**
 * A message body joined from its display text parts.
 *
 * @property html HTML for [HtmlSanitizer.sanitize], or null when every part is plain text.
 * @property text plain text for snippets, search and the text fallback.
 * @property unreferencedImages inline (`cid:`) images that [html] does not show; list them as attachments.
 */
data class JoinedBody(val html: String?, val text: String, val unreferencedImages: List<MimePart>)

/** HTML for the message view, and text from HTML (DESIGN.md §11.1, privacy PS12/PS13, apis AF3). */
object HtmlSanitizer {
    /** Host of the URLs that stand for `cid:` parts; the WebView serves them from the message on screen. */
    const val CID_HOST = "cid.plico.invalid"
    private const val CID_PREFIX = "https://$CID_HOST/"
    private const val SNIPPET_LENGTH = 200

    private val safelist: Safelist = Safelist.relaxed()
        .addTags(
            "font", "center", "span", "div", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "col",
            "colgroup", "hr",
        )
        .addAttributes(
            ":all", "style", "align", "valign", "width", "height", "bgcolor", "border", "cellpadding", "cellspacing",
            "color", "face", "size", "dir",
        )
        .addAttributes("img", "srcset")
        .removeProtocols("a", "href", "ftp")
        .addProtocols("img", "src", "cid", "data")

    /** No `color-scheme`: it would turn off algorithmic darkening (apis AF3). */
    private const val BASE_CSS = "body{overflow-wrap:break-word;word-wrap:break-word}img{max-width:100%;height:auto}" +
        "pre{white-space:pre-wrap}"

    /** Elements that start and end a paragraph in [text]. */
    private val paragraphs = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "dl", "table", "pre", "hr")

    /** Elements that start and end a line in [text]. */
    private val lineBlocks = setOf(
        "div", "li", "tr", "dt", "dd", "center", "section", "article", "header", "footer", "address", "figure",
        "figcaption", "caption",
    )
    private val spaces = Regex("""[\s\u00A0]+""")
    private val pixelSize = Regex("""(\d*\.?\d+)\s*(?:px)?""")
    private val linkLike = Regex(
        """\b(?:https?://|mailto:)[^\s<>"]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""",
        RegexOption.IGNORE_CASE,
    )
    private val separator = Regex("""-{2,}\s*original message\s*-{2,}|_{10,}""", RegexOption.IGNORE_CASE)

    /**
     * Cleans [html] with the safelist, sanitizes CSS, removes remote resources (unless [allowRemote]) and
     * tracking pixels (always), and returns a document with a strict CSP. `cid:` URLs become
     * `https://cid.plico.invalid/<url-encoded Content-ID>` and `http://` image URLs become `https://`.
     */
    fun sanitize(html: String, allowRemote: Boolean): SanitizedHtml {
        val dirty = Jsoup.parse(html)
        val styles = dirty.select("style").map { it.data() }
        dirty.select("style").remove()
        wrapBody(dirty.body())
        for (el in dirty.select("[background]")) {
            el.attr("style", "background-image:url(${Css.quote(el.attr("background"))});" + el.attr("style"))
            el.removeAttr("background")
        }
        val doc = Cleaner(safelist).clean(dirty)
        val pass = Pass(allowRemote)
        doc.body().select("img").forEach(pass::image)
        for (el in doc.body().select("[style]")) {
            val css = pass.css(el.attr("style")).trim()
            if (css.isEmpty()) el.removeAttr("style") else el.attr("style", css)
        }
        val head = doc.head()
        head.appendElement("meta").attr("charset", "utf-8")
        head.appendElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", csp(allowRemote))
        head.appendElement("meta").attr("http-equiv", "x-dns-prefetch-control").attr("content", "off")
        head.appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
        head.appendElement("style").appendChild(DataNode(BASE_CSS))
        for (css in styles) head.appendElement("style").appendChild(DataNode(pass.css(css)))
        doc.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
        return SanitizedHtml(
            "<!DOCTYPE html>" + doc.outerHtml(), pass.blocked, pass.trackers, pass.blocked + pass.remote > 0, pass.cids,
        )
    }

    /** The Content-ID that a [CID_HOST] URL stands for, or null for other URLs. */
    fun cidOf(url: String): String? =
        if (url.startsWith(CID_PREFIX)) Percent.decode(url.substring(CID_PREFIX.length)).ifEmpty { null } else null

    /**
     * Text of an HTML body for snippets, search and the text fallback: block elements end lines, paragraphs are
     * separated by a blank line and quoted (`<blockquote>`) lines start with `> `.
     */
    fun text(html: String): String {
        val writer = TextWriter()
        NodeTraversor.traverse(writer, Jsoup.parse(html).body())
        return tidyLines(writer.toString())
    }

    /**
     * About the first 200 characters of a plain-text body for the message list: quoted lines (`>`), the
     * attribution line before them and everything from the signature (`-- `) or an "Original Message" line on are
     * left out.
     */
    fun snippet(text: String): String {
        val lines = text.lines()
        val kept = ArrayList<String>()
        for ((i, line) in lines.withIndex()) {
            val t = line.trim()
            if (t == "--" || separator.matches(t)) break
            if (t.startsWith(">")) continue
            val next = lines.subList(i + 1, lines.size).firstOrNull { it.isNotBlank() }?.trimStart()
            if (t.endsWith(":") && next?.startsWith(">") == true) continue
            kept += t
        }
        val s = kept.joinToString(" ").replace(spaces, " ").trim()
        if (s.length <= SNIPPET_LENGTH) return s
        val end = if (s[SNIPPET_LENGTH - 1].isHighSurrogate()) SNIPPET_LENGTH - 1 else SNIPPET_LENGTH
        return s.substring(0, end).trimEnd()
    }

    /**
     * Plain text as HTML for the same WebView and link flow: escaped, line breaks kept, and http(s) URLs, `mailto:`
     * URIs and addresses as links. The result goes through [sanitize] like any HTML body.
     */
    fun plainToHtml(text: String): String {
        val s = text.replace("\r\n", "\n")
        val out = StringBuilder(s.length + 64).append("<div style=\"white-space:pre-wrap\">")
        var last = 0
        for (m in linkLike.findAll(s)) {
            val link = trimLinkEnd(m.value)
            val href = if ("://" in link || link.startsWith("mailto:", ignoreCase = true)) link else "mailto:$link"
            out.append(escape(s.substring(last, m.range.first)))
                .append("<a href=\"").append(escape(href)).append("\">").append(escape(link)).append("</a>")
            last = m.range.first + link.length
        }
        return out.append(escape(s.substring(last))).append("</div>").toString()
    }

    /**
     * Joins the decoded display text parts (`DisplayParts.textParts`, in order). With any HTML part, the result
     * has [JoinedBody.html]: a single HTML part is kept as it is; several parts are joined, with plain parts
     * through [plainToHtml]. [JoinedBody.text] joins the plain text of every part.
     *
     * @param inline `DisplayParts.inline`; the images among them whose Content-ID the HTML does not show (compared
     *   without case) come back in [JoinedBody.unreferencedImages].
     */
    fun join(parts: List<Pair<MimePart, String>>, inline: List<MimePart> = emptyList()): JoinedBody {
        fun isHtml(part: MimePart) = part.subtype == "html"
        val text = parts.joinToString("\n\n") { (part, body) ->
            if (isHtml(part)) text(body) else body.replace("\r\n", "\n")
        }.trim()
        val html = when {
            parts.none { isHtml(it.first) } -> null
            parts.size == 1 -> parts[0].second
            else -> parts.joinToString("") { (part, body) ->
                if (isHtml(part)) {
                    val doc = Jsoup.parse(body)
                    doc.head().select("style").outerHtml() + "<div>" + doc.body().html() + "</div>"
                } else {
                    plainToHtml(body)
                }
            }
        }
        // The rules of the message view decide which cid URLs the document shows.
        val shown = if (html == null || inline.isEmpty()) emptySet() else sanitize(html, false).cids
        val unreferenced = inline.filter { part -> shown.none { it.equals(part.contentId, ignoreCase = true) } }
        return JoinedBody(html, text, unreferenced)
    }

    private fun csp(allowRemote: Boolean): String =
        "default-src 'none'; img-src data: https://$CID_HOST" + (if (allowRemote) " https:" else "") +
            "; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'; upgrade-insecure-requests"

    /** Moves the body's presentation attributes to a wrapper `div`, because the cleaner keeps only body content. */
    private fun wrapBody(body: Element) {
        val style = listOfNotNull(
            body.attr("bgcolor").takeIf(String::isNotBlank)?.let { "background-color:$it" },
            body.attr("text").takeIf(String::isNotBlank)?.let { "color:$it" },
            body.attr("style").takeIf(String::isNotBlank),
        ).joinToString(";")
        if (style.isEmpty() && !body.hasAttr("background") && !body.hasAttr("dir")) return
        val wrapper = Element("div")
        if (style.isNotEmpty()) wrapper.attr("style", style)
        for (key in listOf("background", "dir")) if (body.hasAttr(key)) wrapper.attr(key, body.attr(key))
        wrapper.appendChildren(body.childNodes().toList())
        body.appendChild(wrapper)
    }

    /** Collapses spaces; runs of empty or `>`-only lines become one blank line; no blank line at the ends. */
    private fun tidyLines(raw: String): String {
        val out = StringBuilder()
        var blank = false
        for (line in raw.lines()) {
            val t = line.replace(spaces, " ").trim()
            if (t.isEmpty() || t.all { it == '>' || it == ' ' }) {
                blank = out.isNotEmpty()
                continue
            }
            if (out.isNotEmpty()) out.append(if (blank) "\n\n" else "\n")
            out.append(t)
            blank = false
        }
        return out.toString()
    }

    /** Leaves trailing punctuation out of a link found in text; a `)` stays when the link has its `(`. */
    private fun trimLinkEnd(link: String): String {
        val open = link.count { it == '(' }
        var close = link.count { it == ')' }
        var end = link.length
        while (end > 0) {
            val c = link[end - 1]
            when {
                c == ')' && open < close -> close--
                c !in ".,;:!?'\"]}>*" -> break
            }
            end--
        }
        return link.substring(0, end)
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** Writes the text of a DOM: line breaks for blocks, `> ` before quoted lines. */
    private class TextWriter : NodeVisitor {
        private val out = StringBuilder()
        private var quoteDepth = 0
        private var lineStart = true

        override fun head(node: Node, depth: Int) {
            if (node is TextNode) return append(node.text())
            if (node !is Element) return
            when (node.normalName()) {
                "br" -> newLine()
                "td", "th" -> append(" ")
                "blockquote" -> {
                    paragraph()
                    quoteDepth++
                    newLine()
                }
                in paragraphs -> paragraph()
                in lineBlocks -> endLine()
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node !is Element) return
            when (node.normalName()) {
                "blockquote" -> {
                    quoteDepth--
                    paragraph()
                }
                in paragraphs -> paragraph()
                in lineBlocks -> endLine()
            }
        }

        override fun toString(): String = out.toString()

        private fun append(text: String) {
            if (lineStart && text.isBlank()) return
            out.append(text)
            lineStart = false
        }

        private fun newLine() {
            out.append('\n')
            repeat(quoteDepth) { out.append("> ") }
            lineStart = true
        }

        private fun endLine() {
            if (!lineStart) newLine()
        }

        private fun paragraph() {
            endLine()
            newLine()
        }
    }

    /** One sanitizing run: URL rules for images and CSS, with the counters. */
    private class Pass(val allowRemote: Boolean) {
        var blocked = 0
        var trackers = 0
        var remote = 0
        val cids = HashSet<String>()

        /** Removes tracking pixels and images on tracker hosts; rewrites or removes `src`. */
        fun image(img: Element) {
            val firstCandidate = img.attr("srcset").trim().substringBefore(',').trim().substringBefore(' ')
            val src = img.attr("src").ifBlank { firstCandidate }.trim()
            img.removeAttr("srcset")
            if (isRemote(src) && (isPixel(img) || HostRules.isTracker(hostOf(src)))) {
                trackers++
                img.remove()
                return
            }
            val safe = url(src)
            if (safe == null) img.removeAttr("src") else img.attr("src", safe)
        }

        fun css(css: String): String = Css.sanitize(css, ::url)

        /** The URL to keep for an image source, or null to remove it (counted when it is remote). */
        fun url(raw: String): String? {
            val url = raw.trim()
            val lower = url.lowercase()
            return when {
                lower.startsWith("cid:") -> cidUrl(url.substring(4))
                lower.startsWith("data:image/") -> url
                !isRemote(url) -> null
                HostRules.isTracker(hostOf(url)) -> null.also { trackers++ }
                !allowRemote -> null.also { blocked++ }
                // Browsers read a backslash as "/" in http(s) URLs; written out so, the host stays the checked one.
                else -> ("https://" + url.substringAfter("://").replace('\\', '/')).also { remote++ }
            }
        }

        private fun isRemote(url: String) =
            url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

        /**
         * Host of an http(s) URL as a browser reads it: `\` ends the authority, userinfo and port are dropped,
         * escapes are decoded and Unicode is mapped to ASCII (full-width letters too).
         */
        private fun hostOf(url: String): String {
            val authority = url.substringAfter("://").takeWhile { it !in "/?#\\" }.substringAfterLast('@')
            // An IPv6 literal is cut at its first ':', which is fine: no tracker host is one.
            val host = Percent.decode(authority.substringBefore(':'))
            return Idn.toAscii(host) ?: host.lowercase()
        }

        private fun cidUrl(cid: String): String? =
            Percent.decode(cid).trim().removePrefix("<").removeSuffix(">").ifEmpty { null }
                ?.also(cids::add)
                ?.let { CID_PREFIX + Percent.encode(it, Percent::isUnreserved) }

        /**
         * Tracking pixel: area ≤ 25 px, width or height ≤ 2 px, `display:none`, `visibility:hidden`, `opacity:0`,
         * or `max-width`/`max-height` ≤ 1 px (research G1).
         */
        private fun isPixel(img: Element): Boolean {
            val style = Css.decodeLetterEscapes(img.attr("style")).split(';').associate { declaration ->
                declaration.substringBefore(':').trim().lowercase() to
                    declaration.substringAfter(':', "").lowercase().replace("!important", "").trim()
            }
            val width = pixels(style["width"] ?: img.attr("width"))
            val height = pixels(style["height"] ?: img.attr("height"))
            return width != null && width <= 2 || height != null && height <= 2 ||
                width != null && height != null && width * height <= 25 ||
                style["display"] == "none" || style["visibility"] == "hidden" ||
                style["opacity"]?.removeSuffix("%")?.toDoubleOrNull() == 0.0 ||
                (pixels(style["max-width"]) ?: 2.0) <= 1 || (pixels(style["max-height"]) ?: 2.0) <= 1
        }

        private fun pixels(value: String?): Double? =
            value?.trim()?.let { pixelSize.matchEntire(it) }?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
