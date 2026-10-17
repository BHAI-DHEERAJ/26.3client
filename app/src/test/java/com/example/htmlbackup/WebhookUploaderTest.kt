package com.example.htmlbackup

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebhookUploaderTest {

    private lateinit var server: MockWebServer
    private lateinit var archive: File
    private lateinit var summary: ArchiveSummary
    private lateinit var uploader: WebhookUploader

    private val sleepRequests = mutableListOf<Long>()
    private val progressValues = mutableListOf<Int>()
    private val statuses = mutableListOf<UploadStatus>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        archive = File.createTempFile("html-backup-test-", ".zip").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"app":"HTML Backup"}""".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            deleteOnExit()
        }

        summary = ArchiveSummary(
            archiveName = archive.name,
            archiveBytes = archive.length(),
            photosIncluded = true,
            contactsIncluded = true,
            photoCount = 2,
            contactCount = 1,
            photoBytes = 100,
            contactsBytes = 50,
            skippedPhotos = 0,
            photoAccessPartial = false,
            sampleEntries = listOf(
                "photos/",
                "photos/00001_test.jpg",
                "contacts.json",
                "manifest.json"
            ),
            createdAt = Instant.parse("2026-10-17T12:00:00Z")
        )

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
        archive.delete()
    }

    private fun upload(): UploadResult {
        progressValues.clear()
        statuses.clear()
        return runBlocking {
            uploader.upload(
                archive = archive,
                summary = summary,
                senderName = "Test Phone",
                onProgress = { percent -> progressValues += percent },
                onStatus = { status -> statuses += status }
            )
        }
    }

    @Test
    fun uploadsDiscordMultipartAndReturnsMessageId() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"id":"999888777"}""")
        )

        val result = upload()
        assertTrue(result is UploadResult.Success)
        assertEquals("999888777", (result as UploadResult.Success).messageId)
        assertEquals(100, progressValues.last())
        assertTrue(statuses.any { it == UploadStatus.Sending })

        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertTrue(request.path!!.startsWith("/api/webhooks/123456789012345678/"))
        assertTrue(request.path!!.contains("wait=true"))
        assertTrue(request.getHeader("User-Agent")!!.contains("HTMLBackup"))
        assertEquals("application/json", request.getHeader("Accept"))

        val bodyText = request.body.readByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(bodyText.contains("name=\"payload_json\""))
        assertTrue(bodyText.contains("name=\"files[0]\"; filename=\"${archive.name}\""))
        assertTrue(bodyText.contains("Content-Type: application/zip"))
        assertTrue(bodyText.contains("2 photo(s), 1 contact(s)"))
        assertTrue(bodyText.contains("\"username\":\"Test Phone\""))
    }

    @Test
    fun retriesOnceAfterRateLimitAndReportsDelay() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"message":"You are being rate limited.","retry_after":0.5}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"id":"42"}""")
        )

        val result = upload()
        assertTrue(result is UploadResult.Success)
        assertEquals(listOf(1000L), sleepRequests)
        assertEquals(2, server.requestCount)
        assertTrue(statuses.any { it is UploadStatus.RateLimited })
    }

    @Test
    fun stopsAfterRateLimitRetriesAreExhausted() {
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setBody("""{"retry_after":1}""")
            )
        }

        val result = upload()
        val failure = result as? UploadResult.Failure
        assertNotNull(failure)
        assertEquals(UploadFailureKind.RATE_LIMITED, failure?.kind)
        assertEquals(429, failure?.httpStatusCode)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(1000L, 1000L), sleepRequests)
    }

    @Test
    fun reportsPayloadTooLarge() {
        server.enqueue(MockResponse().setResponseCode(413).setBody("Payload Too Large"))

        val result = upload()
        val failure = result as? UploadResult.Failure
        assertNotNull(failure)
        assertEquals(UploadFailureKind.PAYLOAD_TOO_LARGE, failure?.kind)
        assertEquals(413, failure?.httpStatusCode)
    }

    @Test
    fun reportsExpiredOrInvalidWebhook() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"message":"Unknown Webhook"}""")
        )

        val result = upload()
        val failure = result as? UploadResult.Failure
        assertEquals(UploadFailureKind.NOT_FOUND, failure?.kind)
        assertEquals(404, failure?.httpStatusCode)
    }

    @Test
    fun doesNotFollowRedirects() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://example.invalid/steal")
                .setBody("")
        )

        val result = upload()
        val failure = result as? UploadResult.Failure
        assertNotNull(failure)
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
            unconfigured.upload(
                archive = archive,
                summary = summary,
                senderName = "Test Phone",
                onProgress = { _ -> },
                onStatus = { _ -> }
            )
        }
        val failure = result as? UploadResult.Failure
        assertEquals(UploadFailureKind.INVALID_CONFIGURATION, failure?.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rejectsMissingArchive() {
        archive.delete()

        val result = upload()
        val failure = result as? UploadResult.Failure
        assertEquals(UploadFailureKind.ARCHIVE_UNAVAILABLE, failure?.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun acceptsNoContentSuccessWithoutMessageId() {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = upload()
        assertTrue(result is UploadResult.Success)
        assertNull((result as UploadResult.Success).messageId)
    }
}
