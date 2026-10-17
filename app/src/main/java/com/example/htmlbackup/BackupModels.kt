package com.example.htmlbackup

import java.time.Instant

/** Stages reported while the app enumerates what the user authorized. */
enum class PreparationStage {
    STARTING,
    QUERYING_PHOTOS,
    READING_CONTACTS,
    FINALIZING
}

/** UI-independent progress report from [BackupPreparer]. */
data class PreparationProgress(
    val stage: PreparationStage,
    val percent: Int? = null,
    val current: Int = 0,
    val total: Int = 0
)

/** The categories the user authorized for this backup. */
data class BackupRequest(
    val includePhotos: Boolean,
    val includeContacts: Boolean,
    val photoAccessPartial: Boolean
) {
    init {
        require(includePhotos || includeContacts) {
            "At least one backup category must be selected."
        }
    }
}

/**
 * Everything the user authorized, discovered on-device before any upload.
 * Nothing here is archived: photos stay in MediaStore and are streamed one by
 * one at send time.
 */
data class BackupPlan(
    val request: BackupRequest,
    val photos: List<PhotoItem>,
    val contacts: ContactExport?,
    val deviceProfile: DeviceProfile,
    val createdAt: Instant
) {
    val photoBytes: Long
        get() = photos.sumOf { it.sizeBytes }

    val contactsIncluded: Boolean
        get() = contacts != null

    val contactCount: Int
        get() = contacts?.contacts?.size ?: 0

    val hasAnyData: Boolean
        get() = photos.isNotEmpty() || contactCount > 0

    /** Files to upload after the device-info message. */
    val fileCount: Int
        get() = photos.size + if (contactsIncluded) 1 else 0

    /** Total Discord requests: one info message plus one request per file. */
    val itemsToUpload: Int
        get() = 1 + fileCount
}

enum class UploadPhase {
    DEVICE_INFO,
    CONTACTS,
    PHOTOS
}

enum class UploadNote {
    SENDING,
    WAITING_CONFIRMATION,
    COMPRESSING,
    RETRYING,
    RATE_LIMITED
}

/** Progress for one item in the sequential upload queue. */
data class UploadProgress(
    val phase: UploadPhase,
    val itemIndex: Int,
    val itemCount: Int,
    val itemPercent: Int,
    val overallPercent: Int,
    val note: UploadNote = UploadNote.SENDING,
    val retryAfterSeconds: Long? = null
)

/** What actually reached Discord. Used to report real outcomes, not estimates. */
data class UploadReport(
    val infoSent: Boolean,
    val contactsIncluded: Boolean,
    val contactsSent: Boolean,
    val photosAttempted: Int,
    val photosSent: Int,
    val photosCompressedSent: Int,
    val photosSkipped: Int,
    val aborted: Boolean,
    val abortReason: UploadFailureKind?
) {
    val anythingDelivered: Boolean
        get() = infoSent || contactsSent || photosSent > 0
}

/**
 * A failure whose message is intentionally safe to show to the user.
 * Only construct this with fixed, non-sensitive text.
 */
class BackupException(message: String) : Exception(message)
