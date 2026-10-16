package app.plico.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageEncoderTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "image-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun image(name: String, width: Int, height: Int, format: Bitmap.CompressFormat): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return File(dir, name).also { f -> f.outputStream().use { bitmap.compress(format, 95, it) } }
    }

    private fun size(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun jpegLosesItsMetadataAndFitsTheLimit() {
        val source = image("camera.jpg", 4000, 3000, Bitmap.CompressFormat.JPEG)
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "45/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_MAKE, "PhoneMaker")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val target = File(dir, "out.jpg")
        assertTrue(ImageEncoder.encode(source, target, ImageRules.Output.JPEG))
        // The decoder applied the rotation, then the long side was scaled to 2560.
        assertEquals(1920 to 2560, size(target))
        val exif = ExifInterface(target.path)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("image/jpeg", ImageRules.sniff(target.readBytes().copyOf(16)))
    }

    @Test
    fun pngStaysPngAtFullSize() {
        val source = image("shot.png", 1080, 2400, Bitmap.CompressFormat.PNG)
        val target = File(dir, "out.png")
        assertTrue(ImageEncoder.encode(source, target, ImageRules.Output.PNG))
        assertEquals(1080 to 2400, size(target))
        assertEquals("image/png", ImageRules.sniff(target.readBytes().copyOf(16)))
    }

    @Test
    fun webpBecomesLossless() {
        val source = image("pic.webp", 300, 200, Bitmap.CompressFormat.WEBP_LOSSY)
        val target = File(dir, "out.webp")
        assertTrue(ImageEncoder.encode(source, target, ImageRules.Output.WEBP_LOSSLESS))
        val bytes = target.readBytes()
        val head = bytes.copyOf(64)
        assertEquals("image/webp", ImageRules.sniff(head))
        // The encoder adds an sRGB ICC profile; there is no EXIF or XMP chunk.
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("VP8L" in text)
        assertFalse("EXIF" in text || "XMP " in text)
        assertFalse(ImageRules.isAnimatedWebp(head))
    }

    @Test
    fun brokenImagesAreReported() {
        val source = File(dir, "broken.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)) }
        val target = File(dir, "out.jpg")
        assertFalse(ImageEncoder.encode(source, target, ImageRules.Output.JPEG))
    }
}
