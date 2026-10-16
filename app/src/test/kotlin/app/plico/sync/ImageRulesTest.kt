package app.plico.sync

import app.plico.sync.ImageRules.Output
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageRulesTest {
    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { p ->
        when (p) {
            is String -> p.toByteArray(Charsets.ISO_8859_1).toList()
            is Int -> listOf(p.toByte())
            else -> error("type")
        }
    }.toByteArray()

    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE1, "Exif")
    private val png = bytes(0x89, "PNG\r\n", 0x1A, "\n", "....IHDR")
    private val apng = bytes(0x89, "PNG\r\n", 0x1A, "\n", "....IHDR.............acTL........IDAT")
    private val gif = bytes("GIF89a....")
    private val webp = bytes("RIFF....WEBPVP8 ........")
    private val webpExtended = bytes("RIFF....WEBPVP8X....", 0x10, "...")
    private val webpAnimated = bytes("RIFF....WEBPVP8X....", 0x12, "...")
    private val heic = bytes(0, 0, 0, 24, "ftypheic....")
    private val heif = bytes(0, 0, 0, 24, "ftypmif1....")

    @Test
    fun sniffsImageTypes() {
        assertEquals("image/jpeg", ImageRules.sniff(jpeg))
        assertEquals("image/png", ImageRules.sniff(png))
        assertEquals("image/gif", ImageRules.sniff(gif))
        assertEquals("image/webp", ImageRules.sniff(webp))
        assertEquals("image/heic", ImageRules.sniff(heic))
        assertEquals("image/heif", ImageRules.sniff(heif))
        assertNull(ImageRules.sniff(bytes("%PDF-1.7")))
        assertNull(ImageRules.sniff(ByteArray(0)))
    }

    @Test
    fun cameraFormatsBecomeJpegOthersKeepTheirFormat() {
        assertEquals(Output.JPEG, ImageRules.output("image/jpeg", jpeg))
        assertEquals(Output.JPEG, ImageRules.output(null, heic))
        assertEquals(Output.JPEG, ImageRules.output("application/octet-stream", heif))
        assertEquals(Output.PNG, ImageRules.output("image/png", png))
        assertEquals(Output.WEBP_LOSSLESS, ImageRules.output("image/webp", webp))
        assertEquals(Output.WEBP_LOSSLESS, ImageRules.output("image/webp", webpExtended))
        // The magic bytes win over the declared type.
        assertEquals(Output.PNG, ImageRules.output("image/jpeg", png))
        // Declared type when the bytes are not known.
        assertEquals(Output.JPEG, ImageRules.output("image/HEIC; x=y", bytes("????")))
    }

    @Test
    fun animationsAndGifStayUntouched() {
        assertNull(ImageRules.output("image/gif", gif))
        assertNull(ImageRules.output("image/webp", webpAnimated))
        assertNull(ImageRules.output("image/png", apng))
        assertNull(ImageRules.output("image/svg+xml", bytes("<svg")))
        assertTrue(ImageRules.isAnimatedWebp(webpAnimated))
        assertFalse(ImageRules.isAnimatedWebp(webpExtended))
        assertTrue(ImageRules.isAnimatedPng(apng))
        assertFalse(ImageRules.isAnimatedPng(png))
    }

    @Test
    fun jpegFitsTheLongestSide() {
        assertEquals(2560 to 1920, ImageRules.targetSize(4000, 3000, Output.JPEG))
        assertEquals(1440 to 2560, ImageRules.targetSize(3024, 5376, Output.JPEG))
        assertEquals(800 to 600, ImageRules.targetSize(800, 600, Output.JPEG))
        // PNG keeps its size (screenshots stay sharp) unless it is huge.
        assertEquals(4000 to 3000, ImageRules.targetSize(4000, 3000, Output.PNG))
        val (w, h) = ImageRules.targetSize(10_000, 10_000, Output.PNG)
        assertTrue(w.toLong() * h <= ImageRules.MAX_PIXELS * 1.001 && w == h && w > 4_000, "$w x $h")
    }

    @Test
    fun dateStampsAreFound() {
        for (name in listOf(
            "IMG_20261016_101523.jpg", "PXL_20261016_101523123.jpg", "20261016_101523.heic",
            "Screenshot_20261016-101523_Signal.png", "Screenshot 2026-10-16 at 10.15.23.png",
            "signal-2026-10-16-101523.jpg", "IMG-20261016-WA0001.jpg", "WhatsApp Image 2026-10-16 at 10.15.23.jpeg",
            "1792142099000.jpg", "photo_1792142099.png",
        )) {
            assertTrue(ImageRules.hasDateStamp(name), name)
        }
        for (name in listOf("holiday.jpg", "IMG_1234.jpg", "floorplan.png", "DSC01234.JPG", "scan_2026.png", "20261340.png")) {
            assertFalse(ImageRules.hasDateStamp(name), name)
        }
    }

    @Test
    fun namesWithStampsBecomeNumbered() {
        assertEquals("image-1.jpg", ImageRules.fileName("IMG_20261016_101523.jpg", "image/jpeg", Output.JPEG, emptyList()))
        assertEquals(
            "image-3.jpg",
            ImageRules.fileName("PXL_20261016_101523123.heic", "image/heic", Output.JPEG, listOf("IMAGE-1.jpg", "image-2.jpg")),
        )
        assertEquals("image-1.png", ImageRules.fileName("Screenshot_20261016-101523_Signal.png", "image/png", Output.PNG, listOf("image-1.jpg")))
        assertEquals("image-1.gif", ImageRules.fileName("signal-2026-10-16-101523.gif", "image/gif", null, emptyList()))
    }

    @Test
    fun reencodedFilesGetTheirNewExtension() {
        assertEquals("photo.jpg", ImageRules.fileName("photo.heic", "image/heic", Output.JPEG, emptyList()))
        assertEquals("photo.JPEG", ImageRules.fileName("photo.JPEG", "image/jpeg", Output.JPEG, emptyList()))
        assertEquals("plan.png", ImageRules.fileName("plan.png", "image/png", Output.PNG, emptyList()))
        assertEquals("cat.webp", ImageRules.fileName("cat.webp", "image/webp", Output.WEBP_LOSSLESS, emptyList()))
        assertEquals("anim.gif", ImageRules.fileName("anim.gif", "image/gif", null, emptyList()))
        assertEquals("picture.gif", ImageRules.fileName("picture", "image/gif", null, emptyList()))
        assertEquals("picture.jpg", ImageRules.fileName("picture", "image/jpeg", Output.JPEG, emptyList()))
    }
}
