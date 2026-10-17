package com.example.htmlbackup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

/** Final outcome of one webhook request (including rate-limit retries). */
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

/** Non-terminal status updates shown while a request is running. */
sealed interface UploadStatus {
    data object Sending : UploadStatus
    data class RateLimited(val retryAfterSeconds: Long) : UploadStatus
}

/**
 * Sends one Discord webhook message per call, over HTTPS.
 *
 * Two shapes are supported:
 * - [sendMessage]: a JSON-only message (used for the device/app report).
 * - [sendAttachment]: multipart with exactly one `files[0]` part.
 *
 * Security properties:
 * - the endpoint comes from [WebhookConfig], which enforces HTTPS and an exact
 *   Discord host allowlist;
 * - redirects are not followed, so the webhook token cannot be forwarded;
 * - the webhook URL is never logged or included in a failure result;
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

    /** Sends a JSON-only message, used for the device and app report. */
    suspend fun sendMessage(
        payload: JSONObject,
        senderName: String,
        onStatus: (UploadStatus) -> Unit
    ): UploadResult {
        val body = withSender(payload, senderName).toString()
        val endpoint = endpointProvider()
            ?: return UploadResult.Failure(UploadFailureKind.INVALID_CONFIGURATION)

        return execute(
            buildRequest = {
                Request.Builder()
                    .url(withWait(endpoint))
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build()
            },
            onStatus = onStatus
        )
    }

    /** Sends exactly one file, with [caption] as the message content. */
    suspend fun sendAttachment(
        body: RequestBody,
        filename: String,
        caption: String,
        senderName: String,
        onStatus: (UploadStatus) -> Unit
    ): UploadResult {
        val endpoint = endpointProvider()
            ?: return UploadResult.Failure(UploadFailureKind.INVALID_CONFIGURATION)

        val payloadJson = withSender(JSONObject().put("content", caption), senderName)
            .put(
                "attachments",
                JSONArray().put(
                    JSONObject().apply {
                        put("id", "0")
                        put("filename", filename)
                    }
                )
            )
            .toString()

        return execute(
            buildRequest = {
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", payloadJson)
                    .addFormDataPart("files[0]", filename, body)
                    .build()

                Request.Builder()
                    .url(withWait(endpoint))
                    .post(multipart)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build()
            },
            onStatus = onStatus
        )
    }

    private suspend fun execute(
        buildRequest: () -> Request,
        onStatus: (UploadStatus) -> Unit
    ): UploadResult {
        var rateLimitRetries = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            onStatus(UploadStatus.Sending)

            val response = try {
                client.newCall(buildRequest()).awaitResponse()
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
                        ),
                        errorCode = parseDiscordErrorCode(responseBody)
                    )
                }
            }

            if (info.successful) {
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

            if (info.code == 413 || info.errorCode == DISCORD_ENTITY_TOO_LARGE_CODE) {
                return UploadResult.Failure(
                    kind = UploadFailureKind.PAYLOAD_TOO_LARGE,
                    httpStatusCode = info.code
                )
            }

            return UploadResult.Failure(
                kind = failureKindForStatus(info.code),
                httpStatusCode = info.code,
                retryAfterSeconds = info.retryAfterSeconds
            )
        }
    }

    private fun withSender(payload: JSONObject, senderName: String): JSONObject =
        JSONObject(payload.toString()).apply {
            put(
                "username",
                senderName.ifBlank { DEFAULT_SENDER_NAME }.take(MAX_SENDER_NAME_LENGTH)
            )
        }

    private fun withWait(endpoint: HttpUrl): HttpUrl =
        endpoint.newBuilder()
            .setQueryParameter("wait", "true")
            .build()

    private fun failureKindForStatus(code: Int): UploadFailureKind = when {
        code in 300..399 -> UploadFailureKind.REDIRECT_REJECTED
        code == 401 || code == 403 -> UploadFailureKind.UNAUTHORIZED
        code == 404 -> UploadFailureKind.NOT_FOUND
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

    private fun parseDiscordErrorCode(body: String): Int? = runCatching {
        if (body.isBlank()) {
            null
        } else {
            JSONObject(body).optInt("code", -1).takeIf { it > 0 }
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

    private data class ResponseInfo(
        val code: Int,
        val successful: Boolean,
        val messageId: String?,
        val retryAfterSeconds: Long?,
        val errorCode: Int?
    )

    private companion object {
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
        const val USER_AGENT = "HTMLBackup/2.0 (Android)"
        const val DEFAULT_SENDER_NAME = "Android phone"
        const val MAX_SENDER_NAME_LENGTH = 80
        const val MAX_RATE_LIMIT_RETRIES = 3
        const val MAX_RATE_LIMIT_WAIT_SECONDS = 60L
        const val DISCORD_ENTITY_TOO_LARGE_CODE = 40005

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

private fun String.toRequestBody(mediaType: MediaType): RequestBody =
    RequestBody.create(mediaType, this)

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
