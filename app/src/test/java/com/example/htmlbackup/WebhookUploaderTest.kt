package com.example.htmlbackup

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class WebhookUploaderTest {

    private lateinit var server: MockWebServer
    private lateinit var uploader: WebhookUploader

    private val sleepRequests = mutableListOf<Long>()
    private val statuses = mutableListOf<UploadStatus>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        uploader = WebhookUploader(
            client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
            endpointProvider = {
                server.url("/api/webhooks/123456789012345678/${"A".repeat(64)}")
            },
            sleeper = { millis -> sleepRequests += millis }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sendMessage(): UploadResult = runBlocking {
        statuses.clear()
        uploader.sendMessage(
            payload = JSONObject()
                .put("content", "Backup started")
                .put("embeds", org.json.JSONArray()),
            senderName = "Rahul's Pixel",
            onStatus = { status -> statuses += status }
        )
    }

    private fun sendAttachment(): UploadResult = runBlocking {
        statuses.clear()
        uploader.sendAttachment(
            body = BytesRequestBody(
                bytes = "photo-bytes".toByteArray(Charsets.UTF_8),
                mediaType = "image/jpeg".toMediaType()
            ),
            filename = "00001_photo.jpg",
            caption = "Photo 1 of 3",
            senderName = "Rahul's Pixel",
            onStatus = { status -> statuses += status }
        )
    }

    @Test
    fun sendMessagePostsJsonWithPhoneUsername() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"111"}"""))

        val result = sendMessage()
        assertTrue(result is UploadResult.Success)
        assertEquals("111", (result as UploadResult.Success).messageId)

        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertTrue(request.path!!.startsWith("/api/webhooks/123456789012345678/"))
        assertTrue(request.path!!.contains("wait=true"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("application/json", request.getHeader("Accept"))

        val body = JSONObject(request.body.readUtf8())
        assertEquals("Rahul's Pixel", body.getString("username"))
        assertEquals("Backup started", body.getString("content"))
    }

    @Test
    fun sendAttachmentUsesDiscordMultipartWithSingleFile() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"222"}"""))

        val result = sendAttachment()
        assertTrue(result is UploadResult.Success)
        assertEquals("222", (result as UploadResult.Success).messageId)

        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val bodyText = request.body.readByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(bodyText.contains("name=\"payload_json\""))
        assertTrue(bodyText.contains("name=\"files[0]\"; filename=\"00001_photo.jpg\""))
        assertTrue(bodyText.contains("Content-Type: image/jpeg"))
        assertTrue(bodyText.contains("photo-bytes"))
        assertTrue(bodyText.contains("\"username\":\"Rahul's Pixel\""))
        assertTrue(bodyText.contains("\"content\":\"Photo 1 of 3\""))
        assertTrue(bodyText.contains("\"id\":\"0\""))
    }

    @Test
    fun retriesRateLimitAndReportsDelay() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"message":"rate limited","retry_after":0.5}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"42"}"""))

        val result = sendMessage()
        assertTrue(result is UploadResult.Success)
        assertEquals(listOf(1000L), sleepRequests)
        assertEquals(2, server.requestCount)
        assertTrue(statuses.any { it is UploadStatus.RateLimited })
    }

    @Test
    fun stopsAfterRateLimitRetriesAreExhausted() {
        repeat(4) {
            server.enqueue(
                MockResponse().setResponseCode(429).setBody("""{"retry_after":1}""")
            )
        }

        val result = sendMessage()
        val failure = result as? UploadResult.Failure
        assertNotNull(failure)
        assertEquals(UploadFailureKind.RATE_LIMITED, failure?.kind)
        assertEquals(429, failure?.httpStatusCode)
        assertEquals(4, server.requestCount)
        assertEquals(3, sleepRequests.size)
    }

    @Test
    fun reportsPayloadTooLarge() {
        server.enqueue(MockResponse().setResponseCode(413).setBody("Payload Too Large"))
        val failure = sendAttachment() as? UploadResult.Failure
        assertEquals(UploadFailureKind.PAYLOAD_TOO_LARGE, failure?.kind)
        assertEquals(413, failure?.httpStatusCode)
    }

    @Test
    fun treatsDiscordEntityTooLargeErrorAsPayloadTooLarge() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"code":40005,"message":"Request entity too large"}""")
        )
        val failure = sendAttachment() as? UploadResult.Failure
        assertEquals(UploadFailureKind.PAYLOAD_TOO_LARGE, failure?.kind)
    }

    @Test
    fun reportsExpiredOrInvalidWebhook() {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"message":"Unknown Webhook"}""")
        )
        val failure = sendMessage() as? UploadResult.Failure
        assertEquals(UploadFailureKind.NOT_FOUND, failure?.kind)
        assertEquals(404, failure?.httpStatusCode)
    }

    @Test
    fun reportsServerError() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val failure = sendMessage() as? UploadResult.Failure
        assertEquals(UploadFailureKind.SERVER_ERROR, failure?.kind)
        assertEquals(503, failure?.httpStatusCode)
    }

    @Test
    fun doesNotFollowRedirects() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://example.invalid/steal")
                .setBody("")
        )

        val failure = sendAttachment() as? UploadResult.Failure
        assertEquals(UploadFailureKind.REDIRECT_REJECTED, failure?.kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsInvalidConfigurationWithoutNetwork() {
        val unconfigured = WebhookUploader(
            client = OkHttpClient(),
            endpointProvider = { null },
            sleeper = { _ -> }
        )

        val result = runBlocking {
            unconfigured.sendMessage(
                payload = JSONObject().put("content", "x"),
                senderName = "phone",
                onStatus = { _ -> }
            )
        }
        assertEquals(
            UploadFailureKind.INVALID_CONFIGURATION,
            (result as? UploadResult.Failure)?.kind
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun acceptsNoContentSuccessWithoutMessageId() {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = sendMessage()
        assertTrue(result is UploadResult.Success)
        assertNull((result as UploadResult.Success).messageId)
    }
}
