package app.plico.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlSanitizerTest {
    private fun clean(html: String, allowRemote: Boolean = false) = HtmlSanitizer.sanitize(html, allowRemote)

    private fun doc(html: String, allowRemote: Boolean = false): Document =
        Jsoup.parse(clean(html, allowRemote).html).apply { outputSettings().prettyPrint(false) }

    /** Body HTML without the head; the sanitizer always adds the same head. */
    private fun body(html: String, allowRemote: Boolean = false): String = doc(html, allowRemote).body().html()

    private fun assertNoActiveContent(html: String) {
        val result = clean(html).html
        val out = Jsoup.parse(result)
        for (tag in listOf("script", "iframe", "object", "embed", "form", "input", "button", "textarea", "select", "link",
            "base", "svg", "math", "frame", "frameset", "applet", "video", "audio", "source", "noscript", "template")) {
            assertTrue(out.select(tag).isEmpty(), "$tag in $result for $html")
        }
        assertEquals(4, out.select("meta").size, result)
        assertTrue(out.getAllElements().all { el -> el.attributes().none { it.key.startsWith("on") } }, result)
        assertTrue(out.select("[href]").all { it.attr("href").matches(Regex("(?i)(https?|mailto):.*")) }, result)
        assertFalse(Regex("(?i)javascript:|vbscript:|<script").containsMatchIn(result), result)
    }

    @Test
    fun scriptsAndEventHandlers() {
        assertNoActiveContent("<script>alert(1)</script><p>Hi</p>")
        assertNoActiveContent("<SCRIPT SRC=https://evil.example/x.js></SCRIPT>")
        assertNoActiveContent("<img src=x onerror=alert(1)>")
        assertNoActiveContent("<body onload=alert(1)><p onclick='alert(1)' onmouseover=alert(1)>x</p></body>")
        assertNoActiveContent("<div style=\"x\" OnFocus=\"alert(1)\" tabindex=1 autofocus>x</div>")
        assertNoActiveContent("<xmp><script>alert(1)</script></xmp>")
        assertNoActiveContent("<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript>")
        assertNoActiveContent("<!-- <script>alert(1)</script> -->")
        assertNoActiveContent("<template><script>alert(1)</script></template>")
        assertNoActiveContent("<textarea><script>alert(1)</script></textarea>")
        assertEquals("<p>Hi</p>", body("<script>alert(1)</script><p>Hi</p>"))
    }

    @Test
    fun dangerousLinks() {
        for (href in listOf("javascript:alert(1)", "JaVaScRiPt:alert(1)", "jav&#x09;ascript:alert(1)", " javascript:alert(1)",
            "java\nscript:alert(1)", "vbscript:msgbox(1)", "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "ftp://example.com/x", "file:///etc/passwd", "intent://x#Intent;end", "relative/path", "#top")) {
            val html = "<a href=\"$href\">x</a>"
            assertNoActiveContent(html)
            assertEquals("<a>x</a>", body(html), href)
        }
        assertEquals("<a href=\"https://example.com/a?b=1&amp;c=2\">x</a>", body("<a href=\"https://example.com/a?b=1&c=2\">x</a>"))
        assertEquals("<a href=\"http://example.com/\">x</a>", body("<a href=\"http://example.com/\">x</a>"))
        assertEquals("<a href=\"mailto:a@example.com\">x</a>", body("<a href=\"mailto:a@example.com\">x</a>"))
        assertEquals(
            "<a href=\"https://example.com/\">x</a>",
            body("<a href=\"https://example.com/\" ping=\"https://t.example/p\" target=_blank>x</a>"),
        )
    }

    @Test
    fun svgAndMath() {
        assertNoActiveContent("<svg onload=alert(1)><circle r=1 /><image href=\"https://t.example/x.png\" /></svg>")
        assertNoActiveContent("<svg><style><img src=x onerror=alert(1)></style></svg>")
        assertNoActiveContent("<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>")
        assertNoActiveContent("<math href=\"javascript:alert(1)\">x</math>")
        assertNoActiveContent("<svg><a xlink:href=\"javascript:alert(1)\"><text>x</text></a></svg>")
    }

    @Test
    fun formsMetaBaseAndFrames() {
        assertNoActiveContent("<form action=\"https://evil.example/\" method=post><input name=p type=password><button>Go</button></form>")
        assertNoActiveContent("<button formaction=\"https://evil.example/\">Go</button>")
        assertNoActiveContent("<meta http-equiv=\"refresh\" content=\"0;url=https://evil.example/\"><p>x</p>")
        assertNoActiveContent(
            "<html><head><meta http-equiv=refresh content=\"0;url=https://e.example\"><base href=\"https://evil.example/\"></head>" +
                "<body>x</body></html>",
        )
        assertNoActiveContent("<iframe src=\"https://evil.example/\"></iframe><iframe srcdoc=\"<script>alert(1)</script>\"></iframe>")
        assertNoActiveContent("<object data=\"https://evil.example/x.swf\"></object><embed src=\"https://evil.example/x.swf\">")
        assertNoActiveContent("<link rel=stylesheet href=\"https://evil.example/a.css\"><link rel=prefetch href=\"https://t.example/\">")
        assertNoActiveContent("<video poster=\"https://t.example/p.png\"><source src=\"https://t.example/v.mp4\"></video>")
        assertFalse("evil.example" in clean("<link rel=stylesheet href=\"https://evil.example/a.css\"><p>x</p>").html)
        assertFalse("t.example" in clean("<video poster=\"https://t.example/p.png\"></video>").html)
    }

    @Test
    fun outputDocument() {
        val blocked = doc("<p>x</p>")
        assertEquals(
            "default-src 'none'; img-src data: https://cid.plico.invalid; style-src 'unsafe-inline'; form-action 'none'; " +
                "base-uri 'none'; upgrade-insecure-requests",
            blocked.select("meta[http-equiv=Content-Security-Policy]").attr("content"),
        )
        val allowed = doc("<p>x</p>", allowRemote = true)
        assertEquals(
            "default-src 'none'; img-src data: https://cid.plico.invalid https:; style-src 'unsafe-inline'; form-action 'none'; " +
                "base-uri 'none'; upgrade-insecure-requests",
            allowed.select("meta[http-equiv=Content-Security-Policy]").attr("content"),
        )
        assertEquals("off", blocked.select("meta[http-equiv=x-dns-prefetch-control]").attr("content"))
        assertEquals("width=device-width, initial-scale=1", blocked.select("meta[name=viewport]").attr("content"))
        assertEquals("utf-8", blocked.select("meta[charset]").attr("charset"))
        val html = clean("<p>x</p>").html
        assertTrue(html.startsWith("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\""))
        assertTrue("img{max-width:100%;height:auto}" in html)
        assertTrue("word-wrap" in html)
        assertFalse("color-scheme" in html)
        // The CSP comes before any content.
        assertEquals("meta", blocked.head().child(0).normalName())
    }

    @Test
    fun keepsLayoutMarkup() {
        val html = "<table width=600 cellpadding=0 cellspacing=0 border=0 bgcolor=\"#eeeeee\" align=center>" +
            "<tr><td valign=top style=\"padding:8px;color:#333\"><font face=Arial color=red size=2>Hi</font></td></tr></table>" +
            "<center><span dir=rtl>x</span></center><hr>"
        val out = doc(html)
        val table = out.selectFirst("table")!!
        for (key in listOf("width", "cellpadding", "cellspacing", "border", "bgcolor", "align")) assertTrue(table.hasAttr(key), key)
        assertEquals("padding:8px;color:#333", out.selectFirst("td")!!.attr("style"))
        assertEquals("top", out.selectFirst("td")!!.attr("valign"))
        val font = out.selectFirst("font")!!
        assertEquals("Arial", font.attr("face"))
        assertEquals("red", font.attr("color"))
        assertEquals(1, out.select("center").size)
        assertEquals("rtl", out.selectFirst("span")!!.attr("dir"))
        assertEquals(1, out.select("hr").size)
    }

    @Test
    fun bodyAttributesMoveToAWrapper() {
        val out = doc("<body bgcolor=\"#f0f0f0\" text=\"#111\" style=\"margin:0\" dir=\"rtl\"><p>x</p></body>")
        val wrapper = out.body().child(0)
        assertEquals("div", wrapper.normalName())
        assertEquals("background-color:#f0f0f0;color:#111;margin:0", wrapper.attr("style"))
        assertEquals("rtl", wrapper.attr("dir"))
        assertEquals("<p>x</p>", wrapper.html())
        assertEquals("<p>x</p>", body("<body><p>x</p></body>"))
    }

    @Test
    fun remoteImagesBlocked() {
        val result = clean("<p><img src=\"https://img.example/a.png\" width=600 height=200 alt=\"Logo\"></p>")
        assertEquals(1, result.blockedRemote)
        assertEquals(0, result.trackersRemoved)
        assertTrue(result.hasRemote)
        val img = Jsoup.parse(result.html).selectFirst("img")!!
        assertFalse(img.hasAttr("src"))
        assertEquals("Logo", img.attr("alt"))
        assertEquals("600", img.attr("width"))
        assertFalse("img.example" in result.html)
    }

    @Test
    fun remoteImagesAllowed() {
        val html = "<img src=\"http://img.example/a.png\" width=600 height=200><IMG SRC=\"HTTPS://IMG.EXAMPLE/B.PNG\">"
        val result = clean(html, allowRemote = true)
        assertEquals(0, result.blockedRemote)
        assertTrue(result.hasRemote)
        val sources = Jsoup.parse(result.html).select("img").map { it.attr("src") }
        assertEquals(listOf("https://img.example/a.png", "https://IMG.EXAMPLE/B.PNG"), sources)
    }

    @Test
    fun noRemoteContent() {
        val result = clean("<p>Hello</p><img src=\"data:image/png;base64,iVBORw0KGgo=\">")
        assertEquals(0, result.blockedRemote)
        assertEquals(0, result.trackersRemoved)
        assertFalse(result.hasRemote)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", Jsoup.parse(result.html).selectFirst("img")!!.attr("src"))
    }

    @Test
    fun dataAndOtherImageSchemes() {
        for (src in listOf("data:text/html,<script>alert(1)</script>", "javascript:alert(1)", "//img.example/a.png", "file:///sdcard/x.png",
            "content://app.plico.files/att/1/1", "a.png")) {
            val result = clean("<img src=\"$src\">")
            assertFalse(Jsoup.parse(result.html).selectFirst("img")!!.hasAttr("src"), src)
            assertEquals(0, result.blockedRemote, src)
        }
    }

    @Test
    fun srcsetIsBlockedAndNeverKept() {
        val blocked = clean("<img srcset=\"https://img.example/a.png 1x, https://img.example/b.png 2x\">")
        assertEquals(1, blocked.blockedRemote)
        assertFalse("img.example" in blocked.html)
        val allowed = doc("<img srcset=\"https://img.example/a.png 1x, https://img.example/b.png 2x\">", allowRemote = true)
        val img = allowed.selectFirst("img")!!
        assertEquals("https://img.example/a.png", img.attr("src"))
        assertFalse(img.hasAttr("srcset"))
        val both = doc("<img src=\"https://img.example/a.png\" srcset=\"https://other.example/b.png 2x\">", allowRemote = true)
        assertFalse("other.example" in both.html())
    }

    @Test
    fun backgroundAttribute() {
        val blocked = clean(
            "<table background=\"https://img.example/bg.png\"><tr><td background=\"http://img.example/td.png\">x</td></tr></table>",
        )
        assertEquals(2, blocked.blockedRemote)
        assertFalse("img.example" in blocked.html)
        assertFalse("background=" in blocked.html)
        val allowed = doc("<table background=\"http://img.example/bg.png\"><tr><td>x</td></tr></table>", allowRemote = true)
        assertEquals("background-image:url(\"https://img.example/bg.png\");", allowed.selectFirst("table")!!.attr("style"))
        val body = clean("<body background=\"https://img.example/bg.png\"><p>x</p></body>")
        assertEquals(1, body.blockedRemote)
        // A quote in the value cannot add CSS: the whole value is one (invalid) URL.
        val tricky = doc(
            "<table><tr><td background='x\");background:url(https://img.example/t.png'>y</td></tr></table>",
            allowRemote = true,
        )
        assertEquals("background-image:none;", tricky.selectFirst("td")!!.attr("style"))
    }

    @Test
    fun trackingPixels() {
        val pixels = listOf(
            "<img src=\"https://t.example/p.gif\" width=\"1\" height=\"1\">",
            "<img src=\"https://t.example/p.gif\" width=\"0\" height=\"0\">",
            "<img src=\"https://t.example/p.gif\" width=\"5\" height=\"5\">",
            "<img src=\"https://t.example/p.gif\" height=\"2px\">",
            "<img src=\"https://t.example/p.gif\" style=\"width:1px;height:1px\">",
            "<img src=\"https://t.example/p.gif\" style=\"display:none\">",
            "<img src=\"https://t.example/p.gif\" style=\"DISPLAY: none !important\">",
            "<img src=\"https://t.example/p.gif\" style=\"visibility:hidden\">",
            "<img src=\"https://t.example/p.gif\" style=\"opacity:0\">",
            "<img src=\"https://t.example/p.gif\" style=\"opacity:0.0\">",
            "<img src=\"https://t.example/p.gif\" style=\"max-width:1px\">",
            "<img src=\"https://t.example/p.gif\" style=\"max-height:0px\">",
            "<img src=\"https://t.example/p.gif\" style=\"dis\\70lay:none\">",
            "<img srcset=\"https://t.example/p.gif 1x\" width=1 height=1>",
        )
        for (html in pixels) {
            for (allow in listOf(false, true)) {
                val result = clean("<p>a$html</p>", allow)
                assertEquals(1, result.trackersRemoved, html)
                assertEquals(0, result.blockedRemote, html)
                assertFalse(result.hasRemote, html)
                assertTrue(Jsoup.parse(result.html).select("img").isEmpty(), html)
            }
        }
    }

    @Test
    fun normalImagesAreNotPixels() {
        val images = listOf(
            "<img src=\"https://img.example/a.png\" width=\"600\">",
            "<img src=\"https://img.example/a.png\" width=\"100%\" height=\"1%\">",
            "<img src=\"https://img.example/a.png\" style=\"width:auto;opacity:1\">",
            "<img src=\"https://img.example/a.png\" width=6 height=5>",
        )
        for (html in images) {
            val result = clean(html, allowRemote = true)
            assertEquals(0, result.trackersRemoved, html)
            assertEquals(1, Jsoup.parse(result.html).select("img[src]").size, html)
        }
        // A small embedded spacer is not a tracker.
        assertEquals(0, clean("<img src=\"data:image/gif;base64,R0lGODlhAQABAAAAACw=\" width=1 height=1>").trackersRemoved)
        assertEquals(0, clean("<img src=\"cid:spacer\" width=1 height=1>").trackersRemoved)
    }

    @Test
    fun trackerHosts() {
        for (url in listOf("https://www.google-analytics.com/collect?v=1&tid=UA-1", "http://us1.list-manage.com/track/open.php?u=1",
            "https://u123.ct.sendgrid.net/wf/open?upn=x", "https://pixel.mailtrack.io/x.png")) {
            for (allow in listOf(false, true)) {
                val result = clean("<img src=\"$url\" width=600 height=100>", allow)
                assertEquals(1, result.trackersRemoved, url)
                assertEquals(0, result.blockedRemote, url)
                assertTrue(Jsoup.parse(result.html).select("img").isEmpty(), url)
            }
        }
        val css = clean("<div style=\"background:url(https://www.google-analytics.com/x.gif)\">x</div>", allowRemote = true)
        assertEquals(1, css.trackersRemoved)
        assertFalse("google-analytics" in css.html)
        // A browser reads "\\" as "/" (host google-analytics.com); the source is dropped, nothing is left to load.
        val backslash = clean("<img src=\"https://www.google-analytics.com\\@evil.example/x\" width=600 height=100>", allowRemote = true)
        assertFalse(Jsoup.parse(backslash.html).selectFirst("img")!!.hasAttr("src"))
        assertFalse("google-analytics" in backslash.html)
        // In CSS the host is read as a browser does.
        val cssBackslash =
            clean("<div style=\"background:url('https://www.google-analytics.com\\\\@evil.example/x')\">x</div>", allowRemote = true)
        assertEquals(1, cssBackslash.trackersRemoved)
        assertFalse("google-analytics" in cssBackslash.html)
        // Kept URLs get the same reading: the path starts at the backslash.
        val kept = doc("<div style=\"background:url('https://img.example\\\\@www.google-analytics.com/x')\">x</div>", allowRemote = true)
        assertEquals("background:url(\"https://img.example/@www.google-analytics.com/x\")", kept.selectFirst("div")!!.attr("style"))
        // Full-width letters and escapes in the host map to ASCII, as in a browser.
        val hosts = listOf(
            "www.\uFF47oogle-analytics.com", "www.google%2Danalytics.com", "WWW.GOOGLE-ANALYTICS.COM.", "x@www.google-analytics.com",
        )
        for (host in hosts) {
            val result = clean("<div style=\"background:url('https://$host/x')\">x</div>", allowRemote = true)
            assertEquals(1, result.trackersRemoved, host)
        }
    }

    @Test
    fun cidImages() {
        val out = doc("<img src=\"cid:image001.png@01D2A3B4.C5D6E7F0\"><img src=\"CID:part1%40example.com\"><img src=\"cid:a b/c\">")
        val sources = out.select("img").map { it.attr("src") }
        assertEquals(
            listOf(
                "https://cid.plico.invalid/image001.png%4001D2A3B4.C5D6E7F0",
                "https://cid.plico.invalid/part1%40example.com",
                "https://cid.plico.invalid/a%20b%2Fc",
            ),
            sources,
        )
        assertEquals(listOf("image001.png@01D2A3B4.C5D6E7F0", "part1@example.com", "a b/c"), sources.map { HtmlSanitizer.cidOf(it) })
        assertNull(HtmlSanitizer.cidOf("https://img.example/a.png"))
        assertNull(HtmlSanitizer.cidOf("https://cid.plico.invalid/"))
        val result = clean("<img src=\"cid:x\"><div style=\"background:url(cid:%3Cbg%40y%3E)\">z</div>")
        assertEquals(0, result.blockedRemote)
        assertFalse(result.hasRemote)
        assertEquals(setOf("x", "bg@y"), result.cids)
        assertEquals(emptySet(), clean("<a href=\"cid:x\">x</a><img src=\"https://img.example/a.png\">", allowRemote = true).cids)
        assertEquals(
            "background-image:url(\"https://cid.plico.invalid/bg%40x\")",
            doc("<div style=\"background-image:url(cid:bg@x)\">x</div>").selectFirst("div")!!.attr("style"),
        )
    }

    @Test
    fun styleBlocks() {
        val html = "<html><head><style>@import url(https://evil.example/a.css); body{color:red} " +
            ".x{background:url('https://img.example/a.png')}</style>" +
            "</head><body><style>p{margin:0}</style><p class=x>x</p></body></html>"
        val result = clean(html)
        val out = Jsoup.parse(result.html)
        val styles = out.head().select("style").map { it.data() }
        assertEquals(3, styles.size, styles.toString())
        assertTrue("body{color:red}" in styles[1])
        assertTrue(".x{background:none}" in styles[1], styles[1])
        assertFalse("@import" in styles[1] || "evil.example" in styles[1])
        assertEquals("p{margin:0}", styles[2])
        assertTrue(out.body().select("style").isEmpty())
        assertEquals(1, result.blockedRemote)
        val allowed = Jsoup.parse(clean(html, allowRemote = true).html).head().select("style")[1].data()
        assertTrue(".x{background:url(\"https://img.example/a.png\")}" in allowed, allowed)
    }

    @Test
    fun styleBlockCannotEndEarly() {
        assertNoActiveContent("<style>a{content:\"</style><script>alert(1)</script>\"}</style>")
        val out = clean("<style>a::after{content:\"<b>\"} b{}</style>").html
        val style = Jsoup.parse(out).head().select("style")[1].data()
        assertFalse('<' in style, style)
        assertTrue("\\3c b>" in style, style)
    }

    @Test
    fun cssUrlEvasions() {
        val vectors = listOf(
            "background:u\\72l(https://t.example/1)",
            "background:u\\000072l(https://t.example/1)",
            "background:\\75 rl(https://t.example/1)",
            "background:\\55\\52\\4c(https://t.example/1)",
            "background:u\\rl(https://t.example/1)",
            "background:URL( \"https://t.example/1\" )",
            "background:/**/url(https://t.example/1)",
            "background-image:url(\"https://t.example/1\\\")\")",
            "background-image:url('https://t.example/1')",
            "background:url(https:\\2f\\2ft.example/1)",
            "background-image:image-set(\"https://t.example/1\" 1x)",
            "background-image:-webkit-image-set(url(https://t.example/1) 1x)",
            "background-image:cross-fade(url(https://t.example/1), url(https://t.example/2), 50%)",
            "list-style-image:url(https://t.example/1)",
            "border-image:url(https://t.example/1) 30 round",
            "cursor:url(https://t.example/1),auto",
            "content:url(https://t.example/1)",
            "mask-image:url(https://t.example/1)",
            "background:url(https://t.example/1",
        )
        for (css in vectors) {
            val result = clean("<div style='$css'>x</div>")
            val style = Jsoup.parse(result.html).selectFirst("div")!!.attr("style")
            assertFalse("t.example" in style, "$css -> $style")
            assertFalse(Regex("(?i)url\\(|image-set|cross-fade").containsMatchIn(style), "$css -> $style")
        }
        // In a style block too, and comments cannot join a name the browser would not see.
        val block = clean("<style>div{background:u\\72l(https://t.example/1)} p{background:u/**/rl(https://t.example/2)}</style>")
        assertFalse("t.example" in block.html)
    }

    @Test
    fun cssActiveFeatures() {
        val vectors = mapOf(
            "width:expression(alert(1))" to "expression",
            "width:EXPR\\45SSION(alert(1))" to "expression",
            "behavior:url(x.htc)" to "behavior",
            "-moz-binding:url(https://t.example/x.xml#x)" to "binding",
            "background:url(javascript:alert(1))" to "javascript",
            "background:url(\"data:text/html,<script>alert(1)</script>\")" to "script",
            "background:url(vbscript:x)" to "vbscript",
            "x:expexpressionression(1)" to "expression",
        )
        for ((css, word) in vectors) {
            val style = doc("<div style='$css'>x</div>").selectFirst("div")?.attr("style").orEmpty()
            assertFalse(word in style.lowercase(), "$css -> $style")
        }
        val block = clean(
            "<style>@IMPORT 'https://t.example/a.css'; @import url(x.css) screen; " +
                "@font-face{font-family:x;src:url(https://t.example/f.woff)} p{color:red}</style>",
        )
        val style = Jsoup.parse(block.html).head().select("style")[1].data()
        assertFalse("import" in style.lowercase() || "t.example" in style || "font-face" in style, style)
        assertTrue("p{color:red}" in style, style)
        assertEquals(0, block.blockedRemote)
    }

    @Test
    fun cssKeepsHarmlessStyles() {
        val style = doc(
            "<p style=\"color:#123456;font-family:'Segoe UI',Arial;content:'\\201C';background:url(data:image/png;base64,iVBOR)\">x</p>",
        )
            .selectFirst("p")!!.attr("style")
        assertEquals("color:#123456;font-family:'Segoe UI',Arial;content:'\\201C';background:url(\"data:image/png;base64,iVBOR\")", style)
        // A style attribute that becomes empty is removed.
        assertFalse(doc("<p style=\"behavior:\">x</p>").selectFirst("p")!!.attr("style").contains("behavior"))
    }

    @Test
    fun remoteCssUrlsInStyleAttributes() {
        val blocked = clean("<div style=\"background:#fff url(http://img.example/a.png) no-repeat\">x</div>")
        assertEquals(1, blocked.blockedRemote)
        assertEquals("background:#fff none no-repeat", Jsoup.parse(blocked.html).selectFirst("div")!!.attr("style"))
        val allowed = clean("<div style=\"background:#fff url(http://img.example/a.png) no-repeat\">x</div>", allowRemote = true)
        assertEquals(
            "background:#fff url(\"https://img.example/a.png\") no-repeat",
            Jsoup.parse(allowed.html).selectFirst("div")!!.attr("style"),
        )
        assertTrue(allowed.hasRemote)
    }

    @Test
    fun countsEveryBlockedResource() {
        val html = "<img src=\"https://a.example/1.png\" width=100><img src=\"https://a.example/2.png\" width=100>" +
            "<div style=\"background:url(https://a.example/3.png)\">x</div><img src=\"https://a.example/p.gif\" width=1 height=1>"
        val result = clean(html)
        assertEquals(3, result.blockedRemote)
        assertEquals(1, result.trackersRemoved)
    }

    @Test(timeout = 10_000)
    fun craftedInputStaysLinear() {
        // Each pass of a naive "remove until none is left" loop would remove only one nested name.
        val nested = clean("<div style=\"x:" + "exp".repeat(20_000) + "ression".repeat(20_000) + "\">x</div>")
        assertFalse("expression" in nested.html)
        val parens = HtmlSanitizer.plainToHtml("https://example.com/" + ")".repeat(200_000))
        assertTrue("<a href=\"https://example.com/\">" in parens)
        clean("<div style=\"" + "image-set(".repeat(50_000) + "\">x</div>")
        clean("<style>" + "/*".repeat(50_000) + "</style>")
    }

    @Test
    fun hugeOrBrokenInputDoesNotThrow() {
        clean("")
        clean("<<<>>><p<><img src=\"<div style='</")
        clean("<div style=\"" + "url(".repeat(5000) + "\">x</div>")
        clean("<style>" + "/*".repeat(5000) + "</style>")
        clean("<table>".repeat(2000) + "x")
        clean("<div style=\"background:u\\" + "\">x</div>")
    }
}
