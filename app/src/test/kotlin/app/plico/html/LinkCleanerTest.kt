package app.plico.html

import org.json.JSONObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkCleanerTest {
    private val cleaner = LinkCleaner(testPsl)

    private fun web(url: String, text: String? = null): Link.Web = assertIs<Link.Web>(cleaner.clean(url, text), url)

    private fun unsupported(url: String): Link.Unsupported = assertIs<Link.Unsupported>(cleaner.clean(url), url)

    @Test
    fun plainLinkStaysTheSame() {
        val link = web("https://www.example.com/path?id=1#top")
        assertEquals("https://www.example.com/path?id=1#top", link.url)
        assertEquals("www.example.com", link.host)
        assertEquals("example.com", link.domain)
        assertEquals(emptySet(), link.warnings)
        assertNull(link.original)
    }

    @Test
    fun googleRedirectQ() {
        val wrapped = "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage%3Fid%3D1" +
            "&sa=D&source=gmail&ust=1700000000000&usg=AOvVaw0"
        val link = web(wrapped)
        assertEquals("https://example.com/page?id=1", link.url)
        assertEquals(wrapped, link.original)
        // Unencoded q value, as in some bodies.
        assertEquals("https://example.com/a", web("https://google.com/url?q=https://example.com/a&sa=D").url)
    }

    @Test
    fun googleRedirectUrl() {
        assertEquals("https://example.org/", web("https://www.google.com/url?sa=t&rct=j&url=https%3A%2F%2Fexample.org%2F&usg=x").url)
        assertEquals("https://example.org/", web("https://www.google.co.uk/url?url=https%3A%2F%2Fexample.org%2F").url)
        // Not the redirector path.
        val search = "https://www.google.com/search?q=https%3A%2F%2Fexample.org"
        assertEquals(search, web(search).url)
    }

    @Test
    fun googleSubdomainOfOtherDomainIsNotUnwrapped() {
        // Anyone can register xqz.de or abc.io and add a "google" label.
        for (host in listOf("google.xqz.de", "www.google.abc.io")) {
            val wrapped = "https://$host/url?q=https%3A%2F%2Fexample.com%2F"
            val link = web(wrapped)
            assertEquals(wrapped, link.url)
            assertEquals(host, link.host)
        }
        // Real Google hosts still unwrap.
        for (host in listOf("www.google.com", "google.com", "www.google.co.uk")) {
            assertEquals("https://example.com/", web("https://$host/url?q=https%3A%2F%2Fexample.com%2F").url)
        }
    }

    @Test
    fun outlookSafeLinks() {
        val wrapped = "https://nam12.safelinks.protection.outlook.com/?url=https%3A%2F%2Fexample.com%2Fa%3Futm_source%3Dnews%26id%3D7" +
            "&data=05%7C01%7Cuser%40example.com&sdata=abc%3D&reserved=0"
        assertEquals("https://example.com/a?id=7", web(wrapped).url)
    }

    @Test
    fun urlDefenseV3() {
        // Proofpoint's own sample: "*" stands for "+" (base64 "Kw").
        val wrapped = "https://urldefense.com/v3/__https://google.com:443/search?q=a*test&gs=ps__;Kw!-612Flbf0JvQ3kNJkRi5Jg!" +
            "Ue6tQudNKaShHg93trcdjqDP8se2ySE65jyCIe2K1D_uNjZ1Lnf6YLQERujngZv9UWf66ujQIQ$"
        assertEquals("https://google.com/search?q=a+test&gs=ps", web(wrapped).url)
        // "**A" is a run of two characters: "&&" is base64 "JiY".
        assertEquals("https://example.com/x&&y", web("https://urldefense.com/v3/__https://example.com/x**Ay__;JiY!!abc\$").url)
        // Missing replacement data: no unwrap.
        assertEquals("urldefense.com", web("https://urldefense.com/v3/__https://example.com/x*__;!!abc\$").host)
    }

    @Test
    fun awsTrack() {
        val wrapped = "https://xy4ab3cd.r.us-east-1.awstrack.me/L0/https:%2F%2Fexample.com%2Fpage%3Fa=1/1/010001234567-abcd-000000/aBcD=123"
        assertEquals("https://example.com/page?a=1", web(wrapped).url)
        // The open pixel path is not a link wrapper.
        assertEquals("xy4ab3cd.r.us-east-1.awstrack.me", web("https://xy4ab3cd.r.us-east-1.awstrack.me/I0/0100/abc").host)
    }

    @Test
    fun mandrillTrackClick() {
        val inner = JSONObject().put("u", 1).put("v", 1).put("url", "https://example.com/x?utm_medium=email&k=2").put("id", "abc")
        val outer = JSONObject().put("s", "sig").put("v", 1).put("p", inner.toString())
        val p = Base64.getUrlEncoder().encodeToString(outer.toString().toByteArray())
        assertEquals("https://example.com/x?k=2", web("https://mandrillapp.com/track/click/30063573/example.com?p=$p").url)
        // Broken data: the wrapper itself is shown.
        assertEquals("mandrillapp.com", web("https://mandrillapp.com/track/click/1/x?p=%%%").host)
    }

    @Test
    fun nestedWrappers() {
        val safe = "https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fexample.net%2Fdeal%3Ffbclid%3Dx&data=1"
        val google = "https://www.google.com/url?q=" + java.net.URLEncoder.encode(safe, Charsets.UTF_8)
        assertEquals("https://example.net/deal", web(google).url)
    }

    @Test
    fun trackingParameters() {
        assertEquals(
            "https://example.com/?id=5&page=2#top",
            web("https://example.com/?utm_source=a&id=5&fbclid=x&UTM_Campaign=y&gclid=z&page=2&mc_eid=1&_hsenc=2&hsCtaTracking=3#top").url,
        )
        assertEquals("https://example.com/a", web("https://example.com/a?utm_medium=email&igshid=1&msclkid=2&s_cid=3").url)
        assertEquals("https://example.com/a", web("https://example.com/a?").url)
        val link = web("https://example.com/a?utm_source=x")
        assertEquals("https://example.com/a?utm_source=x", link.original)
    }

    @Test
    fun unwrappedSchemesOtherThanHttpAreNotOpened() {
        val targets = listOf(
            "javascript:alert(1)", "intent://scan/#Intent;scheme=zxing;end", "file:///data/data/app.plico/databases/plico.db",
            "content://app.plico.files/att/1/2", "market://details?id=evil", "data:text/html,<script>x</script>",
        )
        for (inner in targets) {
            val wrapped = "https://www.google.com/url?q=" + java.net.URLEncoder.encode(inner, Charsets.UTF_8)
            val link = unsupported(wrapped)
            assertEquals(inner, link.text)
            assertEquals(wrapped, link.original)
        }
        assertIs<Link.Unsupported>(cleaner.clean("ftp://example.com/file"))
        assertIs<Link.Unsupported>(cleaner.clean("tel:+123"))
    }

    @Test
    fun mailtoGoesToCompose() {
        val mail = assertIs<Link.Mail>(cleaner.clean("mailto:alice@example.com?subject=Hi"))
        assertEquals("alice@example.com", mail.mailto.to.single().email)
        assertEquals("Hi", mail.mailto.subject)
        val unwrapped = assertIs<Link.Mail>(cleaner.clean("https://www.google.com/url?q=mailto%3Abob%40example.com"))
        assertEquals("bob@example.com", unwrapped.mailto.to.single().email)
    }

    @Test
    fun userinfoTricksAreRejected() {
        unsupported("https://good.com@evil.com/")
        unsupported("https://evil.com\\@good.com/")
        unsupported("https://user:pass@example.com/")
        unsupported("https://www.google.com/url?q=https%3A%2F%2Fevil.com%5C%40good.com")
        unsupported("https://www.google.com/url?q=https%3A%2F%2Fgood.com%40evil.com")
        unsupported("https://evil.com%40good.com/")
    }

    @Test
    fun whitespaceAndControlsAreRejected() {
        unsupported("https://exa mple.com/")
        unsupported("https://example.com/a b")
        unsupported("https://example.com/a\nb")
        unsupported("https://example.com/a\tb")
        unsupported("https://example.com/\u0000")
        unsupported("https://example.com/\u202Egpj.exe")
        unsupported("https://exam\u200Bple.com/")
        unsupported("https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fa%0Ab")
        // The sheet text shows hidden characters as U+FFFD.
        assertEquals("https://example.com/\uFFFDgpj.exe", unsupported("https://example.com/\u202Egpj.exe").text)
        // Leading and trailing whitespace is only trimmed.
        assertEquals("https://example.com/", web("  https://example.com/ ").url)
    }

    @Test
    fun rebuildNormalizesParts() {
        assertEquals("https://example.com/", web("HTTPS://Example.COM:443").url)
        assertEquals("http://example.com:8080/x", web("http://example.com:8080/x").url)
        assertEquals("https://example.com/p%C3%A4th?q=%C3%BC#%C3%A9", web("https://example.com/päth?q=ü#é").url)
        assertEquals("https://example.com/a%22%3C%3E%7Cb?x=%25zz", web("https://example.com/a\"<>|b?x=%zz").url)
        assertEquals("https://example.com/", web("https://ex%61mple.com/").url)
        assertEquals("https://example.com/", web("https://example.com./").url)
        assertEquals("https://[2001:db8::1]:8443/", web("https://[2001:DB8::1]:8443/").url)
        unsupported("https://example.com:99999/")
        unsupported("https://example.com:12a/")
        unsupported("https://example.com:\u0668\u0660/")
        unsupported("https://example.com:0/")
        unsupported("https:example.com")
        unsupported("https://")
    }

    @Test
    fun idnHomographs() {
        val apple = web("https://аpple.com/login")
        assertEquals("https://xn--pple-43d.com/login", apple.url)
        assertEquals("xn--pple-43d.com", apple.domain)
        assertEquals(setOf(LinkWarning.MIXED_SCRIPT, LinkWarning.LOOKALIKE), apple.warnings)
        // WebView URLs are already punycode.
        assertEquals(apple.warnings, web("https://xn--pple-43d.com/login").warnings)
        assertEquals(setOf(LinkWarning.LOOKALIKE), web("https://аррӏе.com/").warnings)
        val russian = web("https://пример.рф/")
        assertEquals("https://xn--e1afmkfd.xn--p1ai/", russian.url)
        assertEquals(emptySet(), russian.warnings)
    }

    @Test
    fun ipHosts() {
        val link = web("http://192.168.0.1/admin")
        assertEquals(setOf(LinkWarning.IP_HOST), link.warnings)
        assertNull(link.domain)
        assertTrue(LinkWarning.IP_HOST in web("http://0x7f.1/").warnings)
        assertTrue(LinkWarning.IP_HOST in web("https://[::1]/").warnings)
    }

    @Test
    fun linkTextWithAnotherDomain() {
        assertEquals(setOf(LinkWarning.OTHER_DOMAIN), web("https://evil.example/login", "https://www.paypal.com/signin").warnings)
        assertEquals(setOf(LinkWarning.OTHER_DOMAIN), web("https://paypal.com.evil.net/", "paypal.com").warnings)
        assertEquals(setOf(LinkWarning.OTHER_DOMAIN), web("https://192.168.0.1/", "mybank.com").warnings - LinkWarning.IP_HOST)
        assertEquals(emptySet(), web("https://paypal.com/x", "www.PayPal.com").warnings)
        assertEquals(emptySet(), web("https://news.example.co.uk/a", "Read it on example.co.uk").warnings)
        assertEquals(emptySet(), web("https://evil.example/", "Click here").warnings)
        assertEquals(emptySet(), web("https://evil.example/", "Version 2.0 e.g. this").warnings)
        // The registrable domain decides: two github.io sites differ.
        assertEquals(setOf(LinkWarning.OTHER_DOMAIN), web("https://evil.github.io/", "good.github.io").warnings)
        // Text that mentions the real site among others does not warn.
        assertEquals(emptySet(), web("https://example.com/", "example.com and other.org").warnings)
    }

    @Test
    fun registrableDomainForTheSheet() {
        assertEquals("bbc.co.uk", web("https://www.bbc.co.uk/news").domain)
        assertEquals("evil.github.io", web("https://evil.github.io/x").domain)
        assertNull(web("https://github.io/").domain)
    }
}
