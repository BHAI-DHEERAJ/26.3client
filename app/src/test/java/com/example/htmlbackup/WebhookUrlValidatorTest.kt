package com.example.htmlbackup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebhookUrlValidatorTest {

    private val token = "A".repeat(64)
    private val validUrl = "https://discord.com/api/webhooks/123456789012345678/$token"

    @Test
    fun acceptsCanonicalDiscordWebhook() {
        assertNotNull(WebhookUrlValidator.validatedUrl(validUrl))
    }

    @Test
    fun acceptsCanaryHost() {
        assertNotNull(
            WebhookUrlValidator.validatedUrl(
                "https://canary.discord.com/api/webhooks/123456789012345678/$token"
            )
        )
    }

    @Test
    fun rejectsNullEmptyAndGarbage() {
        assertNull(WebhookUrlValidator.validatedUrl(null))
        assertNull(WebhookUrlValidator.validatedUrl(""))
        assertNull(WebhookUrlValidator.validatedUrl("   "))
        assertNull(WebhookUrlValidator.validatedUrl("not a url"))
    }

    @Test
    fun rejectsPlainHttp() {
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "http://discord.com/api/webhooks/123456789012345678/$token"
            )
        )
    }

    @Test
    fun rejectsLookalikeHosts() {
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://discord.com.evil.example/api/webhooks/123456789012345678/$token"
            )
        )
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://evil-discord.com/api/webhooks/123456789012345678/$token"
            )
        )
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://discord.com@evil.example/api/webhooks/123456789012345678/$token"
            )
        )
    }

    @Test
    fun rejectsNonStandardPorts() {
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://discord.com:8443/api/webhooks/123456789012345678/$token"
            )
        )
    }

    @Test
    fun rejectsQueryFragmentAndTrailingSlash() {
        assertNull(WebhookUrlValidator.validatedUrl("$validUrl?wait=true"))
        assertNull(WebhookUrlValidator.validatedUrl("$validUrl#fragment"))
        assertNull(WebhookUrlValidator.validatedUrl("$validUrl/"))
    }

    @Test
    fun rejectsUserInfo() {
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://user:password@discord.com/api/webhooks/123456789012345678/$token"
            )
        )
    }

    @Test
    fun rejectsInvalidWebhookIdAndToken() {
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://discord.com/api/webhooks/not-a-number/$token"
            )
        )
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://discord.com/api/webhooks/123456789012345678/short"
            )
        )
    }

    @Test
    fun rejectsPlaceholderAndAllZeroId() {
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://discord.com/api/webhooks/000000000000000000/REPLACE_WITH_YOUR_WEBHOOK_TOKEN"
            )
        )
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://discord.com/api/webhooks/000000000000000000/$token"
            )
        )
    }

    @Test
    fun rejectsEncodedPathSegments() {
        assertFalse(
            WebhookUrlValidator.validatedUrl(
                "https://discord.com/api/webhooks/123456789012345678/${token}%2Fextra"
            ) != null
        )
    }

    @Test
    fun rejectsMissingApiPrefix() {
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://discord.com/webhooks/123456789012345678/$token"
            )
        )
        assertNull(
            WebhookUrlValidator.validatedUrl(
                "https://discord.com/api/v10/webhooks/123456789012345678/$token"
            )
        )
    }
}
