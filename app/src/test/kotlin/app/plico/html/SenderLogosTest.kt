package app.plico.html

import app.plico.data.Files
import app.plico.data.Prefs
import app.plico.html.RemoteFetcher.Fetched
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files as NioFiles
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The logo cache with a fake network and a fake image codec (images are strings here). */
class SenderLogosTest {
    private val base: File = NioFiles.createTempDirectory("plico-logos").toFile()
    private val files = Files(cacheDir = base, filesDir = base)
    private val dir = File(base, "logos")
    private var clock = 1_000_000_000_000L
    private var enabled = true
    private var knownResult = false
    private val knownCalls = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<String>()
    private val hops = CopyOnWriteArrayList<(URL) -> Boolean>()

    /** URL → answer; anything else is Missing. */
    private val answers = HashMap<String, Fetched>()
    private var onFetch: () -> Unit = {}
    private var onKnown: () -> Unit = {}

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    /** Server bytes "IMG:x" decode to the image "x"; the cache file holds "PNG:x". */
    private object FakeCodec : LogoCache.Codec<String> {
        override fun decode(bytes: ByteArray): String? = String(bytes).takeIf { it.startsWith("IMG:") }?.removePrefix("IMG:")
        override fun encode(image: String): ByteArray = "PNG:$image".toByteArray()
        override fun read(bytes: ByteArray): String? = String(bytes).takeIf { it.startsWith("PNG:") }?.removePrefix("PNG:")
        override fun byteCount(image: String): Int = image.length
    }

    private fun ok(image: String) = Fetched.Ok(RemoteFetcher.Resource("image/png", "IMG:$image".toByteArray()))

    private fun TestScope.cache() = LogoCache(
        files = files,
        codec = FakeCodec,
        enabled = { enabled },
        psl = { testPsl },
        known = { email, _, _ ->
            knownCalls += email
            onKnown()
            knownResult
        },
        fetch = { request, budget ->
            requests += request.url
            hops += request.hop
            onFetch()
            val answer = answers[request.url] ?: Fetched.Missing
            // Like RemoteFetcher: the body comes out of the budget, and a spent budget fails the fetch.
            val size = (answer as? Fetched.Ok)?.resource?.bytes?.size ?: 0
            if (budget != null && !budget.spend(size)) Fetched.Failed else answer
        },
        dispatcher = StandardTestDispatcher(testScheduler),
        now = { clock },
    )

    private fun hash(domain: String) =
        MessageDigest.getInstance("SHA-256").digest(domain.toByteArray()).joinToString("") { "%02x".format(it) }

    private val ddg = "https://icons.duckduckgo.com/ip3/example.com.ico"
    private val touchIcon = "https://example.com/apple-touch-icon.png"
    private val favicon = "https://example.com/favicon.ico"

