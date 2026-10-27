package app.plico.html

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.plico.PlicoApp
import app.plico.app
import app.plico.data.Files
import app.plico.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Sender logos, only when the user turned them on (DESIGN.md §12). Only the domain of a sender is ever sent, to
 * the source that the user selected. Logos are cached in memory and on the device (re-encoded PNG, never the
 * bytes of the server); [LogoCache] has the rules.
 */
class SenderLogos(app: PlicoApp) {
    private val cache = LogoCache(
        files = app.files,
        codec = LogoCodec,
        enabled = { app.prefs.senderLogos },
        psl = { Psl.get(app) },
        // Only signs that the user made: a contact, a trusted sender, or an earlier message that the user read.
        known = { email, before, exceptId ->
            app.contacts.isKnown(email) || app.trustedSenders.isTrusted(email) ||
                app.messages.hasEarlierSeenFrom(email, before, exceptId)
        },
        fetch = RemoteFetcher::fetch,
        io = Dispatchers.IO,
    )

    /** The logo of the domain of [email] from memory, without I/O; null when it is not in memory. */
    fun cached(email: String): ImageBitmap? = cache.cached(email)

    /**
     * The logo of the domain of [email] from the cache or from [source]. [before] and [exceptId] find an earlier
     * message from the same address that the user read (the first-contact rule of the website source). Null when
     * there is none.
     */
    suspend fun load(email: String, source: Prefs.LogoSource, before: Long, exceptId: Long): ImageBitmap? =
        cache.load(email, source, before, exceptId)

    /** Deletes every cached logo (memory and disk). Call it off the main thread. */
    fun clear() = cache.clear()

    /**
     * At app start: deletes expired logo files, or all of them when logos are off ([enabled] false) or there is
     * no account. Call it off the main thread.
     */
    fun prune(enabled: Boolean, anyAccount: Boolean) = cache.prune(enabled, anyAccount)
}

/**
 * The logo for a message row: the cached logo at once, then the loaded one. Null at once when logos are off
 * ([source] null), when the row may not show a logo ([allowed] false) or without a sender address.
 */
@Composable
fun rememberSenderLogo(
    email: String?,
    source: Prefs.LogoSource?,
    allowed: Boolean,
    before: Long,
    exceptId: Long,
): ImageBitmap? {
    val logos = LocalContext.current.app.logos
    val on = source != null && allowed && !email.isNullOrBlank()
    // Keyed, so that another sender or another source never keeps the logo of the one before.
    val state = remember(email, source, on) { mutableStateOf(if (on) logos.cached(email.orEmpty()) else null) }
    LaunchedEffect(email, source, on, before, exceptId) {
        if (on) logos.load(email, source, before, exceptId)?.let { state.value = it }
    }
    return if (on) state.value else null
}

/**
 * The logo cache behind [SenderLogos], without Android types so that JVM tests can drive it.
 *
 * - Memory: the images by the lower-case host of the address ([cached] needs no PSL and no I/O), at most
 *   [memoryBytes]; hosts without a logo are remembered too.
 * - Disk: `logos/<sha256(domain)>.png` (re-encoded by [codec]) and `<sha256(domain)>.none` (no logo), fresh for
 *   [LogoRules.LOGO_TTL_MS] and [LogoRules.NONE_TTL_MS]. Only a [RemoteFetcher.Fetched.Missing] answer writes
 *   `.none`; a [RemoteFetcher.Fetched.Failed] fetch (offline, captive portal) blocks the domain in memory for
 *   [LogoRules.FAILED_TTL_MS] only.
 * - Fetch: never when [enabled] is false. With [Prefs.LogoSource.WEBSITE], only for a sender that is [known] (a
 *   contact, a trusted sender, or an earlier message that the user read; never only an earlier received message,
 *   which a sender can send): else the server of the sender would see when a first message is read. One
 *   fetch per domain at a time, at most [maxFetches] domains at a time, at most [LogoRules.MAX_BYTES] per URL (the
 *   website source tries two URLs).
 * - Threads: everything runs in the coroutine of the caller (the row) on [io]. Memory and disk answers never wait
 *   for a fetch. A row that leaves the screen while its fetch waits for its turn makes no request; a fetch that
 *   has started ends and fills the cache.
 * - [clear] starts a new generation: a fetch that ends under an older generation writes nothing.
 */
