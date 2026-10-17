package com.example.htmlbackup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Resolution presets for the compression fallback chain.
 *
 * [NORMAL] keeps a useful resolution (long edge up to 2048 px).
 * [QUARTER] is the last resort: the long edge is reduced to one quarter of the
 * normal target (512 px), which shrinks the file roughly 16x in pixel count.
 */
enum class CompressionPreset(val maxDimension: Int) {
    NORMAL(2048),
    QUARTER(2048 / 4)
}

/** Pure geometric helpers, unit tested off-device. */
internal object ImageSizing {
    /**
     * Smallest power-of-two sample factor that keeps the decoded image at or
     * below [maxDimension]. Sampling below the target avoids decoding a
     * full-resolution bitmap into memory only to throw it away.
     */
    fun inSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) {
            return 1
        }
        var sample = 1
        while (max(width, height) / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    /** Scales [width] x [height] to fit inside [maxDimension] on the long edge. */
    fun fitInside(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxDimension <= 0) {
            return width to height
        }
        val longest = max(width, height)
        if (longest <= maxDimension) {
            return width to height
        }
        val scale = maxDimension.toDouble() / longest
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }
}

/**
 * Re-encodes a photo as a smaller JPEG when the original cannot be delivered.
 *
 * This is a fallback, not the default: originals are uploaded byte-for-byte
 * when they fit and succeed. Compressed copies live only in the app cache and
 * are deleted after each attempt.
 */
class MediaCompressor(private val context: Context) {

    private val directory: File
        get() = File(context.cacheDir, CACHE_DIRECTORY)

    /**
     * Returns a temporary JPEG at the requested resolution preset, or null when
     * the image cannot be decoded or encoded.
     */
    suspend fun compress(
        photo: PhotoItem,
        index: Int,
        preset: CompressionPreset = CompressionPreset.NORMAL
    ): File? = withContext(Dispatchers.IO) {
        val bounds = readBounds(photo) ?: return@withContext null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return@withContext null
        }

        val sample = ImageSizing.inSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            preset.maxDimension
        )
        val source = decode(photo, sample) ?: return@withContext null

        try {
            ensureActive()
            if (!directory.exists() && !directory.mkdirs()) {
                return@withContext null
            }

            val target = File(
                directory,
                PhotoFormats.compressedFileName(
                    index = index,
                    displayName = photo.displayName,
                    quarterResolution = preset == CompressionPreset.QUARTER
                )
            )

            for (step in stepsFor(preset)) {
                ensureActive()
                val (width, height) = ImageSizing.fitInside(
                    source.width,
                    source.height,
                    step.maxDimension
                )
                val scaled = if (width != source.width || height != source.height) {
                    Bitmap.createScaledBitmap(source, width, height, true)
                } else {
                    source
                }

                try {
                    FileOutputStream(target).use { output ->
                        if (!scaled.compress(Bitmap.CompressFormat.JPEG, step.quality, output)) {
                            return@withContext null
                        }
                    }
                } finally {
                    if (scaled !== source) {
                        scaled.recycle()
                    }
                }

                val limit = if (preset == CompressionPreset.QUARTER) {
                    QUARTER_TARGET_BYTES
                } else {
                    NORMAL_TARGET_BYTES
                }
                if (target.length() in 1..limit) {
                    return@withContext target
                }
            }

            target.takeIf { it.length() > 0L && it.length() <= MAX_FALLBACK_BYTES }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            source.recycle()
        }
    }

    fun clearCache() {
        runCatching { directory.deleteRecursively() }
    }

    private fun stepsFor(preset: CompressionPreset): List<CompressionStep> =
        when (preset) {
            CompressionPreset.NORMAL -> listOf(
                CompressionStep(preset.maxDimension, 80),
                CompressionStep(1600, 70),
                CompressionStep(1280, 60)
            )

            CompressionPreset.QUARTER -> listOf(
                CompressionStep(preset.maxDimension, 60),
                CompressionStep(preset.maxDimension, 45),
                CompressionStep(384, 40)
            )
        }

    private fun readBounds(photo: PhotoItem): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            context.contentResolver.openInputStream(photo.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
                options
            }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(photo: PhotoItem, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            context.contentResolver.openInputStream(photo.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: SecurityException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private data class CompressionStep(val maxDimension: Int, val quality: Int)

    private companion object {
        const val CACHE_DIRECTORY = "compressed"

        /** Aim comfortably below Discord's default 20 MiB per-file limit. */
        const val NORMAL_TARGET_BYTES = 8L * 1024L * 1024L
        const val QUARTER_TARGET_BYTES = 4L * 1024L * 1024L
        const val MAX_FALLBACK_BYTES = 20L * 1024L * 1024L
    }
}
