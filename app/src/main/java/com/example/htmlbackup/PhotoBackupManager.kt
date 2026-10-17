package com.example.htmlbackup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.Locale

/** One image exposed by MediaStore that this app is allowed to read. */
data class PhotoItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

/** Naming rules for uploads. Kept pure so they can be unit tested off-device. */
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

    /** Attachment name, for example `00042_holiday_photo.jpg`. */
    fun uploadFileName(index: Int, displayName: String?, mimeType: String): String =
        String.format(
            Locale.ROOT,
            "%s.%s",
            baseName(index, displayName),
            extensionFor(mimeType)
        )

    /** Attachment name for a re-encoded photo, always JPEG. */
    fun compressedFileName(
        index: Int,
        displayName: String?,
        quarterResolution: Boolean = false
    ): String {
        val suffix = if (quarterResolution) {
            "-compressed-4x.jpg"
        } else {
            "-compressed.jpg"
        }
        return String.format(Locale.ROOT, "%s%s", baseName(index, displayName), suffix)
    }

    private fun baseName(index: Int, displayName: String?): String {
        val sanitized = sanitizeBaseName(displayName).ifBlank { "photo" }
        return String.format(Locale.ROOT, "%05d_%s", index, sanitized)
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
 * Discovers accessible images through MediaStore.
 *
 * No filesystem walking and no per-photo selection: Android exposes exactly the
 * images the user authorized (full library or selected photos on Android 14+).
 * Images stay in place and are streamed directly to the uploader.
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
}
