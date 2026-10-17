package app.plico.html

import app.plico.data.Prefs
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogoRulesTest {
    private fun domain(email: String) = LogoRules.domainOf(email, testPsl)

    @Test
    fun registrableDomainOfTheSender() {
        assertEquals("example.com", domain("news@example.com"))
        assertEquals("example.com", domain("News@Mail.Example.COM"))
        assertEquals("example.co.uk", domain("a@x.y.example.co.uk"))
        assertEquals("example.com", domain("a@example.com."))
        // The last @ decides.
        assertEquals("example.org", domain("\"a@evil.net\"@example.org"))
        assertEquals("xn--bcher-kva.de", domain("info@bücher.de"))
    }

    @Test
    fun hostsThatGetNoLogo() {
        assertNull(domain("a@1.2.3.4"))
        assertNull(domain("a@[1.2.3.4]"))
        assertNull(domain("a@[IPv6:::1]"))
        assertNull(domain("a@localhost"))
        assertNull(domain("a@intranet"))
        assertNull(domain("a@printer.local"))
        assertNull(domain("a@nas.home.arpa"))
        assertNull(domain("a@example.com:8080"))
        assertNull(domain("no-at-sign.example.com"))
        assertNull(domain(""))
        assertNull(domain("a@"))
        assertNull(domain("a@bad host.com"))
    }

    @Test
    fun lookalikeAndMixedScriptNames() {
        // аррӏе.com: all Cyrillic letters that look like Latin.
        assertNull(domain("a@аррӏе.com"))
        // аpple.com: a Cyrillic а in a Latin label.
        assertNull(domain("a@аpple.com"))
        assertNull(domain("a@xn--pple-43d.com"))
    }

    @Test
    fun privateAndUnlistedSuffixes() {
        assertNull(domain("a@a.pages.dev"))
        assertNull(domain("a@www.a.pages.dev"))
        assertNull(domain("a@x.github.io"))
        assertNull(domain("a@b.blogspot.com"))
        assertNull(domain("a@foo.unknowntld"))
        // A public suffix itself has no registrable domain.
        assertNull(domain("a@co.uk"))
    }

    @Test
    fun mailboxProvidersAreSkipped() {
        assertNull(domain("someone@gmail.com"))
        assertNull(domain("someone@googlemail.com"))
        assertNull(domain("someone@riseup.net"))
        assertNull(domain("someone@outlook.com"))
        assertNull(domain("someone@hotmail.co.uk"))
        assertNull(domain("someone@proton.me"))
        // A company that hosts its mail at Google is still its own site.
        assertEquals("example.org", domain("someone@example.org"))
    }

    @Test
    fun urlsCarryOnlyTheDomain() {
        val email = "jane.doe+tag@shop.example.com"
        val d = assertNotNull(domain(email))
        for (source in Prefs.LogoSource.entries) {
            val urls = LogoRules.urls(source, d)
            assertTrue(urls.isNotEmpty())
            for (url in urls) {
                assertTrue(url.startsWith("https://"), url)
                assertNotNull(HostRules.fetchable(url, blockTrackers = true), url)
                assertFalse('@' in url, url)
                assertFalse("jane" in url || "doe" in url || "tag" in url || "shop." in url, url)
                assertTrue(d in url, url)
            }
        }
    }

    @Test
    fun urlsPerSource() {
        assertEquals(
            listOf("https://icons.duckduckgo.com/ip3/example.com.ico"),
            LogoRules.urls(Prefs.LogoSource.DUCKDUCKGO, "example.com"),
        )
        assertEquals(
            listOf("https://www.google.com/s2/favicons?domain=example.com&sz=64"),
            LogoRules.urls(Prefs.LogoSource.GOOGLE, "example.com"),
        )
        assertEquals(
            listOf("https://example.com/apple-touch-icon.png", "https://example.com/favicon.ico"),
            LogoRules.urls(Prefs.LogoSource.WEBSITE, "example.com"),
        )
    }

    @Test
    fun urlHostsMatchAbout() {
        for (source in listOf(Prefs.LogoSource.DUCKDUCKGO, Prefs.LogoSource.GOOGLE)) {
            val hosts = LogoRules.hosts(source)
            for (url in LogoRules.urls(source, "example.com")) assertTrue(URI(url).host in hosts, url)
        }
        assertEquals(listOf("icons.duckduckgo.com"), LogoRules.hosts(Prefs.LogoSource.DUCKDUCKGO))
        assertEquals(listOf("www.google.com", "*.gstatic.com"), LogoRules.hosts(Prefs.LogoSource.GOOGLE))
        assertEquals(emptyList(), LogoRules.hosts(Prefs.LogoSource.WEBSITE))
    }

    @Test
    fun duckDuckGoAndGoogleReachOnlyTheirOwnHosts() {
        val ddg = LogoRules.hopFor(Prefs.LogoSource.DUCKDUCKGO)
        assertTrue(ddg(URI("https://icons.duckduckgo.com/ip3/example.com.ico").toURL()))
        assertFalse(ddg(URI("https://example.com/favicon.ico").toURL()))
        assertFalse(ddg(URI("https://duckduckgo.com.evil.com/x.ico").toURL()))
        val google = LogoRules.hopFor(Prefs.LogoSource.GOOGLE)
        assertTrue(google(URI("https://www.google.com/s2/favicons?domain=example.com&sz=64").toURL()))
        assertTrue(google(URI("https://t3.gstatic.com/faviconV2?url=https://example.com").toURL()))
        assertFalse(google(URI("https://evil.com/favicon.ico").toURL()))
        assertFalse(google(URI("https://gstatic.com.evil.com/x").toURL()))
        // The website source follows the redirects of the sender's site.
        assertTrue(LogoRules.hopFor(Prefs.LogoSource.WEBSITE)(URI("https://cdn.example.net/icon.png").toURL()))
        for (source in listOf(Prefs.LogoSource.DUCKDUCKGO, Prefs.LogoSource.GOOGLE)) {
            for (url in LogoRules.urls(source, "example.com")) assertTrue(LogoRules.hopFor(source)(URI(url).toURL()), url)
        }
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun sniffKnownFormats() {
        assertEquals(LogoRules.ImageKind.PNG, LogoRules.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals(LogoRules.ImageKind.JPEG, LogoRules.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(LogoRules.ImageKind.GIF, LogoRules.sniff("GIF89a....".toByteArray()))
        assertEquals(LogoRules.ImageKind.GIF, LogoRules.sniff("GIF87a....".toByteArray()))
        assertEquals(LogoRules.ImageKind.WEBP, LogoRules.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals(LogoRules.ImageKind.ICO, LogoRules.sniff(bytes(0, 0, 1, 0, 2, 0, 16, 16)))
    }

    @Test
    fun sniffRefusesEverythingElse() {
        assertNull(LogoRules.sniff("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray()))
        assertNull(LogoRules.sniff("<?xml version=\"1.0\"?><svg/>".toByteArray()))
        assertNull(LogoRules.sniff("<!DOCTYPE html><html></html>".toByteArray()))
        assertNull(LogoRules.sniff("BM....".toByteArray()))
        assertNull(LogoRules.sniff(ByteArray(0)))
        // Truncated magic numbers.
        assertNull(LogoRules.sniff(bytes(0x89, 0x50, 0x4E, 0x47)))
        assertNull(LogoRules.sniff(bytes(0xFF, 0xD8)))
        assertNull(LogoRules.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
        // ICO with zero images, and a cursor (type 2).
        assertNull(LogoRules.sniff(bytes(0, 0, 1, 0, 0, 0)))
        assertNull(LogoRules.sniff(bytes(0, 0, 2, 0, 1, 0)))
    }

    @Test
    fun decoderTypesMustMatchTheSniffedFormat() {
        assertTrue(LogoRules.ImageKind.ICO.matches("image/x-ico"))
        assertTrue(LogoRules.ImageKind.ICO.matches("image/x-icon"))
        assertTrue(LogoRules.ImageKind.ICO.matches("image/vnd.microsoft.icon"))
        assertTrue(LogoRules.ImageKind.PNG.matches("image/png"))
        assertFalse(LogoRules.ImageKind.PNG.matches("image/jpeg"))
        assertFalse(LogoRules.ImageKind.JPEG.matches("image/png"))
        assertFalse(LogoRules.ImageKind.PNG.matches(null))
        assertFalse(LogoRules.ImageKind.GIF.matches("image/svg+xml"))
    }

    @Test
    fun sizeLimits() {
        assertTrue(LogoRules.sizeAllowed(32, 32))
        assertTrue(LogoRules.sizeAllowed(512, 512))
        assertTrue(LogoRules.sizeAllowed(64, 180))
        assertFalse(LogoRules.sizeAllowed(16, 16))
        assertFalse(LogoRules.sizeAllowed(31, 64))
        assertFalse(LogoRules.sizeAllowed(600, 600))
        assertFalse(LogoRules.sizeAllowed(64, 513))
        assertEquals(64 to 64, LogoRules.targetSize(64, 64))
        assertEquals(128 to 128, LogoRules.targetSize(128, 128))
        assertEquals(128 to 128, LogoRules.targetSize(512, 512))
        assertEquals(128 to 64, LogoRules.targetSize(512, 256))
        assertEquals(45 to 128, LogoRules.targetSize(180, 512))
    }

    @Test
    fun ttlDecisions() {
        val day = 24L * 60 * 60 * 1000
        val now = 1_000L * day
        assertTrue(LogoRules.fresh(LogoRules.Entry.LOGO, now, now))
        assertTrue(LogoRules.fresh(LogoRules.Entry.LOGO, now - 29 * day, now))
        assertFalse(LogoRules.fresh(LogoRules.Entry.LOGO, now - 30 * day, now))
        assertTrue(LogoRules.fresh(LogoRules.Entry.NONE, now - 6 * day, now))
        assertFalse(LogoRules.fresh(LogoRules.Entry.NONE, now - 7 * day, now))
        // A file from the future (the clock went back) is expired.
        assertFalse(LogoRules.fresh(LogoRules.Entry.LOGO, now + 1, now))
        assertTrue(LogoRules.failureActive(now - 9 * 60_000, now))
        assertFalse(LogoRules.failureActive(now - 10 * 60_000, now))
        assertFalse(LogoRules.failureActive(now + 1, now))
    }

    @Test
    fun cacheFileNames() {
        val hash = "a".repeat(64)
        assertEquals(LogoRules.Entry.LOGO, LogoRules.entryOf("$hash.png"))
        assertEquals(LogoRules.Entry.NONE, LogoRules.entryOf("$hash.none"))
        assertNull(LogoRules.entryOf("$hash.png.tmp"))
        assertNull(LogoRules.entryOf("logo123.tmp"))
        assertNull(LogoRules.entryOf("${"A".repeat(64)}.png"))
        assertNull(LogoRules.entryOf("example.com.png"))
        assertNull(LogoRules.entryOf("$hash.jpg"))
    }
}
