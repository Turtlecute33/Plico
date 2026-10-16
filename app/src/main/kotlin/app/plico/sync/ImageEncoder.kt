package app.plico.sync

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import java.io.IOException

/** Re-encodes outgoing images with the platform decoder, which applies the EXIF orientation (DESIGN.md §10). */
internal object ImageEncoder {
    /**
     * Writes [source] to [target] as [output], scaled per [ImageRules.targetSize]. The new file has no
     * metadata. False when the platform cannot decode or encode the image.
     */
    fun encode(source: File, target: File, output: ImageRules.Output): Boolean {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (width, height) = ImageRules.targetSize(info.size.width, info.size.height, output)
                if (width != info.size.width || height != info.size.height) decoder.setTargetSize(width, height)
            }
        } catch (_: Exception) {
            // DecodeException (an IOException) for broken data; IllegalArgumentException for unsupported sources.
            return false
        }
        return try {
            target.outputStream().buffered().use { out ->
                when (output) {
                    ImageRules.Output.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, ImageRules.JPEG_QUALITY, out)
                    ImageRules.Output.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    ImageRules.Output.WEBP_LOSSLESS -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                }
            }
        } catch (_: IOException) {
            false
        } finally {
            bitmap.recycle()
        }
    }
}
