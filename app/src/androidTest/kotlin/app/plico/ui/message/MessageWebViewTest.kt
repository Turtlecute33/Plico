package app.plico.ui.message

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.ScrollView
import androidx.test.platform.app.InstrumentationRegistry
import app.plico.MainActivity
import app.plico.html.HtmlSanitizer
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** The message WebView on a real Chromium: what reaches the request handler, and renderer death (DESIGN §11.2). */
class MessageWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val views = ArrayList<WebView>()

    private class Recorder : BodyResources {
        val cids = CopyOnWriteArrayList<String>()
        val remote = CopyOnWriteArrayList<String>()

        override fun cidImage(contentId: String): Served {
            cids += contentId
            return Served("image/gif", ByteArrayInputStream(GIF))
        }

        override fun remoteImage(url: String): Served? {
            remote += url
            return null
        }
    }

    @After
    fun destroy() = instrumentation.runOnMainSync { views.forEach(WebView::destroy) }

    private fun show(html: String, resources: BodyResources, onGone: () -> Unit = {}): WebView {
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = messageWebView(context, Client(resources, { _, _ -> }, onGone))
            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        return view.also(views::add)
    }

    private fun waitFor(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!check()) {
            if (SystemClock.uptimeMillis() > end) fail("timed out")
            Thread.sleep(50)
        }
    }

    @Test
    fun cidImagesComeFromTheMessageAndRemoteImagesOnlyWhenAllowed() {
        val html = """<p>Hello</p><img src="cid:Logo@Example">""" +
            """<img src="https://images.example.com/a.png" width="200" height="80">"""

        val blocked = Recorder()
        show(HtmlSanitizer.sanitize(html, allowRemote = false).html, blocked)
        waitFor { blocked.cids.isNotEmpty() }
        Thread.sleep(500)
        assertEquals(listOf("Logo@Example"), blocked.cids)
        assertEquals(emptyList(), blocked.remote)

        val allowed = Recorder()
        show(HtmlSanitizer.sanitize(html, allowRemote = true).html, allowed)
        waitFor { allowed.remote.isNotEmpty() }
        assertEquals(listOf("https://images.example.com/a.png"), allowed.remote)
    }

    @Test
    fun aWideBodyIsZoomedOutToTheViewWidthAndANarrowOneIsNot() {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            // Like the message screen: as tall as its content, in a vertical scroll.
            fun shown(body: String): WebView {
                lateinit var view: WebView
                instrumentation.runOnMainSync {
                    view = messageWebView(activity, Client(Recorder(), { _, _ -> }, {}))
                    activity.addContentView(
                        ScrollView(activity).apply { addView(view) },
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                    view.loadDataWithBaseURL(null, HtmlSanitizer.sanitize(body, false).html, "text/html", "utf-8", null)
                }
                return view.also(views::add)
            }
            fun <T> onMain(read: () -> T): T {
                var value: T? = null
                instrumentation.runOnMainSync { value = read() }
                @Suppress("UNCHECKED_CAST")
                return value as T
            }
            val density = context.resources.displayMetrics.density

            fun settled(view: WebView) {
                waitFor { onMain { view.progress == 100 && view.contentHeight > 0 && view.height > 0 } }
                Thread.sleep(1_000)
            }

            val wide = shown("<table width=\"900\"><tr><td>Wide newsletter</td></tr></table>")
            settled(wide)
            assertFalse(onMain { wide.canScrollHorizontally(1) })
            @Suppress("DEPRECATION")
            val scale = onMain { wide.scale }
            assertTrue(scale < density)
            // The view stays as tall as the zoomed-out content: nothing cut off, no empty space below.
            assertEquals(onMain { wide.contentHeight * scale }, onMain { wide.height.toFloat() }, 2 * density)

            val narrow = shown("<p>Hello</p>")
            settled(narrow)
            assertFalse(onMain { narrow.canScrollHorizontally(1) })
            @Suppress("DEPRECATION")
            assertEquals(density, onMain { narrow.scale }, 0.01f)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun pinchZoomIsOnWithoutZoomButtons() {
        val view = show("<p>Hello</p>", Recorder())
        var zoom = emptyList<Boolean>()
        instrumentation.runOnMainSync {
            zoom = with(view.settings) { listOf(supportZoom(), builtInZoomControls, displayZoomControls) }
        }
        assertEquals(listOf(true, true, false), zoom)
    }

    @Test
    fun rendererDeathIsContained() {
        val gone = CountDownLatch(1)
        val view = show("<p>Hello</p>", Recorder()) { gone.countDown() }
        instrumentation.runOnMainSync { view.loadUrl("chrome://crash") }
        // Without onRenderProcessGone returning true, this process (the test, or push in the app) would die here.
        assertTrue(gone.await(20, TimeUnit.SECONDS))
    }

    @Test
    fun aFailingResourceIsA403NotACrash() {
        // E.g. the attachment-cache prune deleted the file between the check and the read.
        val failing = object : BodyResources {
            override fun cidImage(contentId: String): Served = throw FileNotFoundException("pruned")
            override fun remoteImage(url: String): Served = throw IllegalStateException("bug")
        }
        val client = Client(failing, { _, _ -> }, {})
        val view = show("<p>Hello</p>", Recorder())
        for (url in listOf("https://${HtmlSanitizer.CID_HOST}/logo@example", "https://images.example.com/a.png")) {
            // An exception that left this callback would end the process, and push with it.
            val response = client.shouldInterceptRequest(view, get(url))
            assertEquals(403, response?.statusCode, url)
        }
    }

    private fun get(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    private companion object {
        /** A 1x1 transparent GIF. */
        val GIF = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C,
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        )
    }
}
