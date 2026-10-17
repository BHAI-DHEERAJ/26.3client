package com.example.htmlbackup

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the backup ZIP entirely inside the app cache and destroys it when the
 * caller is finished or cancels.
 *
 * ZIP layout:
 *   manifest.json          counts and metadata (no contact details)
 *   photos/00001_name.jpg  one entry per accessible photo
 *   contacts.json          names, phone numbers and email addresses
 */
class ZipBackupManager(private val context: Context) {
    private val photoManager = PhotoBackupManager(context)
    private val contactExporter = ContactExporter(context)

    /**
     * Prepares the archive for the categories the user authorized. This reads
     * local data but performs no network activity.
     */
    suspend fun prepareBackup(
        request: BackupRequest,
        onProgress: (PreparationProgress) -> Unit
    ): PreparedArchive = withContext(Dispatchers.IO) {
        val access = PermissionSupport.currentAccess(context)
        if (request.includePhotos && !access.hasPhotos) {
            throw BackupException("Photo access is no longer granted.")
        }
        if (request.includeContacts && !access.hasContacts) {
            throw BackupException("Contacts access is no longer granted.")
        }

        deleteTemporaryArchivesInternal()
        val directory = backupDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            throw BackupException("Temporary storage could not be created.")
        }

        val createdAt = Instant.now()
        val archiveName = Formats.archiveName(createdAt)
        val archiveFile = File(directory, archiveName)
        var success = false

        try {
            onProgress(PreparationProgress(PreparationStage.STARTING, 0))
            var preparedData: PreparedData? = null

            FileOutputStream(archiveFile).use { fileOutput ->
                BufferedOutputStream(fileOutput, STREAM_BUFFER_SIZE).use { buffered ->
                    ZipOutputStream(buffered).use { zip ->
                        zip.setLevel(Deflater.BEST_SPEED)

                        val sampleEntries = ArrayList<String>()
                        var photoCount = 0
                        var photoBytes = 0L
                        var skippedPhotos = 0

                        if (request.includePhotos) {
                            currentCoroutineContext().ensureActive()
                            onProgress(PreparationProgress(PreparationStage.QUERYING_PHOTOS))
                            val photos = photoManager.queryAccessiblePhotos()

                            if (photos.isNotEmpty()) {
                                zip.putNextEntry(ZipEntry(PHOTOS_DIRECTORY_ENTRY))
                                zip.closeEntry()
                                sampleEntries += PHOTOS_DIRECTORY_ENTRY

                                val result = photoManager.writePhotosToZip(zip, photos) { done, total ->
                                    val percent = if (total <= 0) {
                                        70
                                    } else {
                                        10 + ((60L * done) / total).toInt()
                                    }
                                    onProgress(
                                        PreparationProgress(
                                            stage = PreparationStage.ADDING_PHOTOS,
                                            percent = percent.coerceIn(10, 70),
                                            current = done,
                                            total = total
                                        )
                                    )
                                }
                                photoCount = result.writtenCount
                                photoBytes = result.writtenBytes
                                skippedPhotos = result.skippedCount
                                sampleEntries += result.sampleEntryNames
                            }
                        }

                        currentCoroutineContext().ensureActive()
                        var contactCount = 0
                        var contactsBytes = 0L

                        if (request.includeContacts) {
                            onProgress(PreparationProgress(PreparationStage.READING_CONTACTS, 75))
                            val export = contactExporter.readContacts()
                            contactCount = export.contacts.size
                            onProgress(PreparationProgress(PreparationStage.ADDING_CONTACTS, 85))
                            contactsBytes = contactExporter.writeJsonToZip(zip, export)
                            sampleEntries += CONTACTS_ENTRY
                        }

                        currentCoroutineContext().ensureActive()
                        onProgress(PreparationProgress(PreparationStage.FINALIZING, 95))
                        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                        zip.write(
                            buildManifest(
                                request = request,
                                photoCount = photoCount,
                                contactCount = contactCount,
                                skippedPhotos = skippedPhotos,
                                createdAt = createdAt
                            )
                        )
                        zip.closeEntry()
                        sampleEntries += MANIFEST_ENTRY

                        preparedData = PreparedData(
                            photoCount = photoCount,
                            photoBytes = photoBytes,
                            contactCount = contactCount,
                            contactsBytes = contactsBytes,
                            skippedPhotos = skippedPhotos,
                            sampleEntries = sampleEntries
                        )
                    }
                }
            }

            val data = preparedData ?: throw BackupException(
                "The backup archive could not be finalized."
            )
            val summary = ArchiveSummary(
                archiveName = archiveFile.name,
                archiveBytes = archiveFile.length(),
                photosIncluded = request.includePhotos,
                contactsIncluded = request.includeContacts,
                photoCount = data.photoCount,
                contactCount = data.contactCount,
                photoBytes = data.photoBytes,
                contactsBytes = data.contactsBytes,
                skippedPhotos = data.skippedPhotos,
                photoAccessPartial = request.photoAccessPartial,
                sampleEntries = data.sampleEntries.take(MAX_SUMMARY_SAMPLES),
                createdAt = createdAt
            )

            success = true
            onProgress(PreparationProgress(PreparationStage.FINALIZING, 100))
            PreparedArchive(archiveFile, summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: SecurityException) {
            throw BackupException("Permission was revoked while the backup was being prepared.")
        } catch (e: IOException) {
            throw BackupException(
                "The backup archive could not be written. Check free storage and try again."
            )
        } catch (e: Exception) {
            throw BackupException("The backup could not be prepared.")
        } finally {
            if (!success) {
                archiveFile.delete()
            }
        }
    }

