package com.example.htmlbackup

import java.io.File
import java.time.Instant

/** Stages reported while a backup ZIP is being assembled. */
enum class PreparationStage {
    STARTING,
    QUERYING_PHOTOS,
    ADDING_PHOTOS,
    READING_CONTACTS,
    ADDING_CONTACTS,
    FINALIZING
}

/** UI-independent progress report from [ZipBackupManager]. */
data class PreparationProgress(
    val stage: PreparationStage,
    val percent: Int? = null,
    val current: Int = 0,
    val total: Int = 0
)

/** The categories the user authorized for this particular archive. */
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

/** Facts about a completed ZIP that are safe to show in the confirmation dialog. */
data class ArchiveSummary(
    val archiveName: String,
    val archiveBytes: Long,
    val photosIncluded: Boolean,
    val contactsIncluded: Boolean,
    val photoCount: Int,
    val contactCount: Int,
    val photoBytes: Long,
    val contactsBytes: Long,
    val skippedPhotos: Int,
    val photoAccessPartial: Boolean,
    val sampleEntries: List<String>,
    val createdAt: Instant
) {
    val hasAnyData: Boolean
        get() = photoCount > 0 || contactCount > 0
}

/** A finished archive plus the summary used by the review screen. */
data class PreparedArchive(
    val file: File,
    val summary: ArchiveSummary
)

/**
 * A failure whose message is intentionally safe to show to the user.
 * Only construct this with fixed, non-sensitive text.
 */
class BackupException(message: String) : Exception(message)
