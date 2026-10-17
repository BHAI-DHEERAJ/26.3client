package com.example.htmlbackup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

/** Final outcome of one upload attempt (including rate-limit retries). */
sealed interface UploadResult {
    data class Success(val messageId: String?) : UploadResult

    data class Failure(
        val kind: UploadFailureKind,
        val httpStatusCode: Int? = null,
        val retryAfterSeconds: Long? = null
    ) : UploadResult
}

/** Safe, structured failure reasons; the UI supplies the human-readable text. */
enum class UploadFailureKind {
    INVALID_CONFIGURATION,
    ARCHIVE_UNAVAILABLE,
    CONNECTIVITY,
    TIMEOUT,
    TLS,
    NETWORK,
    REDIRECT_REJECTED,
    UNAUTHORIZED,
    NOT_FOUND,
    PAYLOAD_TOO_LARGE,
    RATE_LIMITED,
    SERVER_ERROR,
    REJECTED,
    UNEXPECTED
}

/** Non-terminal status updates shown while the upload is running. */
sealed interface UploadStatus {
    data object Sending : UploadStatus
    data class RateLimited(val retryAfterSeconds: Long) : UploadStatus
}

/**
 * Uploads the prepared ZIP to the Discord webhook using Discord's multipart
 * format: a payload_json part plus a files[0] part.
 *
 * Security properties:
 * - The endpoint is provided by [WebhookConfig], which validates HTTPS and an
 *   exact Discord host allowlist.
 * - Redirects are not followed, preventing the webhook token from being
 *   forwarded to another host.
 * - The webhook URL is never logged and never included in a failure result.
 * - OkHttp logging interceptors are deliberately not installed.
 */
