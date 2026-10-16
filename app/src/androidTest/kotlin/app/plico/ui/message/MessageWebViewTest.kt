package app.plico.ui.message

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import app.plico.html.HtmlSanitizer
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
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
    fun rendererDeathIsContained() {
        val gone = CountDownLatch(1)
        val view = show("<p>Hello</p>", Recorder()) { gone.countDown() }
        instrumentation.runOnMainSync { view.loadUrl("chrome://crash") }
        // Without onRenderProcessGone returning true, this process (the test, or push in the app) would die here.
        assertTrue(gone.await(20, TimeUnit.SECONDS))
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
