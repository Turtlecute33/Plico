package app.plico.html

import java.io.ByteArrayOutputStream
import java.io.IOException
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
 * `User-Agent: Mozilla/5.0`. Redirects are followed only when every hop passes [HostRules]. 10 s connect and read
 * timeouts and a total deadline per request. Blocking: call from a worker thread (`shouldInterceptRequest` is one).
 */
object RemoteFetcher {
    private const val TIMEOUT_MS = 10_000
    private const val TOTAL_MS = 20_000L
    private const val MAX_BYTES = 5 * 1024 * 1024
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
     * One GET. [accept] is the Accept header; [type] decides on the Content-Type (without parameters,
     * `application/octet-stream` when there is none); [maxBytes] limits the body. [hop] must accept every URL,
     * the first one and each redirect target; [totalMs] is the deadline of all hops together.
     */
    class Request(
        val url: String,
        val accept: String,
        val maxBytes: Int,
        val type: (String) -> Boolean,
        val maxRedirects: Int = 3,
        val hop: (URL) -> Boolean = { true },
        val totalMs: Long = TOTAL_MS,
        val blockTrackers: Boolean = true,
    )

    /** The result of [fetch]. */
    sealed interface Fetched {
        class Ok(val resource: Resource) : Fetched

        /** The server has no usable resource: HTTP 4xx other than 408 and 429, a refused type, or too large. */
        data object Missing : Fetched

        /**
         * No answer about the resource: an I/O error, HTTP 408, 429 or 5xx, the deadline, a spent [Budget], too
         * many redirects, or a URL that a rule or [Request.hop] blocks.
         */
        data object Failed : Fetched
    }

    /**
     * GET of [request]. [HostRules] apply at every hop (with the tracker list when [Request.blockTrackers]);
     * [budget], when given, limits the parallel fetches and the bytes. Never throws.
     */
    fun fetch(request: Request, budget: Budget? = null): Fetched {
        try {
            budget?.slots?.acquire()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Fetched.Failed
        }
        try {
            var target = allowed(HostRules.fetchable(request.url, request.blockTrackers), request)
                ?: return Fetched.Failed
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.totalMs)
            repeat(request.maxRedirects + 1) {
                val result = withDeadline(open(target), deadline) { conn ->
                    conn.setRequestProperty("Accept", request.accept)
                    val code = conn.responseCode
                    when {
                        code in 300..399 -> allowed(
                            HostRules.redirect(target, conn.getHeaderField("Location"), request.blockTrackers),
                            request,
                        ) ?: Fetched.Failed
                        code == HttpURLConnection.HTTP_OK -> body(conn, request, budget)
                        code == 408 || code == 429 || code >= 500 -> Fetched.Failed
                        else -> Fetched.Missing
                    }
                }
                when (result) {
                    is URL -> target = result
                    is Fetched -> return result
                    else -> return Fetched.Failed
                }
            }
            return Fetched.Failed
        } catch (_: Exception) {
            // An exception here would end the process, push included.
            return Fetched.Failed
        } finally {
            budget?.slots?.release()
        }
    }

    /**
     * GET of an image or other remote resource of a message. Null when a rule blocks it (also hosts on the
     * tracker list, at every hop), on any error, or when a limit is reached (5 MB, 20 s, 3 redirects).
     */
    fun image(url: String, budget: Budget): Resource? =
        (fetch(Request(url, "image/*", MAX_BYTES, type = { true }), budget) as? Fetched.Ok)?.resource

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

    /** [url] when [Request.hop] accepts it. */
    private fun allowed(url: URL?, request: Request): URL? = url?.takeIf(request.hop)

    /** The body of a 200 reply, checked against the type and size limits of [request]. */
    private fun body(conn: HttpsURLConnection, request: Request, budget: Budget?): Fetched {
        val type = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
        val mimeType = if (type.isNullOrEmpty()) "application/octet-stream" else type
        if (!request.type(mimeType) || conn.contentLengthLong > request.maxBytes) return Fetched.Missing
        val bytes = conn.inputStream.use { read(it, request.maxBytes, budget) } ?: return Fetched.Missing
        return Fetched.Ok(Resource(mimeType, bytes))
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

    /** The body, or null when it is larger than [maxBytes]. Throws IOException when the budget is spent. */
    private fun read(input: InputStream, maxBytes: Int, budget: Budget?): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > maxBytes) return null
            if (budget != null && !budget.spend(n)) throw IOException("Budget spent")
            out.write(buffer, 0, n)
        }
    }
}
