package com.example.htmlbackup

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Discovers exactly what the user authorized: accessible MediaStore photos,
 * real Contacts Provider records, and device/app metadata.
 *
 * No ZIP is built and no photo is copied here. Photos remain in place and are
 * streamed one by one during upload. The only disk writes during upload are
 * short-lived compressed fallback files.
 */
class BackupPreparer(private val context: Context) {
    private val photoManager = PhotoBackupManager(context)
    private val contactExporter = ContactExporter(context)

    suspend fun prepare(
        request: BackupRequest,
        onProgress: (PreparationProgress) -> Unit
    ): BackupPlan = withContext(Dispatchers.IO) {
        val access = PermissionSupport.currentAccess(context)
        if (request.includePhotos && !access.hasPhotos) {
            throw BackupException("Photo access is no longer granted.")
        }
        if (request.includeContacts && !access.hasContacts) {
            throw BackupException("Contacts access is no longer granted.")
        }

        // Older versions left ZIPs behind; remove any that still exist.
        removeLegacyArchives()

        onProgress(PreparationProgress(PreparationStage.STARTING, 0))

        try {
            var photos: List<PhotoItem> = emptyList()
            if (request.includePhotos) {
                currentCoroutineContext().ensureActive()
                onProgress(PreparationProgress(PreparationStage.QUERYING_PHOTOS, 10))
                photos = photoManager.queryAccessiblePhotos()
                onProgress(PreparationProgress(PreparationStage.QUERYING_PHOTOS, 55))
            }

            var contacts: ContactExport? = null
            if (request.includeContacts) {
                currentCoroutineContext().ensureActive()
                onProgress(PreparationProgress(PreparationStage.READING_CONTACTS, 70))
                contacts = contactExporter.readContacts()
                onProgress(PreparationProgress(PreparationStage.READING_CONTACTS, 90))
            }

            currentCoroutineContext().ensureActive()
            onProgress(PreparationProgress(PreparationStage.FINALIZING, 95))

            val plan = BackupPlan(
                request = request,
                photos = photos,
                contacts = contacts,
                deviceProfile = DeviceInfo.profile(context),
                createdAt = Instant.now()
            )

            onProgress(PreparationProgress(PreparationStage.FINALIZING, 100))
            plan
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: SecurityException) {
            throw BackupException("Permission was revoked while the backup was being prepared.")
        } catch (e: IOException) {
            throw BackupException("The device data could not be read. Check access and try again.")
        } catch (e: Exception) {
            throw BackupException("The backup could not be prepared.")
        }
    }

    private fun removeLegacyArchives() {
        val legacy = File(context.cacheDir, "backups")
        if (legacy.exists()) {
            legacy.deleteRecursively()
        }
    }
}
