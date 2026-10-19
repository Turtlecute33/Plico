package app.plico.ui.message

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.plico.html.HtmlSanitizer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** A body resource for the WebView. */
class Served(val mimeType: String, val stream: InputStream)

/** Serves the subresources of the body on screen; called on WebView worker threads, may block. */
interface BodyResources {
    /** An inline image by Content-ID, or null (403). */
    fun cidImage(contentId: String): Served?

    /** An allowed remote image, or null (403). */
    fun remoteImage(url: String): Served?
}

/**
 * The sanitized body in a locked-down WebView (DESIGN §11.2, privacy PS7/PS13): no JS, no file, content or DOM
 * storage access, no cookies, no network of its own (subresources come only through [resources]), every link
 * to [onLink]. The view is as tall as its content; put it in a scrolling column.
 *
 * @param darkening algorithmic darkening (dark theme, "Dark email" on, not "Show original colours").
 * @param onLink a tapped link and the anchor text when the WebView knows it.
 * @param onRenderGone the renderer died; the caller removes this view and shows the text instead.
 */
@Composable
fun MessageWebView(
    html: String,
    darkening: Boolean,
    background: Color,
    resources: BodyResources,
    onLink: (url: String, text: String?) -> Unit,
    onRenderGone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textZoom = (LocalDensity.current.fontScale * 100).roundToInt()
    val link by rememberUpdatedState(onLink)
    val gone by rememberUpdatedState(onRenderGone)
    AndroidView(
        factory = { context ->
            messageWebView(context, Client(resources, onLink = { url, text -> link(url, text) }, onGone = { gone() }))
        },
        // Chromium can paint its (darkened) page background past the view's bounds; keep it inside.
        modifier = modifier.clipToBounds(),
        update = { view ->
            if (view.tag == Dead) return@AndroidView
            view.settings.textZoom = textZoom
            view.setBackgroundColor(background.toArgb())
            val shown = Shown(html, darkening)
            if (view.tag != shown) {
                view.tag = shown
                view.settings.isAlgorithmicDarkeningAllowed = darkening
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = WebView::destroy,
    )
}

/** What the view shows; kept in its tag so that recompositions do not reload it. */
private data class Shown(val html: String, val darkening: Boolean)

/** Tag of a view whose renderer died; it must not be used again. */
private object Dead

/** A WebView with the settings of DESIGN §11.2; [client] is a [Client]. */
internal fun messageWebView(context: Context, client: WebViewClient): WebView {
    DeadProxy.ensure()
    CookieManager.getInstance().setAcceptCookie(false)
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        with(settings) {
            javaScriptEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = true
            safeBrowsingEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            // Read the viewport tag of HtmlSanitizer and start zoomed out to the full width: no side scroll.
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR)) {
            WebSettingsCompat.setAttributionRegistrationBehavior(
                settings,
                WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED,
            )
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        isVerticalScrollBarEnabled = false
        webViewClient = client
    }
}

/**
 * Links go to [onLink]; subresources come from [resources], all else gets 403; a dead renderer calls [onGone].
 * The lint check takes Kotlin's super-constructor call for a plain `WebViewClient()`, hence the suppression.
 */
@SuppressLint("MissingOnRenderProcessGone")
internal class Client(
    private val resources: BodyResources,
    private val onLink: (String, String?) -> Unit,
    private val onGone: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Only taps: a navigation without a gesture (meta refresh, a form) goes nowhere.
        if (!request.hasGesture()) return true
        val url = request.url.toString()
        // The hit test knows the anchor text ("title"); it is used only when it belongs to this link.
        val reply = Handler(Looper.getMainLooper()) { msg ->
            onLink(url, msg.data.getString("title")?.takeIf { msg.data.getString("url") == url })
            true
        }.obtainMessage()
        view.requestFocusNodeHref(reply)
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        // The document itself (loadDataWithBaseURL) and data: images never reach the network.
        if (uri.scheme == "data" || uri.scheme == "about") return null
        val url = uri.toString()
        val cid = HtmlSanitizer.cidOf(url)
        // An exception that leaves this WebView callback would end the process, push included: 403 instead.
        val served = try {
            when {
                request.method != "GET" -> null
                cid != null -> resources.cidImage(cid)
                uri.scheme == "https" && uri.host != HtmlSanitizer.CID_HOST -> resources.remoteImage(url)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        return served?.let { WebResourceResponse(it.mimeType, null, 200, "OK", NO_STORE, it.stream) }
            ?: WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", NO_STORE, ByteArrayInputStream(EMPTY))
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.tag = Dead
        onGone()
        // True for crashes and for renderers the system killed: the process (and push) lives on.
        return true
    }

    private companion object {
        val NO_STORE = mapOf("Cache-Control" to "no-store")
        val EMPTY = ByteArray(0)
    }
}

/**
 * Process-wide dead proxy for every WebView (DESIGN §11.2, research G10): even a load that slips past
 * blockNetworkLoads cannot reach the network.
 */
private object DeadProxy {
    private val done = AtomicBoolean(false)

    fun ensure() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        if (!done.compareAndSet(false, true)) return
        val config = ProxyConfig.Builder().addProxyRule("0.0.0.0").removeImplicitRules().build()
        ProxyController.getInstance().setProxyOverride(config, Runnable::run) {}
    }
}
