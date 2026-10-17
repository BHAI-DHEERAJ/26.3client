package com.example.htmlbackup

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small, pure formatting helpers used by the UI and the ZIP writer. */
internal object Formats {
    private val ARCHIVE_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneOffset.UTC)

    /** Deterministic, filesystem-safe archive name in UTC. */
    fun archiveName(createdAt: Instant): String = "html-backup-${ARCHIVE_TIMESTAMP.format(createdAt)}.zip"

    /** Human-readable byte count, for example "4.2 MB". */
    fun bytes(size: Long): String {
        if (size < 1024L) {
            return "$size B"
        }

        var value = size.toDouble() / 1024.0
        val units = arrayOf("KB", "MB", "GB", "TB")
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }

        return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }
}
