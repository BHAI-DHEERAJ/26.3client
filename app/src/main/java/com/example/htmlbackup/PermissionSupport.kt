package com.example.htmlbackup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** How much of the photo library Android currently permits this app to read. */
enum class PhotoAccess {
    NONE,
    SELECTED_ONLY,
    FULL;

    val isGranted: Boolean
        get() = this != NONE
}

/** The current authorization state for both backup categories. */
data class GrantedAccess(
    val photos: PhotoAccess,
    val contacts: Boolean
) {
    val hasPhotos: Boolean
        get() = photos.isGranted

    val hasAny: Boolean
        get() = hasPhotos || contacts
}

/**
 * Android-version-specific permission handling.
 *
 * - Android 8-12: READ_EXTERNAL_STORAGE for scoped MediaStore reads.
 * - Android 13: READ_MEDIA_IMAGES.
 * - Android 14+: READ_MEDIA_IMAGES plus READ_MEDIA_VISUAL_USER_SELECTED, which
 *   allows either full-library access or selected-photos access.
 * - Contacts: READ_CONTACTS on every supported version.
 *
 * No broad filesystem permission is requested and the app never uses
 * MANAGE_EXTERNAL_STORAGE or WRITE_EXTERNAL_STORAGE.
 */
object PermissionSupport {

    fun currentAccess(context: Context): GrantedAccess = GrantedAccess(
        photos = photoAccess(context),
        contacts = isGranted(context, Manifest.permission.READ_CONTACTS)
    )

    fun photoAccess(context: Context): PhotoAccess = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> when {
            isGranted(context, Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
            isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.SELECTED_ONLY
            else -> PhotoAccess.NONE
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            if (isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)) {
                PhotoAccess.FULL
            } else {
                PhotoAccess.NONE
            }
        }

        else -> {
            if (isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                PhotoAccess.FULL
            } else {
                PhotoAccess.NONE
            }
        }
    }

    /**
     * Permissions that still need to be requested. Once the user has partial
     * photo access, the media permissions are deliberately not requested
     * again: the app uses exactly the photos Android has made accessible.
     */
    fun missingPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()

        if (!photoAccess(context).isGranted) {
            missing += photoPermissionsForThisAndroidVersion()
        }
        if (!isGranted(context, Manifest.permission.READ_CONTACTS)) {
            missing += Manifest.permission.READ_CONTACTS
        }

        return missing
    }

    private fun photoPermissionsForThisAndroidVersion(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES
        )

        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