    @Test
    fun duckDuckGoFetchesForAFirstContact() = runTest {
        answers[ddg] = ok("logo")
        val cache = cache()
        assertEquals("logo", cache.load("jane.doe@news.example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        assertEquals(listOf(ddg), requests)
        // A redirect may not leave DuckDuckGo: the sender's site never sees the phone (D20).
        assertFalse(hops.single()(URI("https://example.com/favicon.ico").toURL()))
        // The first-contact rule is only for the website source.
        assertTrue(knownCalls.isEmpty())
        // Memory by host, without I/O; the file holds the re-encoded image, never the server's bytes.
        assertEquals("logo", cache.cached("other@NEWS.example.com"))
        assertNull(cache.cached("x@example.com"))
        assertEquals("PNG:logo", File(dir, "${hash("example.com")}.png").readText())
    }

    @Test
    fun googleFetchesForAFirstContactToo() = runTest {
        val google = "https://www.google.com/s2/favicons?domain=example.com&sz=64"
        answers[google] = ok("g")
        assertEquals("g", cache().load("a@example.com", Prefs.LogoSource.GOOGLE, 0, 1))
        assertEquals(listOf(google), requests)
        assertTrue(knownCalls.isEmpty())
    }

    @Test
    fun websiteNeedsAKnownSender() = runTest {
        answers[touchIcon] = ok("site")
        val cache = cache()
        knownResult = false
        assertNull(cache.load("a@example.com", Prefs.LogoSource.WEBSITE, 0, 1))
        assertTrue(requests.isEmpty())
        knownResult = true
        assertEquals("site", cache.load("a@example.com", Prefs.LogoSource.WEBSITE, 0, 2))
        assertEquals(listOf(touchIcon), requests)
    }

    @Test
    fun websiteGivesAFirstContactTheCachedLogoOnly() = runTest {
        File(dir.apply { mkdirs() }, "${hash("example.com")}.png").apply {
            writeText("PNG:old")
            setLastModified(clock)
        }
        knownResult = false
        assertEquals("old", cache().load("new@example.com", Prefs.LogoSource.WEBSITE, 0, 1))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun knownStaysKnown() = runTest {
        knownResult = true
        val cache = cache()
        cache.load("A@example.com", Prefs.LogoSource.WEBSITE, 0, 1)
        knownResult = false
        clock += LogoRules.NONE_TTL_MS
        cache.load("a@example.com", Prefs.LogoSource.WEBSITE, 0, 2)
        assertEquals(1, knownCalls.size)
        assertEquals(4, requests.size)
    }

    @Test
    fun websiteFallsBackToTheFavicon() = runTest {
        knownResult = true
        answers[favicon] = ok("fav")
        assertEquals("fav", cache().load("a@example.com", Prefs.LogoSource.WEBSITE, 0, 1))
        assertEquals(listOf(touchIcon, favicon), requests)
    }

    @Test
    fun bytesThatDoNotDecodeCountAsMissing() = runTest {
        knownResult = true
        answers[touchIcon] = Fetched.Ok(RemoteFetcher.Resource("image/png", "<svg/>".toByteArray()))
        answers[favicon] = ok("fav")
        assertEquals("fav", cache().load("a@example.com", Prefs.LogoSource.WEBSITE, 0, 1))
    }

    @Test
    fun eachWebsiteUrlHasItsOwnByteBudget() = runTest {
        knownResult = true
        // A touch icon of the full size that does not decode must not use up the bytes of the favicon.
        answers[touchIcon] = Fetched.Ok(RemoteFetcher.Resource("image/png", ByteArray(LogoRules.MAX_BYTES)))
        answers[favicon] = ok("fav")
        assertEquals("fav", cache().load("a@example.com", Prefs.LogoSource.WEBSITE, 0, 1))
        assertEquals(listOf(touchIcon, favicon), requests)
    }

    @Test
    fun missingWritesANegativeEntry() = runTest {
        val cache = cache()
        assertNull(cache.load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        assertTrue(File(dir, "${hash("example.com")}.none").exists())
        assertNull(cache.load("b@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 2))
        // After a restart the disk still knows.
        assertNull(cache().load("c@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 3))
        assertEquals(1, requests.size)
        // After 7 days the domain is tried again.
        clock += LogoRules.NONE_TTL_MS
        answers[ddg] = ok("new")
        assertEquals("new", cache().load("c@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 3))
        assertFalse(File(dir, "${hash("example.com")}.none").exists())
    }

    @Test
    fun failedIsRememberedInMemoryForTenMinutesOnly() = runTest {
        answers[ddg] = Fetched.Failed
        val cache = cache()
        assertNull(cache.load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        assertNull(cache.load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        assertEquals(1, requests.size)
        // Nothing on disk: offline or a captive portal must not leave a lasting "no logo".
        assertTrue(dir.listFiles().isNullOrEmpty())
        assertNull(cache().load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        assertEquals(2, requests.size)
        clock += LogoRules.FAILED_TTL_MS
        answers[ddg] = ok("back")
        assertEquals("back", cache.load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        assertEquals(3, requests.size)
    }

    @Test
    fun aFailureStopsTheNextUrlOfTheWebsite() = runTest {
        knownResult = true
        answers[touchIcon] = Fetched.Failed
        answers[favicon] = ok("fav")
        assertNull(cache().load("a@example.com", Prefs.LogoSource.WEBSITE, 0, 1))
        assertEquals(listOf(touchIcon), requests)
    }

    @Test
    fun logosExpireAfterThirtyDays() = runTest {
        answers[ddg] = ok("one")
        cache().load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1)
        answers[ddg] = ok("two")
        assertEquals("one", cache().load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        clock += LogoRules.LOGO_TTL_MS
        assertEquals("two", cache().load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        assertEquals(2, requests.size)
    }

    @Test
    fun noFetchWhenLogosAreOff() = runTest {
        enabled = false
        answers[ddg] = ok("logo")
        assertNull(cache().load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun noFetchForHostsWithoutALogoDomain() = runTest {
        val cache = cache()
        for (email in listOf("a@gmail.com", "a@x.github.io", "a@1.2.3.4", "a@localhost", "a@foo.unknowntld", "no-address", "")) {
            assertNull(cache.load(email, Prefs.LogoSource.DUCKDUCKGO, 0, 1), email)
        }
        assertTrue(requests.isEmpty())
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun clearDropsMemoryAndDisk() = runTest {
        answers[ddg] = ok("logo")
        val cache = cache()
        cache.load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1)
        cache.clear()
        assertNull(cache.cached("a@example.com"))
        assertFalse(dir.exists())
        // The next load fetches again.
        assertEquals("logo", cache.load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        assertEquals(2, requests.size)
    }

    @Test
    fun aFetchThatEndsAfterClearWritesNothing() = runTest {
        answers[ddg] = ok("old")
        val cache = cache()
        onFetch = { cache.clear() }
        assertNull(cache.load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1))
        assertNull(cache.cached("a@example.com"))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun aNewerLoadNeverWaitsForAFetchOfAnOlderGeneration() = runTest {
        knownResult = true
        answers[touchIcon] = ok("new")
        lateinit var cache: LogoCache<String>
        var second: Deferred<String?>? = null
        onKnown = {
            if (second == null) {
                // clear() comes after the first load read its generation and before it starts its fetch; a
                // second load for the same domain starts at once.
                cache.clear()
                second = async(start = CoroutineStart.UNDISPATCHED) {
                    cache.load("b@example.com", Prefs.LogoSource.WEBSITE, 0, 2)
                }
            }
        }
        cache = cache()
        assertNull(cache.load("a@example.com", Prefs.LogoSource.WEBSITE, 0, 1))
        assertEquals("new", second!!.await())
        assertEquals(listOf(touchIcon), requests)
    }

    @Test
    fun oneFetchPerDomainAtATime() = runTest {
        answers[ddg] = ok("logo")
        val cache = cache()
        val first = async { cache.load("a@example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 1) }
        val second = async { cache.load("b@mail.example.com", Prefs.LogoSource.DUCKDUCKGO, 0, 2) }
        assertEquals("logo", first.await())
        assertEquals("logo", second.await())
        assertEquals(1, requests.size)
    }

    @Test
    fun pruneDeletesExpiredAndForeignFiles() = runTest {
        dir.mkdirs()
        val fresh = File(dir, "${hash("a.com")}.png").apply { writeText("PNG:a") }
        val old = File(dir, "${hash("b.com")}.png").apply { writeText("PNG:b") }
        val freshNone = File(dir, "${hash("c.com")}.none").apply { createNewFile() }
        val oldNone = File(dir, "${hash("d.com")}.none").apply { createNewFile() }
        val stray = File(dir, "logo123.tmp").apply { writeText("x") }
        fresh.setLastModified(clock - LogoRules.LOGO_TTL_MS + 60_000)
        old.setLastModified(clock - LogoRules.LOGO_TTL_MS - 60_000)
        freshNone.setLastModified(clock - 60_000)
        oldNone.setLastModified(clock - LogoRules.NONE_TTL_MS - 60_000)
        stray.setLastModified(clock)
        cache().prune(enabled = true, anyAccount = true)
        assertEquals(setOf(fresh.name, freshNone.name), dir.list()!!.toSet())
    }

    @Test
    fun pruneDeletesEverythingWhenOffOrWithoutAccounts() = runTest {
        for ((on, accounts) in listOf(false to true, true to false)) {
            dir.mkdirs()
            File(dir, "${hash("a.com")}.png").writeText("PNG:a")
            cache().prune(enabled = on, anyAccount = accounts)
            assertFalse(dir.exists())
        }
    }

    @Test
    fun memoryKeepsTheNewestWithinItsLimit() {
        val lru = ByteLru<String, String>(10) { it.length }
        lru.put("a", "1234")
        lru.put("b", "1234")
        lru.get("a")
        lru.put("c", "1234")
        // "b" was the least recently used.
        assertNull(lru.get("b"))
        assertEquals("1234", lru.get("a"))
        assertEquals("1234", lru.get("c"))
        // One entry larger than the limit is kept alone.
        lru.put("d", "x".repeat(50))
        assertEquals(1, lru.size)
        assertEquals("x".repeat(50), lru.get("d"))
        lru.clear()
        assertEquals(0, lru.size)
    }
}
