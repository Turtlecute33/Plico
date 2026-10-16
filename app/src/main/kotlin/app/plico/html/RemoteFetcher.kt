package app.plico.html

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches remote content after the user allowed it, and sends the one-click unsubscribe POST (DESIGN.md §11.2,
 * privacy PS12). HTTPS only; no cookies (the app installs no `CookieHandler`), no Referer, no Accept-Language,
 * `User-Agent: Mozilla/5.0`. At most 3 redirects, each checked with [HostRules]. Per request: 10 s connect and
 * read timeouts, 20 s in total, 5 MB. Blocking: call from a worker thread (`shouldInterceptRequest` is one).
 */
object RemoteFetcher {
    private const val TIMEOUT_MS = 10_000
    private const val TOTAL_MS = 20_000L
    private const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_REDIRECTS = 3
    private const val USER_AGENT = "Mozilla/5.0"
    private val oneClick = "List-Unsubscribe=One-Click".toByteArray()

    /** Closes connections that pass the total deadline; a slow server could otherwise stay under the read timeout. */
    private val deadlines by lazy {
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "remote-deadline").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }

    /** Limits for the remote content of one message on screen: [maxBytes] in total, [parallel] fetches at a time. */
    class Budget(maxBytes: Long = 20L * 1024 * 1024, parallel: Int = 4) {
        internal val slots = Semaphore(parallel)
        private val left = AtomicLong(maxBytes)

        internal fun spend(bytes: Int): Boolean = left.addAndGet(-bytes.toLong()) >= 0
    }

    /** A fetched resource; [mimeType] is the server's Content-Type without parameters. */
    class Resource(val mimeType: String, val bytes: ByteArray)

    /**
     * GET of an image or other remote resource of a message. Null when a rule blocks it (also hosts on the
     * tracker list, at every hop), on any error, or when a limit is reached.
     */
    fun image(url: String, budget: Budget): Resource? {
        var target = HostRules.fetchable(url, blockTrackers = true) ?: return null
        try {
            budget.slots.acquire()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_MS)
            repeat(MAX_REDIRECTS + 1) {
                val result = withDeadline(open(target), deadline) { conn ->
                    conn.setRequestProperty("Accept", "image/*")
                    val code = conn.responseCode
                    when {
                        code in 300..399 ->
                            HostRules.redirect(target, conn.getHeaderField("Location"), blockTrackers = true)
                        code != HttpURLConnection.HTTP_OK || conn.contentLengthLong > MAX_BYTES -> null
                        else -> conn.inputStream.use { read(it, budget) }?.let { bytes ->
                            val type = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
                            Resource(if (type.isNullOrEmpty()) "application/octet-stream" else type, bytes)
                        }
                    }
                }
                when (result) {
                    is URL -> target = result
                    is Resource -> return result
                    else -> return null
                }
            }
            return null
        } catch (_: Exception) {
            // Any failure is a missing image; an exception here would end the process, push included.
            return null
        } finally {
            budget.slots.release()
        }
    }

    /**
     * RFC 8058 one-click unsubscribe: `POST` with `List-Unsubscribe=One-Click`. Redirects are not followed.
     * True on a 2xx or 3xx reply.
     */
    fun unsubscribe(url: String): Boolean {
        val target = HostRules.fetchable(url, blockTrackers = false) ?: return false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_MS)
        return try {
            withDeadline(open(target), deadline) { conn ->
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setFixedLengthStreamingMode(oneClick.size)
                conn.outputStream.use { it.write(oneClick) }
                conn.responseCode in 200..399
            } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun open(url: URL): HttpsURLConnection = (url.openConnection() as HttpsURLConnection).apply {
        instanceFollowRedirects = false
        useCaches = false
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("User-Agent", USER_AGENT)
    }

    /** Runs [block]; null when [deadline] (System.nanoTime) has passed. The connection is closed afterwards. */
    private fun <T> withDeadline(conn: HttpsURLConnection, deadline: Long, block: (HttpsURLConnection) -> T): T? {
        val left = deadline - System.nanoTime()
        if (left <= 0) return null
        val timer = deadlines.schedule({ conn.disconnect() }, left, TimeUnit.NANOSECONDS)
        try {
            return block(conn)
        } finally {
            timer.cancel(false)
            conn.disconnect()
        }
    }

    /** The body, or null when it is larger than 5 MB or the message budget is spent. */
    private fun read(input: InputStream, budget: Budget): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_BYTES || !budget.spend(n)) return null
            out.write(buffer, 0, n)
        }
    }
}
