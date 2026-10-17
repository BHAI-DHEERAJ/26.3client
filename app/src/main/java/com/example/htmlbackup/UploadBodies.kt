package com.example.htmlbackup

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Request bodies that stream bytes without copying whole files into memory.
 * They are repeatable so a rate-limited request can be retried safely.
 */
class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mediaType: MediaType,
    private val declaredLength: Long,
    private val onProgress: (Int) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = declaredLength.takeIf { it > 0L } ?: -1L

    override fun writeTo(sink: BufferedSink) {
        val input = try {
            resolver.openInputStream(uri)
        } catch (e: SecurityException) {
            null
        } catch (e: IOException) {
            null
        } ?: throw IOException("The selected photo could not be opened.")

        input.use { stream ->
            copyWithProgress(stream, sink, contentLength(), onProgress)
        }
    }
}

class FileRequestBody(
    private val file: File,
    private val mediaType: MediaType,
    private val onProgress: (Int) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        file.inputStream().use { stream ->
            copyWithProgress(stream, sink, file.length(), onProgress)
        }
    }
}

class BytesRequestBody(
    private val bytes: ByteArray,
    private val mediaType: MediaType,
    private val onProgress: ((Int) -> Unit)? = null
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        if (bytes.isEmpty()) {
            onProgress?.invoke(100)
            return
        }

        var written = 0
        var lastPercent = -1
        while (written < bytes.size) {
            val chunk = minOf(COPY_BUFFER_SIZE, bytes.size - written)
            sink.write(bytes, written, chunk)
            written += chunk
            val percent = ((written * 100L) / bytes.size).toInt().coerceIn(0, 100)
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress?.invoke(percent)
            }
        }
        sink.flush()
        onProgress?.invoke(100)
    }
}

private fun copyWithProgress(
    input: InputStream,
    sink: BufferedSink,
    totalBytes: Long,
    onProgress: (Int) -> Unit
) {
    val buffer = ByteArray(COPY_BUFFER_SIZE)
    var copied = 0L
    var lastPercent = -1

    while (true) {
        val read = input.read(buffer)
        if (read == -1) {
            break
        }
        sink.write(buffer, 0, read)
        copied += read

        if (totalBytes > 0L) {
            val percent = ((copied * 100L) / totalBytes).toInt().coerceIn(0, 100)
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
        }
    }

    sink.flush()
    onProgress(100)
}

private const val COPY_BUFFER_SIZE = 64 * 1024
