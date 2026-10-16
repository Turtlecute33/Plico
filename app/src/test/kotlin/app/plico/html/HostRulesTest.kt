package app.plico.html

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostRulesTest {
    @Test
    fun publicHostsAreAllowed() {
        for (host in listOf("example.com", "img.example.co.uk", "Example.COM.", "xn--e1afmkfd.xn--p1ai")) {
            assertTrue(HostRules.allowed(host), host)
        }
    }

    @Test
    fun localAndIpHostsAreRejected() {
        val hosts = listOf(
            "localhost", "LOCALHOST", "app.localhost", "intranet", "printer", "printer.local", "nas.lan", "home.arpa",
            "router.home.arpa", "svc.internal", "127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.169.254", "0x7f.1", "127.1",
            "2130706433", "[::1]", "::1", "[fd00::1]", "100.64.0.1", "", ".",
        )
        for (host in hosts) assertFalse(HostRules.allowed(host), host)
    }

    @Test
    fun trackers() {
        val trackers = listOf(
            "www.google-analytics.com", "google-analytics.com", "us12.list-manage.com", "u1.ct.sendgrid.net", "WWW.Google-Analytics.COM.",
        )
        for (host in trackers) {
            assertTrue(HostRules.isTracker(host), host)
        }
        for (host in listOf("example.com", "notgoogle-analytics.com", "sendgrid.net", "mcusercontent.com")) {
            assertFalse(HostRules.isTracker(host), host)
        }
    }

    @Test
    fun fetchableUrls() {
        assertEquals(URL("https://img.example/a.png?x=1"), HostRules.fetchable("https://img.example/a.png?x=1", blockTrackers = true))
        assertNull(HostRules.fetchable("http://img.example/a.png", blockTrackers = true))
        assertNull(HostRules.fetchable("ftp://img.example/a.png", blockTrackers = true))
        assertNull(HostRules.fetchable("https://user@img.example/a.png", blockTrackers = true))
        assertNull(HostRules.fetchable("https://127.0.0.1/a.png", blockTrackers = true))
        assertNull(HostRules.fetchable("https://printer.local/a.png", blockTrackers = true))
        assertNull(HostRules.fetchable("https://img example/a.png", blockTrackers = true))
        assertNull(HostRules.fetchable("https:///a.png", blockTrackers = true))
        assertNull(HostRules.fetchable("https://www.google-analytics.com/collect", blockTrackers = true))
        // The unsubscribe POST may go to a host that also tracks (Mailchimp).
        assertEquals(
            URL("https://us1.list-manage.com/unsubscribe/post?u=1"),
            HostRules.fetchable("https://us1.list-manage.com/unsubscribe/post?u=1", blockTrackers = false),
        )
    }

    @Test
    fun redirectsAreCheckedAtEveryHop() {
        val from = URL("https://img.example/a/b.png")
        assertEquals(URL("https://img.example/a/c.png"), HostRules.redirect(from, "c.png", blockTrackers = true))
        assertEquals(URL("https://cdn.example/x.png"), HostRules.redirect(from, " https://cdn.example/x.png ", blockTrackers = true))
        assertEquals(URL("https://img.example/root.png"), HostRules.redirect(from, "/root.png", blockTrackers = true))
        assertNull(HostRules.redirect(from, "http://img.example/x.png", blockTrackers = true))
        assertNull(HostRules.redirect(from, "https://localhost/x.png", blockTrackers = true))
        assertNull(HostRules.redirect(from, "https://10.0.0.1/x.png", blockTrackers = true))
        assertNull(HostRules.redirect(from, "//192.168.0.1/x.png", blockTrackers = true))
        assertNull(HostRules.redirect(from, "https://www.google-analytics.com/x", blockTrackers = true))
        assertNull(HostRules.redirect(from, "file:///etc/passwd", blockTrackers = true))
        assertNull(HostRules.redirect(from, "bad url with spaces", blockTrackers = true))
        assertNull(HostRules.redirect(from, null, blockTrackers = true))
    }

    @Test
    fun fetcherRejectsBeforeAnyNetworkAccess() {
        val budget = RemoteFetcher.Budget()
        assertNull(RemoteFetcher.image("http://img.example/a.png", budget))
        assertNull(RemoteFetcher.image("https://localhost/a.png", budget))
        assertNull(RemoteFetcher.image("https://www.google-analytics.com/collect", budget))
        assertNull(RemoteFetcher.image("javascript:alert(1)", budget))
        assertFalse(RemoteFetcher.unsubscribe("http://example.com/unsubscribe"))
        assertFalse(RemoteFetcher.unsubscribe("https://192.168.0.10/unsubscribe"))
    }

    @Test
    fun budget() {
        val budget = RemoteFetcher.Budget(maxBytes = 10, parallel = 1)
        assertTrue(budget.spend(6))
        assertTrue(budget.spend(4))
        assertFalse(budget.spend(1))
        assertTrue(budget.slots.tryAcquire())
        assertFalse(budget.slots.tryAcquire())
    }
}
