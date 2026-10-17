package com.example.htmlbackup

import android.content.Context
import kotlinx.coroutines.delay
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import kotlin.math.roundToInt

/**
 * Sends a [BackupPlan] to Discord one request per item:
 *
 *   1. one device/app info message,
 *   2. one `contacts.json` file (when contacts are authorized),
 *   3. one message per photo.
 *
 * No ZIP is created. Photos are streamed from MediaStore. If a photo cannot be
 * delivered, it is re-encoded as a smaller JPEG and retried. If that still
 * fails, a last-resort copy with one quarter of the resolution (long edge
 * 2048 / 4 = 512 px) is tried. Only if that also fails is the photo skipped;
 * the remaining photos continue. Files already over Discord's default 20 MiB
 * limit skip straight to compression.
 *
 * Requests are serialized with a small delay to stay friendly to Discord's
 * per-webhook rate limit; 429 responses are handled by [WebhookUploader].
 */
class BackupSender(
    private val context: Context,
    private val uploader: WebhookUploader = WebhookUploader(),
    private val compressor: MediaCompressor = MediaCompressor(context),
    private val pause: suspend (Long) -> Unit = { millis -> delay(millis) }
) {

    suspend fun send(
        plan: BackupPlan,
        onProgress: (UploadProgress) -> Unit
    ): UploadReport {
        val totalItems = plan.itemsToUpload
        val senderName = plan.deviceProfile.senderName

        var completed = 0
        var infoSent = false
        var contactsSent = false
        var photosAttempted = 0
        var photosSent = 0
        var photosCompressedSent = 0
        var photosSkipped = 0
        var aborted = false
        var abortReason: UploadFailureKind? = null
        var consecutiveNetworkFailures = 0

        fun report(
            phase: UploadPhase,
            itemPercent: Int,
            note: UploadNote = UploadNote.SENDING,
            retryAfterSeconds: Long? = null
        ) {
            val percent = itemPercent.coerceIn(0, 100)
            val overall = (((completed + percent / 100.0) / totalItems) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
            onProgress(
                UploadProgress(
                    phase = phase,
                    itemIndex = (completed + 1).coerceAtMost(totalItems),
                    itemCount = totalItems,
                    itemPercent = percent,
                    overallPercent = overall,
                    note = note,
                    retryAfterSeconds = retryAfterSeconds
                )
            )
        }

        fun statusHandler(
            phase: UploadPhase,
            itemPercent: Int
        ): (UploadStatus) -> Unit = { status ->
            when (status) {
                UploadStatus.Sending -> report(phase, itemPercent, UploadNote.SENDING)
                is UploadStatus.RateLimited -> report(
                    phase = phase,
                    itemPercent = itemPercent,
                    note = UploadNote.RATE_LIMITED,
                    retryAfterSeconds = status.retryAfterSeconds
                )
            }
        }

        fun progressHandler(phase: UploadPhase): (Int) -> Unit = { percent ->
            report(
                phase = phase,
                itemPercent = percent,
                note = if (percent >= 100) {
                    UploadNote.WAITING_CONFIRMATION
                } else {
                    UploadNote.SENDING
                }
            )
        }

        try {
            // ---- 1. Device and app information -------------------------------
            report(UploadPhase.DEVICE_INFO, 0, UploadNote.SENDING)
            val infoResult = uploader.sendMessage(
                payload = DeviceInfo.reportPayload(plan.deviceProfile, plan),
                senderName = senderName,
                onStatus = statusHandler(UploadPhase.DEVICE_INFO, 0)
            )
            if (infoResult is UploadResult.Failure) {
                report(UploadPhase.DEVICE_INFO, 0)
                return UploadReport(
                    infoSent = false,
                    contactsIncluded = plan.contactsIncluded,
                    contactsSent = false,
                    photosAttempted = 0,
                    photosSent = 0,
                    photosCompressedSent = 0,
                    photosSkipped = 0,
                    aborted = true,
                    abortReason = infoResult.kind
                )
            }
            infoSent = true
            completed += 1
            report(UploadPhase.DEVICE_INFO, 100)
            pause(INTER_ITEM_DELAY_MS)

            // ---- 2. contacts.json ---------------------------------------------
            val contacts = plan.contacts
            if (contacts != null) {
                val payloadBytes = ContactJsonEncoder.encode(contacts)
                val contactsMediaType = JSON_MEDIA_TYPE

                suspend fun sendContacts(): UploadResult {
                    val body = BytesRequestBody(
                        bytes = payloadBytes,
                        mediaType = contactsMediaType,
                        onProgress = progressHandler(UploadPhase.CONTACTS)
                    )
                    return uploader.sendAttachment(
                        body = body,
                        filename = CONTACTS_FILENAME,
                        caption = "Contacts (${plan.contactCount} records)",
                        senderName = senderName,
                        onStatus = statusHandler(UploadPhase.CONTACTS, 0)
                    )
                }

                report(UploadPhase.CONTACTS, 0, UploadNote.SENDING)
                var result = sendContacts()
                if (result is UploadResult.Failure && result.kind.isConnectionProblem()) {
                    report(UploadPhase.CONTACTS, 0, UploadNote.RETRYING)
                    pause(TRANSIENT_RETRY_DELAY_MS)
                    result = sendContacts()
                }

                contactsSent = result is UploadResult.Success
                if (!contactsSent) {
                    val kind = (result as? UploadResult.Failure)?.kind
                    consecutiveNetworkFailures = if (kind.isConnectionProblem()) {
                        consecutiveNetworkFailures + 1
                    } else {
                        0
                    }
                } else {
                    consecutiveNetworkFailures = 0
                }

                completed += 1
                report(UploadPhase.CONTACTS, 100)

                if (consecutiveNetworkFailures >= MAX_CONSECUTIVE_NETWORK_FAILURES) {
                    aborted = true
                    abortReason = (result as? UploadResult.Failure)?.kind
                    return UploadReport(
                        infoSent = infoSent,
                        contactsIncluded = true,
                        contactsSent = contactsSent,
                        photosAttempted = photosAttempted,
                        photosSent = photosSent,
                        photosCompressedSent = photosCompressedSent,
                        photosSkipped = photosSkipped,
                        aborted = aborted,
                        abortReason = abortReason
                    )
                }

                pause(INTER_ITEM_DELAY_MS)
            }

            // ---- 3. photos, one request each ---------------------------------
            plan.photos.forEachIndexed { index, photo ->
                val number = index + 1
                val caption = "Photo $number of ${plan.photos.size}"

                val outcome = sendPhoto(
                    photo = photo,
                    number = number,
                    caption = caption,
                    senderName = senderName,
                    report = ::report,
                    statusHandler = ::statusHandler,
                    progressHandler = ::progressHandler
                )

                photosAttempted += 1
                when (outcome) {
                    is PhotoOutcome.Delivered -> {
                        photosSent += 1
                        if (outcome.compressed) {
                            photosCompressedSent += 1
                        }
                        consecutiveNetworkFailures = 0
                    }

                    is PhotoOutcome.Skipped -> {
                        photosSkipped += 1
                        consecutiveNetworkFailures = if (outcome.kind.isConnectionProblem()) {
                            consecutiveNetworkFailures + 1
                        } else {
                            0
                        }
                    }
                }

                completed += 1
                report(UploadPhase.PHOTOS, 100)

                if (consecutiveNetworkFailures >= MAX_CONSECUTIVE_NETWORK_FAILURES) {
                    aborted = true
                    abortReason = (outcome as? PhotoOutcome.Skipped)?.kind
                    return UploadReport(
                        infoSent = infoSent,
                        contactsIncluded = plan.contactsIncluded,
                        contactsSent = contactsSent,
                        photosAttempted = photosAttempted,
                        photosSent = photosSent,
                        photosCompressedSent = photosCompressedSent,
                        photosSkipped = photosSkipped,
                        aborted = true,
                        abortReason = abortReason
                    )
                }

                if (number < plan.photos.size) {
                    pause(INTER_ITEM_DELAY_MS)
                }
            }

            return UploadReport(
                infoSent = infoSent,
                contactsIncluded = plan.contactsIncluded,
                contactsSent = contactsSent,
                photosAttempted = photosAttempted,
                photosSent = photosSent,
                photosCompressedSent = photosCompressedSent,
                photosSkipped = photosSkipped,
                aborted = aborted,
                abortReason = abortReason
            )
        } finally {
            compressor.clearCache()
        }
    }

    /**
     * Fallback chain for one photo:
     *
     *   1. original bytes (skipped when the file is already over 20 MiB),
     *   2. compressed copy at normal resolution (long edge up to 2048 px),
     *   3. compressed copy at one quarter resolution (long edge 512 px),
     *   4. skip this photo and continue with the next one.
     *
     * Transient connection errors get one immediate retry before compression.
     */
    private suspend fun sendPhoto(
        photo: PhotoItem,
        number: Int,
        caption: String,
        senderName: String,
        report: (UploadPhase, Int, UploadNote, Long?) -> Unit,
        statusHandler: (UploadPhase, Int) -> (UploadStatus) -> Unit,
        progressHandler: (UploadPhase) -> (Int) -> Unit
    ): PhotoOutcome {
        var lastFailureKind: UploadFailureKind? = null

        if (photo.sizeBytes <= MAX_DIRECT_UPLOAD_BYTES) {
            val first = sendOriginal(photo, number, caption, senderName, statusHandler, progressHandler)
            when (first) {
                is UploadResult.Success -> return PhotoOutcome.Delivered(compressed = false)

                is UploadResult.Failure -> {
                    lastFailureKind = first.kind
                    if (first.kind.isConnectionProblem()) {
                        report(UploadPhase.PHOTOS, 0, UploadNote.RETRYING, null)
                        pause(TRANSIENT_RETRY_DELAY_MS)
                        val retry = sendOriginal(
                            photo,
                            number,
                            caption,
                            senderName,
                            statusHandler,
                            progressHandler
                        )
                        if (retry is UploadResult.Success) {
                            return PhotoOutcome.Delivered(compressed = false)
                        }
                        lastFailureKind = (retry as? UploadResult.Failure)?.kind ?: lastFailureKind
                    }
                }
            }
        }

        for (preset in COMPRESSION_FALLBACKS) {
            report(UploadPhase.PHOTOS, 0, UploadNote.COMPRESSING, null)
            val compressedFile = compressor.compress(photo, number, preset) ?: continue

            try {
                val body = FileRequestBody(
                    file = compressedFile,
                    mediaType = JPEG_MEDIA_TYPE,
                    onProgress = progressHandler(UploadPhase.PHOTOS)
                )
                val result = uploader.sendAttachment(
                    body = body,
                    filename = PhotoFormats.compressedFileName(
                        index = number,
                        displayName = photo.displayName,
                        quarterResolution = preset == CompressionPreset.QUARTER
                    ),
                    caption = if (preset == CompressionPreset.QUARTER) {
                        "$caption (compressed, low resolution)"
                    } else {
                        "$caption (compressed)"
                    },
                    senderName = senderName,
                    onStatus = statusHandler(UploadPhase.PHOTOS, 0)
                )

                when (result) {
                    is UploadResult.Success -> return PhotoOutcome.Delivered(compressed = true)
                    is UploadResult.Failure -> lastFailureKind = result.kind
                }
            } finally {
                compressedFile.delete()
            }
        }

        return PhotoOutcome.Skipped(kind = lastFailureKind)
    }

    private sealed interface PhotoOutcome {
        data class Delivered(val compressed: Boolean) : PhotoOutcome
        data class Skipped(val kind: UploadFailureKind?) : PhotoOutcome
    }

    /** Removes compressed fallback files left in the cache. */
    fun clearTemporaryFiles() {
        compressor.clearCache()
    }

    private companion object {
        const val CONTACTS_FILENAME = "contacts.json"
        const val INTER_ITEM_DELAY_MS = 400L
        const val TRANSIENT_RETRY_DELAY_MS = 1500L
        const val MAX_CONSECUTIVE_NETWORK_FAILURES = 3

        /** Discord's default free per-file limit; larger files are compressed first. */
        const val MAX_DIRECT_UPLOAD_BYTES = 20L * 1024L * 1024L

        /** Normal compression first, then a quarter-resolution last resort. */
        val COMPRESSION_FALLBACKS = listOf(
            CompressionPreset.NORMAL,
            CompressionPreset.QUARTER
        )

        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
        val JPEG_MEDIA_TYPE: MediaType = "image/jpeg".toMediaType()
        val OCTET_STREAM_MEDIA_TYPE: MediaType = "application/octet-stream".toMediaType()
    }
}

private val UploadFailureKind?.isConnectionProblem: Boolean
    get() = this != null && this in setOf(
        UploadFailureKind.CONNECTIVITY,
        UploadFailureKind.TIMEOUT,
        UploadFailureKind.TLS,
        UploadFailureKind.NETWORK
    )