internal class LogoCache<T : Any>(
    private val files: Files,
    private val codec: Codec<T>,
    private val enabled: () -> Boolean,
    private val psl: () -> Psl,
    private val known: (email: String, before: Long, exceptId: Long) -> Boolean,
    private val fetch: (RemoteFetcher.Request, RemoteFetcher.Budget?) -> RemoteFetcher.Fetched,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
    memoryBytes: Long = 8L * 1024 * 1024,
    maxFetches: Int = 3,
) {
    /** Image work: [decode] checks and scales fetched bytes, [encode] makes the PNG file, [read] reads it back. */
    interface Codec<T> {
        fun decode(bytes: ByteArray): T?
        fun encode(image: T): ByteArray?
        fun read(bytes: ByteArray): T?
        fun byteCount(image: T): Int
    }

    private sealed interface Outcome<out T> {
        class Logo<T>(val image: T) : Outcome<T>
        data object None : Outcome<Nothing>
        data object Failed : Outcome<Nothing>
    }

    private val lock = Any()

    /** The domains that fetch now; a fetch blocks a thread for up to 20 s per URL. */
    private val fetches = Semaphore(maxFetches)

    @Volatile
    private var generation = 0
    private val memory = ByteLru<String, T>(memoryBytes, codec::byteCount)

    /** Host → time until which it has no logo. */
    private val misses = ConcurrentHashMap<String, Long>()

    /** Domain → time of its last failed fetch. */
    private val failures = ConcurrentHashMap<String, Long>()

    /** Lower-case addresses that are not a first contact (known stays known). */
    private val knownAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Domain → the lock of its fetch: one fetch per domain at a time. */
    private val domainLocks = HashMap<String, Mutex>()

    fun cached(email: String): T? = hostOf(email)?.let(memory::get)

    suspend fun load(email: String, source: Prefs.LogoSource, before: Long, exceptId: Long): T? {
        val host = hostOf(email) ?: return null
        memory.get(host)?.let { return it }
        val missUntil = misses[host]
        if (missUntil != null) {
            if (missUntil > now()) return null
            misses.remove(host, missUntil)
        }
        val gen = generation
        return try {
            withContext(io) { find(email, host, source, before, exceptId, gen) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        synchronized(lock) {
            generation++
            memory.clear()
            misses.clear()
            failures.clear()
            knownAddresses.clear()
            domainLocks.clear()
        }
        files.logoDir().deleteRecursively()
    }

    fun prune(enabled: Boolean, anyAccount: Boolean) {
        if (!enabled || !anyAccount) {
            clear()
            return
        }
        val time = now()
        files.logoDir().listFiles()?.forEach { file ->
            val entry = LogoRules.entryOf(file.name)
            if (entry == null || !LogoRules.fresh(entry, file.lastModified(), time)) file.deleteRecursively()
        }
    }

    private suspend fun find(
        email: String,
        host: String,
        source: Prefs.LogoSource,
        before: Long,
        exceptId: Long,
        gen: Int,
    ): T? {
        val domain = LogoRules.domainOf(email, psl())
        if (domain == null) {
            miss(host, Long.MAX_VALUE, gen)
            return null
        }
        val hash = sha256(domain)
        readDisk(host, hash, gen)?.let { return it.image }
        if (failed(domain)) return null
        if (!enabled()) return null
        if (source == Prefs.LogoSource.WEBSITE && !isKnown(email, before, exceptId)) return null
        val outcome = (domainLock(domain, gen) ?: return null).withLock {
            // A fetch of the same domain can have ended while this one waited.
            readDisk(host, hash, gen)?.let { return it.image }
            if (failed(domain)) return null
            // Waits for a free fetch; a row that leaves the screen now (cancel) makes no request.
            fetches.withPermit { fetchAndStore(domain, hash, source, gen) }
        }
        // After clear() (logos off, another source) an older answer is not shown.
        if (gen != generation) return null
        return when (outcome) {
            is Outcome.Logo -> outcome.image.also { remember(host, it, gen) }
            Outcome.None -> {
                miss(host, now() + LogoRules.NONE_TTL_MS, gen)
                null
            }
            Outcome.Failed -> null
        }
    }

    /** An answer from the disk: [image] null = "no logo". */
    private class DiskAnswer<T>(val image: T?)

    /** The fresh disk entry of [hash], or null when there is none (an expired or unreadable file is deleted). */
    private fun readDisk(host: String, hash: String, gen: Int): DiskAnswer<T>? {
        val logo = files.logo(hash, LogoRules.Entry.LOGO.extension)
        if (logo.exists()) {
            val image = if (LogoRules.fresh(LogoRules.Entry.LOGO, logo.lastModified(), now())) {
                runCatching { codec.read(logo.readBytes()) }.getOrNull()
            } else {
                null
            }
            if (image != null) {
                remember(host, image, gen)
                return DiskAnswer(image)
            }
            logo.delete()
        }
        val none = files.logo(hash, LogoRules.Entry.NONE.extension)
        if (none.exists()) {
            val written = none.lastModified()
            if (LogoRules.fresh(LogoRules.Entry.NONE, written, now())) {
                miss(host, written + LogoRules.NONE_TTL_MS, gen)
                return DiskAnswer(null)
            }
            none.delete()
        }
        return null
    }

    private fun isKnown(email: String, before: Long, exceptId: Long): Boolean {
        val key = email.lowercase(Locale.ROOT)
        if (key in knownAddresses) return true
        return known(email, before, exceptId).also { if (it) knownAddresses += key }
    }

    /** A failed fetch of [domain] blocks it for a short time (offline, captive portal). */
    private fun failed(domain: String): Boolean = failures[domain]?.let { LogoRules.failureActive(it, now()) } == true

    /**
     * The lock of the fetches of [domain] in this generation; null when [gen] is not the current one. [clear] drops
     * the locks under the same lock, so that a newer caller never waits for a fetch of an older generation (that
     * fetch writes nothing, so the newer caller would get no logo).
     */
    private fun domainLock(domain: String, gen: Int): Mutex? = synchronized(lock) {
        if (gen != generation) null else domainLocks.getOrPut(domain) { Mutex() }
    }

    /** The blocking fetch of [domain] from [source]; it runs on [io] in the caller's coroutine. */
    private fun fetchAndStore(domain: String, hash: String, source: Prefs.LogoSource, gen: Int): Outcome<T> {
        for (url in LogoRules.urls(source, domain)) {
            if (gen != generation || !enabled()) return Outcome.Failed
            // One budget per URL: a large first answer (also one that does not decode) must not make the next
            // URL fail, else the domain gets no "no logo" entry and is fetched again after each failure time.
            val budget = RemoteFetcher.Budget(LogoRules.MAX_BYTES.toLong(), parallel = 1)
            val request = RemoteFetcher.Request(
                url, "image/*", LogoRules.MAX_BYTES, type = { it.startsWith("image/") }, hop = LogoRules.hopFor(source),
            )
            when (val fetched = fetch(request, budget)) {
                is RemoteFetcher.Fetched.Ok -> {
                    // Bytes that do not decode count as "no logo at this URL".
                    val image = runCatching { codec.decode(fetched.resource.bytes) }.getOrNull() ?: continue
                    runCatching { codec.encode(image) }.getOrNull()?.let { store(hash, LogoRules.Entry.LOGO, it, gen) }
                    return Outcome.Logo(image)
                }
                RemoteFetcher.Fetched.Missing -> continue
                RemoteFetcher.Fetched.Failed -> {
                    synchronized(lock) { if (gen == generation) failures[domain] = now() }
                    return Outcome.Failed
                }
            }
        }
        store(hash, LogoRules.Entry.NONE, ByteArray(0), gen)
        return Outcome.None
    }

    /** Writes the [entry] file of [hash] through a temporary file and deletes the other kind; nothing after [clear]. */
    private fun store(hash: String, entry: LogoRules.Entry, bytes: ByteArray, gen: Int) {
        val target = files.logo(hash, entry.extension)
        val other = files.logo(hash, LogoRules.Entry.entries.first { it != entry }.extension)
        val temp = try {
            File.createTempFile("logo", ".tmp", target.parentFile).apply {
                writeBytes(bytes)
                // The age of an entry is its file time, on the same clock as the checks.
                setLastModified(now())
            }
        } catch (_: Exception) {
            return
        }
        synchronized(lock) {
            if (gen == generation && temp.renameTo(target)) {
                other.delete()
                return
            }
        }
        temp.delete()
    }

    private fun remember(host: String, image: T, gen: Int) = synchronized(lock) {
        if (gen == generation) {
            memory.put(host, image)
            misses.remove(host)
        }
    }

    private fun miss(host: String, until: Long, gen: Int) = synchronized(lock) {
        if (gen == generation) misses[host] = until
    }

    private companion object {
        /** The lower-case host of [email]: the memory key, so that [cached] needs no PSL. */
        fun hostOf(email: String): String? =
            email.substringAfterLast('@', "").trim().lowercase(Locale.ROOT).removeSuffix(".").ifEmpty { null }

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/** A least-recently-used map of at most [maxBytes] of values (by [sizeOf]); it always keeps the newest entry. */
internal class ByteLru<K : Any, V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Int) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(value)
        val entries = map.entries.iterator()
        while (bytes > maxBytes && map.size > 1 && entries.hasNext()) {
            val eldest = entries.next()
            if (eldest.key == key) continue
            bytes -= sizeOf(eldest.value)
            entries.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0
    }

    @get:Synchronized
    val size: Int get() = map.size
}

/**
 * The platform decoder for logos (DESIGN.md §12): the first bytes must be PNG, JPEG, GIF, WebP or ICO, and the
 * decoder must report the same format; both sides from 32 to 512 px; scaled to at most 128 px; software memory.
 * ICO gives its largest image. Any problem gives null.
 */
internal object LogoCodec : LogoCache.Codec<ImageBitmap> {
    private class Refused : RuntimeException()

    override fun decode(bytes: ByteArray): ImageBitmap? = LogoRules.sniff(bytes)?.let { decode(bytes, it) }

    /** Decodes [bytes] only when the decoder sees the format [kind] (from [LogoRules.sniff]). */
    fun decode(bytes: ByteArray, kind: LogoRules.ImageKind): ImageBitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            if (!LogoRules.sizeAllowed(width, height) || !kind.matches(info.mimeType)) throw Refused()
            val (targetWidth, targetHeight) = LogoRules.targetSize(width, height)
            decoder.setTargetSize(targetWidth, targetHeight)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.asImageBitmap()
    } catch (_: Exception) {
        null
    }

    override fun encode(image: ImageBitmap): ByteArray? = ByteArrayOutputStream().use { out ->
        if (image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
    }

    /** A cache file: only a PNG of at most [LogoRules.MAX_PX] per side (the cache writes at most 128 px). */
    override fun read(bytes: ByteArray): ImageBitmap? {
        if (LogoRules.sniff(bytes) != LogoRules.ImageKind.PNG) return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                if (info.size.width > LogoRules.MAX_PX || info.size.height > LogoRules.MAX_PX) throw Refused()
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    override fun byteCount(image: ImageBitmap): Int = image.asAndroidBitmap().allocationByteCount
}
