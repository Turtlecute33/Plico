package app.plico.sync

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Rules for outgoing images from every source (DESIGN.md §10, privacy PS17, apis AF14): camera formats become
 * JPEG of at most 2560 px, PNG stays PNG and static WebP becomes lossless WebP, which drops their metadata.
 * GIF and animations stay as they are. Names with a date stamp become `image-N.<ext>`. Pure.
 */
internal object ImageRules {
    const val MAX_SIDE = 2560
    const val JPEG_QUALITY = 90

    /** Larger images are scaled down before re-encoding, so that one decoded bitmap stays near 100 MB. */
    const val MAX_PIXELS = 24_000_000L

    enum class Output(val mime: String, val extension: String) {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP_LOSSLESS("image/webp", "webp"),
    }

    private val heicBrands = setOf("heic", "heix", "heim", "heis", "hevc", "hevx")
    private val heifBrands = setOf("mif1", "msf1")
    private val dateStamp = Regex("""(?<!\d)(?:19|20)\d{2}[-_.]?(?:0[1-9]|1[0-2])[-_.]?(?:0[1-9]|[12]\d|3[01])(?!\d)""")
    private val unixTime = Regex("""(?<!\d)1\d{9}(?:\d{3})?(?!\d)""")

    /** The image type of the first bytes of a file, or null. */
    fun sniff(head: ByteArray): String? = when {
        head.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        head.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> "image/png"
        head.ascii(0, "GIF8") -> "image/gif"
        head.ascii(0, "RIFF") && head.ascii(8, "WEBP") -> "image/webp"
        head.ascii(4, "ftyp") -> when (head.asciiAt(8, 4)) {
            in heicBrands -> "image/heic"
            in heifBrands -> "image/heif"
            else -> null
        }
        else -> null
    }

    /** How to re-encode a file of the declared [mime] that starts with [head]; null = send it unchanged. */
    fun output(mime: String?, head: ByteArray): Output? =
        when (sniff(head) ?: mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "image/jpeg", "image/heic", "image/heif" -> Output.JPEG
            "image/png" -> if (isAnimatedPng(head)) null else Output.PNG
            "image/webp" -> if (isAnimatedWebp(head)) null else Output.WEBP_LOSSLESS
            else -> null
        }

    /** An extended WebP (`VP8X`) with the animation flag. */
    fun isAnimatedWebp(head: ByteArray): Boolean =
        head.size > 20 && head.ascii(12, "VP8X") && (head[20].toInt() and 0x02) != 0

    /** An APNG: an `acTL` chunk before the first `IDAT`. */
    fun isAnimatedPng(head: ByteArray): Boolean {
        val text = String(head, Charsets.ISO_8859_1)
        val actl = text.indexOf("acTL")
        return actl >= 0 && (text.indexOf("IDAT").let { it < 0 || actl < it })
    }

    /** The decoded size: a JPEG fits in [MAX_SIDE], every output in [MAX_PIXELS]; the aspect ratio is kept. */
    fun targetSize(width: Int, height: Int, output: Output): Pair<Int, Int> {
        var scale = 1.0
        if (output == Output.JPEG) scale = minOf(scale, MAX_SIDE.toDouble() / max(width, height))
        val pixels = width.toLong() * height
        if (pixels * scale * scale > MAX_PIXELS) scale = minOf(scale, sqrt(MAX_PIXELS.toDouble() / pixels))
        if (scale >= 1.0) return width to height
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** True when [name] holds a date or a Unix time, as camera, screenshot and messenger files do. */
    fun hasDateStamp(name: String): Boolean = dateStamp.containsMatchIn(name) || unixTime.containsMatchIn(name)

    /**
     * The attachment name of an image: a name with a date stamp becomes `image-N.<ext>` with the lowest N
     * that is not [taken]; a re-encoded file gets the extension of its new type.
     */
    fun fileName(name: String, mime: String?, output: Output?, taken: Collection<String>): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1) else ""
        val lower = ext.lowercase(Locale.ROOT)
        val newExt = when {
            output == null -> ext.ifEmpty { mime?.substringAfter("image/", "")?.takeIf { it.all(Char::isLetterOrDigit) }.orEmpty() }
            output == Output.JPEG && (lower == "jpg" || lower == "jpeg") -> ext
            else -> output.extension
        }
        val suffix = if (newExt.isEmpty()) "" else ".$newExt"
        if (hasDateStamp(name)) {
            val used = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
            return generateSequence(1) { it + 1 }.map { "image-$it$suffix" }.first { it.lowercase(Locale.ROOT) !in used }
        }
        return base + suffix
    }

    private fun ByteArray.startsWith(vararg bytes: Int) =
        size >= bytes.size && bytes.indices.all { (this[it].toInt() and 0xFF) == bytes[it] }

    private fun ByteArray.ascii(offset: Int, text: String) =
        size >= offset + text.length && text.indices.all { this[offset + it].toInt() == text[it].code }

    private fun ByteArray.asciiAt(offset: Int, length: Int): String =
        if (size < offset + length) "" else String(this, offset, length, Charsets.ISO_8859_1)
}
