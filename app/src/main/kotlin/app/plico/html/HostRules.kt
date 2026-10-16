package app.plico.html

import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale

/** Host rules for remote content and the unsubscribe POST (DESIGN.md §11.2, privacy PS12). String checks only. */
object HostRules {
    private val localSuffixes = listOf("localhost", "local", "lan", "home.arpa", "internal")

    /** Hosts that serve only open-tracking pixels and analytics. A host matches itself and its subdomains. */
    private val trackers = listOf(
        "google-analytics.com", "doubleclick.net", "list-manage.com", "mandrillapp.com", "ct.sendgrid.net",
        "exct.net", "rs6.net", "awstrack.me", "emltrk.com", "mailtrack.io", "t.yesware.com", "track.hubspot.com",
        "pi.pardot.com", "track.customer.io", "via.intercom.io", "mailfoogae.appspot.com", "r.superhuman.com",
        "open.convertkit-mail.com", "bananatag.com", "getnotify.com",
    )

    /**
     * False for IP literals (every form a browser reads as IP), `localhost`, single-label names and names under
     * `.local`, `.lan`, `.home.arpa`, `.internal` and `.localhost`.
     */
    fun allowed(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).removeSuffix(".")
        return '.' in h && !Idn.isIpAddress(h) && localSuffixes.none { h == it || h.endsWith(".$it") }
    }

    /** True for hosts on the built-in tracker list and their subdomains. */
    fun isTracker(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).removeSuffix(".")
        return trackers.any { h == it || h.endsWith(".$it") }
    }

    /** [url] as a [URL] when it is https, has no userinfo and its host is [allowed] (and not a tracker, if asked). */
    internal fun fetchable(url: String, blockTrackers: Boolean): URL? = try {
        val uri = URI(url)
        val host = uri.host
        val ok = uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null && host != null &&
            allowed(host) && !(blockTrackers && isTracker(host))
        if (ok) uri.toURL() else null
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: MalformedURLException) {
        null
    }

    /** The next hop of a redirect from [from] to [location], under the same rules; null when it is not allowed. */
    internal fun redirect(from: URL, location: String?, blockTrackers: Boolean): URL? = try {
        location?.let { fetchable(from.toURI().resolve(it.trim()).toString(), blockTrackers) }
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
