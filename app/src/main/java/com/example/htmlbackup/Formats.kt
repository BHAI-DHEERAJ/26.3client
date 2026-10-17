package com.example.htmlbackup

import java.util.Locale

/** Small, pure formatting helpers used by the UI. */
internal object Formats {
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
