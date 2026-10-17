package com.example.htmlbackup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One image exposed by MediaStore that this app is allowed to read. */
data class PhotoItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

/** Outcome of adding the accessible photos to the open ZIP stream. */
data class PhotoWriteResult(
    val writtenCount: Int,
    val writtenBytes: Long,
    val skippedCount: Int,
    val sampleEntryNames: List<String>
)

/** Pure naming rules, kept separate so they can be unit tested off-device. */
internal object PhotoFormats {
    val supportedMimeTypes: List<String> = listOf(
        "image/jpeg",
        "image/png",
        "image/webp"
    )

    fun extensionFor(mimeType: String): String = when (mimeType.lowercase(Locale.ROOT)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "bin"
    }

    /**
     * Builds a collision-free entry inside photos/. The original display name
     * is sanitized, and the extension always follows the validated MIME type.
     */
    fun zipEntryName(index: Int, displayName: String?, mimeType: String): String {
        val baseName = sanitizeBaseName(displayName).ifBlank { "photo" }
        return String.format(
            Locale.ROOT,
            "photos/%05d_%s.%s",
            index,
            baseName,
            extensionFor(mimeType)
        )
    }

    private fun sanitizeBaseName(displayName: String?): String {
        if (displayName.isNullOrBlank()) {
            return ""
        }

        val leaf = displayName.substringAfterLast('/').substringAfterLast('\\')
        val withoutExtension = leaf.substringBeforeLast('.', leaf)
        val sanitized = buildString {
            for (character in withoutExtension) {
                append(
                    when {
                        character.isLetterOrDigit() -> character
                        character == '-' || character == '_' -> character
                        else -> '_'
                    }
                )
            }
        }

        return sanitized.replace(Regex("_+"), "_").trim('_', '-').take(60)
    }
}

/**
 * Discovers accessible images through MediaStore and streams them into a ZIP.
 *
 * The implementation intentionally does not enumerate the filesystem and does
 * not require per-photo selection: Android exposes exactly the images the user
 * authorized (all images, or only selected photos on Android 14+).
 */
class PhotoBackupManager(private val context: Context) {

    fun queryAccessiblePhotos(): List<PhotoItem> {
        if (!PermissionSupport.photoAccess(context).isGranted) {
            throw BackupException("Photo access is not granted.")
        }

        val mimeTypes = PhotoFormats.supportedMimeTypes
        val placeholders = mimeTypes.joinToString(separator = ", ") { "?" }
        val conditions = mutableListOf(
            "${MediaStore.Images.Media.MIME_TYPE} IN ($placeholders)"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            conditions += "${MediaStore.Images.Media.IS_PENDING} = 0"
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE
        )
        val sortOrder =
            "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC"
        val items = mutableListOf<PhotoItem>()

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            conditions.joinToString(" AND "),
            mimeTypes.toTypedArray(),
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(mimeColumn)?.lowercase(Locale.ROOT) ?: continue
                if (mimeType !in PhotoFormats.supportedMimeTypes) {
                    continue
                }

                val id = cursor.getLong(idColumn)
                val displayName = if (nameColumn >= 0) {
                    cursor.getString(nameColumn).orEmpty()
                } else {
                    ""
                }
                val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    cursor.getLong(sizeColumn)
                } else {
                    0L
                }

                items += PhotoItem(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    ),
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = size.coerceAtLeast(0L)
                )
            }
        } ?: throw BackupException(
            "The photo library could not be queried. Check photo access and try again."
        )

        return items
    }

    /**
     * Streams photo bytes directly from MediaStore to the ZIP stream. Nothing
     * is decoded into a bitmap and no full-size byte array is held in memory.
     */
    suspend fun writePhotosToZip(
        zipOut: ZipOutputStream,
        photos: List<PhotoItem>,
        onProgress: (completed: Int, total: Int) -> Unit
    ): PhotoWriteResult {
        var writtenCount = 0
        var writtenBytes = 0L
        var skippedCount = 0
        val sampleEntries = ArrayList<String>(MAX_SAMPLE_ENTRIES)
        val buffer = ByteArray(COPY_BUFFER_SIZE)

        onProgress(0, photos.size)

        photos.forEachIndexed { index, photo ->
            currentCoroutineContext().ensureActive()

            val entryName = PhotoFormats.zipEntryName(index + 1, photo.displayName, photo.mimeType)
            val input = try {
                context.contentResolver.openInputStream(photo.uri)
            } catch (_: SecurityException) {
                null
            } catch (_: IOException) {
                null
            }

            if (input == null) {
                skippedCount++
                onProgress(index + 1, photos.size)
                return@forEachIndexed
            }

            input.use { stream ->
                zipOut.putNextEntry(ZipEntry(entryName))
                var entryBytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read == -1) {
                        break
                    }
                    zipOut.write(buffer, 0, read)
                    entryBytes += read
                }
                zipOut.closeEntry()
                writtenBytes += entryBytes
                writtenCount++
                if (sampleEntries.size < MAX_SAMPLE_ENTRIES) {
                    sampleEntries += entryName
                }
            }

            onProgress(index + 1, photos.size)
        }

        return PhotoWriteResult(
            writtenCount = writtenCount,
            writtenBytes = writtenBytes,
            skippedCount = skippedCount,
            sampleEntryNames = sampleEntries
        )
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_SAMPLE_ENTRIES = 3
    }
}
