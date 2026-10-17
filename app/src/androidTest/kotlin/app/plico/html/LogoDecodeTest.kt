package app.plico.html

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The platform logo decoder (DESIGN §12): formats, size limits, the format check and the cache file round trip. */
class LogoDecodeTest {
    private fun bitmap(width: Int, height: Int = width): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 30, 60)) }

    private fun encoded(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(format, 100, out)
            out.toByteArray()
        }

    private fun png(width: Int, height: Int = width) = encoded(bitmap(width, height), Bitmap.CompressFormat.PNG)

    /** An ICO file whose images are PNG data (Vista format), one per size. */
    private fun ico(vararg sizes: Int): ByteArray {
        val images = sizes.map { png(it) }
        val header = 6 + 16 * sizes.size
        val buffer = ByteBuffer.allocate(header + images.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0).putShort(1).putShort(sizes.size.toShort())
        var offset = header
        sizes.forEachIndexed { i, size ->
            buffer.put((size and 0xFF).toByte()).put((size and 0xFF).toByte()).put(0).put(0)
            buffer.putShort(1).putShort(32).putInt(images[i].size).putInt(offset)
            offset += images[i].size
        }
        images.forEach(buffer::put)
        return buffer.array()
    }

    @Test
    fun png64Decodes() {
        val image = assertNotNull(LogoCodec.decode(png(64)))
        assertEquals(64, image.width)
        assertEquals(64, image.height)
    }

    @Test
    fun largeImagesAreScaledTo128() {
        val image = assertNotNull(LogoCodec.decode(png(512, 256)))
        assertEquals(128, image.width)
        assertEquals(64, image.height)
    }

    @Test
    fun jpegAndWebpDecode() {
        assertNotNull(LogoCodec.decode(encoded(bitmap(64), Bitmap.CompressFormat.JPEG)))
        assertNotNull(LogoCodec.decode(encoded(bitmap(64), Bitmap.CompressFormat.WEBP_LOSSLESS)))
    }

    @Test
    fun icoWithSeveralSizesGivesTheLargest() {
        val bytes = ico(16, 48)
        assertEquals(LogoRules.ImageKind.ICO, LogoRules.sniff(bytes))
        val image = assertNotNull(LogoCodec.decode(bytes))
        assertEquals(48, image.width)
    }

    @Test
    fun tooSmallIsRefused() {
        assertNull(LogoCodec.decode(png(16)))
        assertNull(LogoCodec.decode(png(64, 16)))
        assertNull(LogoCodec.decode(ico(16)))
    }

    @Test
    fun tooLargeIsRefused() {
        assertNull(LogoCodec.decode(png(600)))
    }

    @Test
    fun aDecoderFormatOtherThanTheSniffedOneIsRefused() {
        val bytes = png(64)
        assertNotNull(LogoCodec.decode(bytes, LogoRules.ImageKind.PNG))
        assertNull(LogoCodec.decode(bytes, LogoRules.ImageKind.JPEG))
    }

    @Test
    fun svgAndHtmlAreRefused() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64"/></svg>"""
        assertNull(LogoCodec.decode(svg.toByteArray()))
        assertNull(LogoCodec.decode("<!DOCTYPE html><html><body>Not found</body></html>".toByteArray()))
        assertNull(LogoCodec.decode(ByteArray(0)))
    }

    @Test
    fun cacheFileRoundTrip() {
        val image = assertNotNull(LogoCodec.decode(png(200)))
        val file = assertNotNull(LogoCodec.encode(image))
        assertEquals(LogoRules.ImageKind.PNG, LogoRules.sniff(file))
        val read = assertNotNull(LogoCodec.read(file))
        assertEquals(image.width, read.width)
        assertEquals(image.height, read.height)
        // A cache file must be a PNG.
        assertNull(LogoCodec.read(encoded(bitmap(64), Bitmap.CompressFormat.JPEG)))
    }
}
