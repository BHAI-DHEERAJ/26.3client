package com.example.htmlbackup

import okhttp3.HttpUrl

/**
 * Destination configuration for HTML Backup.
 *
 * The effective URL comes from `BuildConfig.WEBHOOK_URL`, which is injected at
 * build time from one of:
 *
 *   1. `-PhtmlBackup.webhookUrl=...`
 *   2. the `HTML_BACKUP_WEBHOOK_URL` environment variable
 *      (CI reads it from the `DISCORD_WEBHOOK_URL` GitHub Actions secret)
 *
 * If neither is present, [WEBHOOK_URL] is the placeholder below, [isConfigured]
 * is false, and the app disables Send. The real URL is intentionally not kept
 * in this file so it cannot be committed to a public repository.
 *
 * SECURITY NOTE: a Discord webhook URL is a bearer secret. Anyone who has it can
 * post to that channel. Any APK built with a real URL contains it and it can be
 * extracted from the APK, so keep such builds private and revoke/rotate the
 * webhook if it is exposed.
 *
 * The app itself never displays the URL: the review dialog only shows the host.
 */
object WebhookConfig {
    private const val PLACEHOLDER_URL: String =
        "https://discord.com/api/webhooks/000000000000000000/REPLACE_WITH_YOUR_WEBHOOK_TOKEN"

    /** The effective webhook URL: build-time value, or the placeholder. */
    val WEBHOOK_URL: String = BuildConfig.WEBHOOK_URL.trim().ifBlank { PLACEHOLDER_URL }

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