class WebhookUploader internal constructor(
    private val client: OkHttpClient,
    private val endpointProvider: () -> HttpUrl?,
    private val sleeper: suspend (Long) -> Unit
) {
    constructor() : this(
        client = defaultClient(),
        endpointProvider = { WebhookConfig.uploadUrl() },
        sleeper = { millis -> delay(millis) }
    )

    suspend fun upload(
        archive: File,
        summary: ArchiveSummary,
        onProgress: (Int) -> Unit,
        onStatus: (UploadStatus) -> Unit
    ): UploadResult {
        if (!archive.isFile || !archive.canRead()) {
            return UploadResult.Failure(UploadFailureKind.ARCHIVE_UNAVAILABLE)
        }

        val endpoint = endpointProvider()
            ?: return UploadResult.Failure(UploadFailureKind.INVALID_CONFIGURATION)

        var rateLimitRetries = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            onProgress(0)
            onStatus(UploadStatus.Sending)

            val request = buildRequest(endpoint, archive, summary, onProgress)
            val response = try {
                client.newCall(request).awaitResponse()
            } catch (e: IOException) {
                return UploadResult.Failure(kind = networkFailureKind(e))
            }

            val info = withContext(Dispatchers.IO) {
                response.use { currentResponse ->
                    val responseBody = currentResponse.body?.string().orEmpty()
                    ResponseInfo(
                        code = currentResponse.code,
                        successful = currentResponse.isSuccessful,
                        messageId = if (currentResponse.isSuccessful) {
                            parseMessageId(responseBody)
                        } else {
                            null
                        },
                        retryAfterSeconds = parseRetryAfterSeconds(
                            headerValue = currentResponse.header("Retry-After"),
                            body = responseBody
                        )
                    )
                }
            }

            if (info.successful) {
                onProgress(100)
                return UploadResult.Success(info.messageId)
            }

            if (info.code == 429) {
                val waitSeconds = info.retryAfterSeconds ?: 1L
                if (
                    rateLimitRetries < MAX_RATE_LIMIT_RETRIES &&
                    waitSeconds <= MAX_RATE_LIMIT_WAIT_SECONDS
                ) {
                    rateLimitRetries++
                    onStatus(UploadStatus.RateLimited(waitSeconds))
                    sleeper(waitSeconds * 1000L)
                    continue
                }

                return UploadResult.Failure(
                    kind = UploadFailureKind.RATE_LIMITED,
                    httpStatusCode = info.code,
                    retryAfterSeconds = waitSeconds
                )
            }

            return UploadResult.Failure(
                kind = failureKindForStatus(info.code),
                httpStatusCode = info.code,
                retryAfterSeconds = info.retryAfterSeconds
            )
        }
    }

    private fun buildRequest(
        endpoint: HttpUrl,
        archive: File,
        summary: ArchiveSummary,
        onProgress: (Int) -> Unit
    ): Request {
        // wait=true asks Discord to return the created message (and its id),
        // so a 2xx response is an actual confirmed upload.
        val url = endpoint.newBuilder()
            .setQueryParameter("wait", "true")
            .build()

        val payload = JSONObject().apply {
            put("content", contentFor(summary))
            put("username", "HTML Backup")
            put("allowed_mentions", JSONObject().put("parse", JSONArray()))
            put(
                "attachments",
                JSONArray().put(
                    JSONObject().apply {
                        put("id", "0")
                        put("filename", archive.name)
                    }
                )
            )
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("payload_json", payload.toString())
            .addFormDataPart(
                "files[0]",
                archive.name,
                ProgressRequestBody(archive, ZIP_MEDIA_TYPE, onProgress)
            )
            .build()

        return Request.Builder()
            .url(url)
            .post(multipart)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
    }

    private fun contentFor(summary: ArchiveSummary): String {
        val categories = mutableListOf<String>()
        if (summary.photosIncluded) {
            categories += "${summary.photoCount} photo(s)"
        }
        if (summary.contactsIncluded) {
            categories += "${summary.contactCount} contact(s)"
        }
        return "HTML Backup archive: ${categories.joinToString(", ")}."
    }

    private fun failureKindForStatus(code: Int): UploadFailureKind = when {
        code in 300..399 -> UploadFailureKind.REDIRECT_REJECTED
        code == 401 || code == 403 -> UploadFailureKind.UNAUTHORIZED
        code == 404 -> UploadFailureKind.NOT_FOUND
        code == 413 -> UploadFailureKind.PAYLOAD_TOO_LARGE
        code in 500..599 -> UploadFailureKind.SERVER_ERROR
        else -> UploadFailureKind.REJECTED
    }

    private fun networkFailureKind(error: IOException): UploadFailureKind = when (error) {
        is UnknownHostException, is ConnectException -> UploadFailureKind.CONNECTIVITY
        is SocketTimeoutException -> UploadFailureKind.TIMEOUT
        is SSLException -> UploadFailureKind.TLS
        else -> UploadFailureKind.NETWORK
    }

    private fun parseMessageId(body: String): String? = runCatching {
        if (body.isBlank()) {
            null
        } else {
            JSONObject(body).optString("id").ifBlank { null }
        }
    }.getOrNull()

    private fun parseRetryAfterSeconds(headerValue: String?, body: String): Long? {
        val headerSeconds = headerValue?.trim()?.toDoubleOrNull()
        val bodySeconds = if (body.isBlank()) {
            null
        } else {
            runCatching {
                JSONObject(body).optDouble("retry_after", Double.NaN)
            }.getOrNull()?.takeUnless { it.isNaN() }
        }

        val seconds = headerSeconds ?: bodySeconds ?: return null
        return seconds.coerceIn(1.0, 60.0).roundToLong()
    }

    private class ProgressRequestBody(
        private val file: File,
        private val mediaType: MediaType,
        private val onProgress: (Int) -> Unit
    ) : RequestBody() {

        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val totalBytes = file.length()
            if (totalBytes <= 0L) {
                onProgress(100)
                return
            }

            var uploadedBytes = 0L
            var lastPercent = -1
            val buffer = ByteArray(UPLOAD_BUFFER_SIZE)

            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }
                    sink.write(buffer, 0, read)
                    uploadedBytes += read
                    val percent = ((uploadedBytes * 100.0) / totalBytes)
                        .toInt()
                        .coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }

            sink.flush()
            onProgress(100)
        }
    }

    private data class ResponseInfo(
        val code: Int,
        val successful: Boolean,
        val messageId: String?,
        val retryAfterSeconds: Long?
    )

    private companion object {
        val ZIP_MEDIA_TYPE: MediaType = "application/zip".toMediaType()
        const val USER_AGENT = "HTMLBackup/1.0 (Android)"
        const val MAX_RATE_LIMIT_RETRIES = 2
        const val MAX_RATE_LIMIT_WAIT_SECONDS = 30L
        const val UPLOAD_BUFFER_SIZE = 64 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }

    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}
