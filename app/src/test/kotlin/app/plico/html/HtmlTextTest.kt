package app.plico.html

import app.plico.mail.mime.MimePart
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlTextTest {
    private fun part(subtype: String, section: String) =
        MimePart(section, "text", subtype, emptyMap(), null, "7bit", 0, null, emptyMap(), emptyList())

    private fun image(cid: String, section: String) =
        MimePart(section, "image", "png", emptyMap(), cid, "base64", 100, "inline", emptyMap(), emptyList())

    @Test
    fun textKeepsLinesAndMarksQuotes() {
        val html = "<html><head><title>T</title><style>p{color:red}</style></head><body>" +
            "<p>Hello<br>World</p><div>Line   with\n spaces&nbsp;and&nbsp;nbsp</div>" +
            "<blockquote>quoted<blockquote>deeper</blockquote></blockquote><p>After</p>" +
            "<table><tr><td>a</td><td>b</td></tr></table><script>var x</script></body></html>"
        assertEquals("Hello\nWorld\n\nLine with spaces and nbsp\n\n> quoted\n\n> > deeper\n\nAfter\n\na b", HtmlSanitizer.text(html))
        // Gmail writes one div per line and <div><br></div> for a blank line.
        assertEquals("Hi Bob,\n\nSee below\nThanks", HtmlSanitizer.text("Hi Bob,<div><br></div><div>See below</div><div>Thanks</div>"))
    }

    @Test
    fun textOfEmptyOrPlainInput() {
        assertEquals("", HtmlSanitizer.text(""))
        assertEquals("just text", HtmlSanitizer.text("just text"))
        assertEquals("a < b & c", HtmlSanitizer.text("a &lt; b &amp; c"))
    }

    @Test
    fun snippetSkipsQuotesAttributionAndSignature() {
        val text = "Hi Bob,\n\nSee below.\n\nOn Mon, 1 Jan 2026 at 10:00, Alice <a@example.com> wrote:\n" +
            "> old text\n> > older\n\n-- \nAlice\nPhone 123"
        assertEquals("Hi Bob, See below.", HtmlSanitizer.snippet(text))
        assertEquals("Reply text", HtmlSanitizer.snippet("Reply text\n\n-----Original Message-----\nFrom: x\nOld"))
        assertEquals("Reply", HtmlSanitizer.snippet("Reply\n________________________________\nFrom: x"))
        assertEquals("Agenda: one two", HtmlSanitizer.snippet("Agenda:\none\ntwo"))
        assertEquals("", HtmlSanitizer.snippet("> only a quote"))
        assertEquals("", HtmlSanitizer.snippet(""))
    }

    @Test
    fun snippetLength() {
        val long = "word ".repeat(100)
        val snippet = HtmlSanitizer.snippet(long)
        assertTrue(snippet.length <= 200)
        assertTrue(snippet.length >= 195)
        // A surrogate pair is never cut in half.
        val emoji = "a".repeat(199) + "😀😀"
        assertEquals("a".repeat(199), HtmlSanitizer.snippet(emoji))
        assertEquals("Short", HtmlSanitizer.snippet("  Short  "))
    }

    @Test
    fun snippetOfHtmlText() {
        val html = "<div>New reply</div><div class=gmail_quote>On Mon, X wrote:<blockquote>old</blockquote></div>"
        assertEquals("New reply", HtmlSanitizer.snippet(HtmlSanitizer.text(html)))
    }

    @Test
    fun plainToHtmlEscapesAndLinks() {
        val html = HtmlSanitizer.plainToHtml(
            "<b>bold</b> & \"q\" 'x'\r\nSee https://example.com/a?b=1&c=2. Mail mailto:bob@example.com or alice@example.org!",
        )
        assertEquals(
            "<div style=\"white-space:pre-wrap\">&lt;b&gt;bold&lt;/b&gt; &amp; &quot;q&quot; &#39;x&#39;\nSee " +
                "<a href=\"https://example.com/a?b=1&amp;c=2\">https://example.com/a?b=1&amp;c=2</a>. Mail " +
                "<a href=\"mailto:bob@example.com\">mailto:bob@example.com</a> or " +
                "<a href=\"mailto:alice@example.org\">alice@example.org</a>!</div>",
            html,
        )
    }

    @Test
    fun plainToHtmlLinkEnds() {
        fun hrefs(text: String) = Jsoup.parse(HtmlSanitizer.plainToHtml(text)).select("a").map { it.attr("href") }
        assertEquals(listOf("https://example.com/x"), hrefs("(see https://example.com/x)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), hrefs("https://en.wikipedia.org/wiki/Foo_(bar)."))
        assertEquals(listOf("http://example.com/"), hrefs("<http://example.com/>"))
        assertEquals(listOf("https://example.com/a"), hrefs("\"https://example.com/a\""))
        assertEquals(emptyList(), hrefs("javascript:alert(1) ftp://example.com file:///etc/passwd"))
        assertEquals(emptyList(), hrefs("not a link: example dot com"))
    }

    @Test
    fun plainTextGoesThroughTheSanitizer() {
        val result = HtmlSanitizer.sanitize(HtmlSanitizer.plainToHtml("Hi <script>alert(1)</script> https://example.com/"), false)
        val doc = Jsoup.parse(result.html)
        assertEquals("https://example.com/", doc.selectFirst("a")!!.attr("href"))
        assertTrue(doc.select("script").isEmpty())
        assertEquals("white-space:pre-wrap", doc.selectFirst("div")!!.attr("style"))
        assertEquals("Hi <script>alert(1)</script> https://example.com/", doc.body().text())
    }

    @Test
    fun joinSingleHtmlPartUnchanged() {
        val html = "<html><body bgcolor=#fff><p>Hi</p></body></html>"
        val joined = HtmlSanitizer.join(listOf(part("html", "1") to html))
        assertEquals(html, joined.html)
        assertEquals("Hi", joined.text)
    }

    @Test
    fun joinPlainParts() {
        val joined = HtmlSanitizer.join(listOf(part("plain", "1") to "First\r\npart", part("plain", "2") to "Footer"))
        assertNull(joined.html)
        assertEquals("First\npart\n\nFooter", joined.text)
        assertNull(HtmlSanitizer.join(emptyList()).html)
    }

    @Test
    fun joinMixedParts() {
        val joined = HtmlSanitizer.join(
            listOf(
                part("html", "1") to "<html><head><style>p{color:red}</style></head><body><p>Hello <b>you</b></p><div>",
                part("plain", "2") to "-- \nList <footer> https://lists.example/x",
                part("html", "3") to "<p>Second</p>",
            ),
        )
        val html = joined.html!!
        val doc = Jsoup.parse(html)
        assertEquals("p{color:red}", doc.select("style").single().data())
        assertTrue("<p>Hello <b>you</b></p>" in html)
        assertTrue("List &lt;footer&gt; <a href=\"https://lists.example/x\">" in html)
        assertTrue(html.indexOf("Hello") < html.indexOf("List") && html.indexOf("List") < html.indexOf("Second"))
        assertFalse("<footer>" in html)
        assertEquals("Hello you\n\n-- \nList <footer> https://lists.example/x\n\nSecond", joined.text)
        // The joined body sanitizes as one document.
        val sanitized = Jsoup.parse(HtmlSanitizer.sanitize(html, false).html)
        assertEquals(1, sanitized.select("a[href=https://lists.example/x]").size)
    }

    @Test
    fun inlineImagesThatTheHtmlDoesNotShow() {
        val logo = image("logo@x", "1.2")
        val bg = image("BG@X", "1.3")
        val photo = image("photo@x", "1.4")
        val spare = image("spare@x", "1.5")
        val html = "<html><head><style>td{background:url(cid:bg@x)}</style></head><body>" +
            "<img src=\"cid:logo%40x\"><div style=\"background-image:url('cid:<photo@x>')\">Mentions cid:spare@x</div></body></html>"
        val joined = HtmlSanitizer.join(listOf(part("html", "1.1") to html), listOf(logo, bg, photo, spare))
        assertEquals(listOf(spare), joined.unreferencedImages)
        // Without HTML no image is shown inline.
        assertEquals(listOf(logo), HtmlSanitizer.join(listOf(part("plain", "1") to "Hi"), listOf(logo)).unreferencedImages)
        assertEquals(emptyList(), HtmlSanitizer.join(listOf(part("html", "1") to html)).unreferencedImages)
        // A cid in a link or in text is not an image.
        val link = HtmlSanitizer.join(listOf(part("html", "1") to "<a href=\"cid:logo@x\">logo</a>"), listOf(logo))
        assertEquals(listOf(logo), link.unreferencedImages)
    }

    @Test
    fun inlineImagesInJoinedParts() {
        val logo = image("logo@x", "2")
        val footer = image("footer@x", "4")
        val joined = HtmlSanitizer.join(
            listOf(part("html", "1") to "<p>Hi</p>", part("html", "3") to "<img src=\"cid:footer@x\">"),
            listOf(logo, footer),
        )
        assertEquals(listOf(logo), joined.unreferencedImages)
    }
}
