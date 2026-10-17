package com.example.htmlbackup

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validates a Discord webhook URL before it is used.
 *
 * Webhook URLs are bearer secrets. They are never logged and never shown in
 * the UI. Validation rejects lookalike hosts, plain HTTP, redirects through
 * unusual ports, and malformed webhook paths.
 */
internal object WebhookUrlValidator {
    private val ALLOWED_HOSTS = setOf(
        "discord.com",
        "canary.discord.com",
        "ptb.discord.com"
    )

    private val WEBHOOK_ID = Regex("^[0-9]{17,20}$")
    private val WEBHOOK_TOKEN = Regex("^[A-Za-z0-9._-]{30,128}$")

    private const val PLACEHOLDER_ID = "000000000000000000"
    private const val PLACEHOLDER_TOKEN = "REPLACE_WITH_YOUR_WEBHOOK_TOKEN"

    /** Returns a validated URL, or null if the value must not be used. */
    fun validatedUrl(raw: String?): HttpUrl? {
        if (raw.isNullOrBlank()) {
            return null
        }

        val trimmed = raw.trim()
        if (trimmed.any { it.isWhitespace() } || '%' in trimmed) {
            return null
        }

        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") {
            return null
        }
        if (url.host !in ALLOWED_HOSTS) {
            return null
        }
        if (url.port != 443) {
            return null
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return null
        }
        if (url.query != null || url.fragment != null) {
            return null
        }

        val segments = url.pathSegments
        if (segments.size != 4 || segments[0] != "api" || segments[1] != "webhooks") {
            return null
        }

        val id = segments[2]
        val token = segments[3]
        if (!WEBHOOK_ID.matches(id) || id == PLACEHOLDER_ID || id.all { it == '0' }) {
            return null
        }
        if (!WEBHOOK_TOKEN.matches(token) || token == PLACEHOLDER_TOKEN) {
            return null
        }

        return url
    }
}