    /** Removes a finished archive after upload, cancellation, or refusal. */
    fun deleteArchive(archive: PreparedArchive) {
        archive.file.delete()
    }

    /** Removes every temporary archive owned by this app. */
    fun clearTemporaryArchives() {
        backupDirectory().listFiles()?.forEach { file ->
            file.deleteRecursively()
        }
    }

    private fun deleteTemporaryArchivesInternal() {
        clearTemporaryArchives()
    }

    private fun backupDirectory(): File = File(context.cacheDir, BACKUP_DIRECTORY)

    private fun buildManifest(
        request: BackupRequest,
        photoCount: Int,
        contactCount: Int,
        skippedPhotos: Int,
        createdAt: Instant
    ): ByteArray {
        val categories = JSONArray()
        if (request.includePhotos) {
            categories.put("photos")
        }
        if (request.includeContacts) {
            categories.put("contacts")
        }

        val photoAccess = when {
            !request.includePhotos -> "not_included"
            request.photoAccessPartial -> "selected_only"
            else -> "full"
        }

        val manifest = JSONObject().apply {
            put("app", "HTML Backup")
            put("schemaVersion", 1)
            put("createdAt", DateTimeFormatter.ISO_INSTANT.format(createdAt))
            put("categories", categories)
            put("photoCount", photoCount)
            put("contactCount", contactCount)
            put("skippedPhotoCount", skippedPhotos)
            put("photoAccess", photoAccess)
            put(
                "note",
                "Created on-device. Contact details are in contacts.json; photo files are in photos/."
            )
        }

        return manifest.toString(2).toByteArray(Charsets.UTF_8)
    }

    private data class PreparedData(
        val photoCount: Int,
        val photoBytes: Long,
        val contactCount: Int,
        val contactsBytes: Long,
        val skippedPhotos: Int,
        val sampleEntries: List<String>
    )

    private companion object {
        const val BACKUP_DIRECTORY = "backups"
        const val PHOTOS_DIRECTORY_ENTRY = "photos/"
        const val CONTACTS_ENTRY = "contacts.json"
        const val MANIFEST_ENTRY = "manifest.json"
        const val STREAM_BUFFER_SIZE = 128 * 1024
        const val MAX_SUMMARY_SAMPLES = 6
    }
}
