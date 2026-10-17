package com.example.htmlbackup

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The name used as the Discord webhook message username, so backups arriving in
 * the channel are labelled with the phone they came from.
 *
 * The user-editable device name is read from [Settings.Global.DEVICE_NAME]
 * (Android 8+); if it is unavailable, the manufacturer and model are used.
 * The name is not secret, but it is device metadata that Discord will see.
 */
object DeviceInfo {
    private const val MAX_NAME_LENGTH = 80
    private const val FALLBACK_NAME = "Android phone"

    fun senderName(context: Context): String {
        val configuredName = runCatching {
            Settings.Global.getString(
                context.contentResolver,
                Settings.Global.DEVICE_NAME
            )
        }.getOrNull()

        val modelName = listOf(Build.MANUFACTURER, Build.MODEL)
            .mapNotNull { part -> part?.trim()?.takeIf { it.isNotEmpty() && it != Build.UNKNOWN } }
            .distinct()
            .joinToString(" ")

        return sanitize(configuredName?.takeIf { it.isNotBlank() } ?: modelName)
    }

    /** Normalizes a device name for a Discord username. Pure and unit tested. */
    internal fun sanitize(raw: String?): String {
        val cleaned = raw
            ?.replace(Regex("[\\p{Cntrl}]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_NAME_LENGTH)
            ?.trim()
            .orEmpty()

        return cleaned.ifBlank { FALLBACK_NAME }
    }
}
