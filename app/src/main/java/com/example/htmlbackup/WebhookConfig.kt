package com.example.htmlbackup

import okhttp3.HttpUrl

/**
 * Destination configuration for HTML Backup.
 *
 * Replace [WEBHOOK_URL] with your own Discord webhook before building.
 *
 * SECURITY NOTE: a Discord webhook URL is a bearer secret. Anyone who has the
 * URL can post to that channel. It is embedded in the APK by design because
 * this project keeps configuration in Kotlin source code, so do not publish
 * a build that contains a real webhook. Use a dedicated channel, keep the APK
 * private, and revoke/rotate the webhook if it is exposed.
 *
 * Keep this value out of screenshots, issue reports, logs, and version control
 * history where possible. The app itself never displays the URL: the review
 * dialog only shows the host.
 */
object WebhookConfig {
    const val WEBHOOK_URL: String =
        "https://discord.com/api/webhooks/000000000000000000/REPLACE_WITH_YOUR_WEBHOOK_TOKEN"

    /** True only when the configured URL passes strict HTTPS validation. */
    val isConfigured: Boolean
        get() = WebhookUrlValidator.validatedUrl(WEBHOOK_URL) != null

    /** A UI-safe destination label. Never includes the webhook token or path. */
    val destinationLabel: String
        get() = WebhookUrlValidator.validatedUrl(WEBHOOK_URL)?.host?.let { "Discord ($it)" }
            ?: "Discord (not configured)"

    /** The validated endpoint, used only by [WebhookUploader]. */
    internal fun uploadUrl(): HttpUrl? = WebhookUrlValidator.validatedUrl(WEBHOOK_URL)
}
