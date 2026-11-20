package com.example.data.opencode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.domain.model.AttachedImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class ImageAttachmentProcessor(
    private val context: Context,
) {
    suspend fun processImageUri(uri: Uri): AttachedImageRef = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rawName = resolveDisplayName(uri) ?: "image_${System.currentTimeMillis()}.jpg"
        val cleanName = if (rawName.endsWith(".jpg", true) || rawName.endsWith(".jpeg", true)) {
            rawName
        } else {
            "${rawName.substringBeforeLast('.')}.jpg"
        }

        // Step 1: Decode bounds only to avoid OOM on high-res photos
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw IllegalStateException("Не удалось открыть выбранное изображение")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("Файл не является корректным изображением")
        }

        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION_PX)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: throw IllegalStateException("Не удалось декодировать изображение")

        val scaled = scaleDownIfNeeded(decoded, MAX_DIMENSION_PX)
        if (scaled !== decoded) {
            decoded.recycle()
        }

        // Step 2: Compress to JPEG under target payload cap
        var quality = 84
        var jpegBytes = compressJpeg(scaled, quality)
        while (jpegBytes.size > MAX_PAYLOAD_BYTES && quality > 52) {
            quality -= 10
            jpegBytes = compressJpeg(scaled, quality)
        }

        // Step 3: Build compact preview thumbnail for composer chip
        val thumb = scaleDownIfNeeded(scaled, PREVIEW_THUMB_PX)
        val previewBytes = compressJpeg(thumb, 72)
        if (thumb !== scaled) {
            thumb.recycle()
        }
        scaled.recycle()

        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        AttachedImageRef(
            id = UUID.randomUUID().toString(),
            filename = cleanName,
            mimeType = "image/jpeg",
            base64DataUrl = "data:image/jpeg;base64,$base64",
            byteSize = jpegBytes.size,
            previewBytes = previewBytes,
        )
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest.toFloat()
        val targetW = max(1, (bitmap.width * ratio).roundToInt())
        val targetH = max(1, (bitmap.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    companion object {
        private const val MAX_DIMENSION_PX = 1600
        private const val PREVIEW_THUMB_PX = 192
        private const val MAX_PAYLOAD_BYTES = 1_500_000 // ~1.5 MB

        internal fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
            var sample = 1
            var w = width
            var h = height
            while (w / 2 >= maxDim && h / 2 >= maxDim) {
                sample *= 2
                w /= 2
                h /= 2
            }
            return sample
        }
    }
}
