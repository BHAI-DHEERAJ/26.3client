package com.example.htmlbackup

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * App, OS, and hardware metadata included in the backup report.
 * No identifiers that require privileged access (IMEI, serial, Android ID)
 * are collected.
 */
data class DeviceProfile(
    val senderName: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val androidRelease: String,
    val sdkInt: Int,
    val buildId: String,
    val securityPatch: String?,
    val supportedAbis: String,
    val locale: String,
    val timeZone: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val packageName: String,
    val capturedAt: Instant
)

/**
 * The name used as the Discord webhook message username, plus the device report
 * payload sent before any personal data.
 *
 * The user-editable device name is read from [Settings.Global.DEVICE_NAME]
 * (Android 8+); if it is unavailable, the manufacturer and model are used.
 */
object DeviceInfo {
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_FIELD_LENGTH = 1000
    private const val FALLBACK_NAME = "Android phone"
    private const val EMBED_COLOR = 0x0F766E

    fun senderName(context: Context): String {
        val configuredName = runCatching {
            Settings.Global.getString(
                context.contentResolver,
                Settings.Global.DEVICE_NAME
            )
        }.getOrNull()

        val modelName = listOf(Build.MANUFACTURER, Build.MODEL)
            .mapNotNull { part -> clean(part, MAX_NAME_LENGTH).takeIf { it.isNotEmpty() } }
            .distinct()
            .joinToString(" ")

        return clean(configuredName, MAX_NAME_LENGTH)
            .ifBlank { modelName }
            .ifBlank { FALLBACK_NAME }
    }

    fun profile(context: Context): DeviceProfile {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        return DeviceProfile(
            senderName = senderName(context),
            manufacturer = clean(Build.MANUFACTURER, MAX_FIELD_LENGTH).ifBlank { "unknown" },
            brand = clean(Build.BRAND, MAX_FIELD_LENGTH).ifBlank { "unknown" },
            model = clean(Build.MODEL, MAX_FIELD_LENGTH).ifBlank { "unknown" },
            device = clean(Build.DEVICE, MAX_FIELD_LENGTH).ifBlank { "unknown" },
            product = clean(Build.PRODUCT, MAX_FIELD_LENGTH).ifBlank { "unknown" },
            androidRelease = clean(Build.VERSION.RELEASE, MAX_FIELD_LENGTH).ifBlank { "unknown" },
            sdkInt = Build.VERSION.SDK_INT,
            buildId = clean(Build.ID, MAX_FIELD_LENGTH).ifBlank { "unknown" },
            securityPatch = clean(Build.VERSION.SECURITY_PATCH, MAX_FIELD_LENGTH)
                .takeIf { it.isNotEmpty() },
            supportedAbis = clean(Build.SUPPORTED_ABIS.joinToString(", "), MAX_FIELD_LENGTH)
                .ifBlank { "unknown" },
            locale = clean(Locale.getDefault().toLanguageTag(), MAX_FIELD_LENGTH)
                .ifBlank { "unknown" },
            timeZone = clean(TimeZone.getDefault().id, MAX_FIELD_LENGTH).ifBlank { "unknown" },
            appVersionName = clean(packageInfo?.versionName, MAX_FIELD_LENGTH)
                .ifBlank { "unknown" },
            appVersionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
            packageName = context.packageName,
            capturedAt = Instant.now()
        )
    }

    /** Normalizes a device name for a Discord username. Pure and unit tested. */
    internal fun sanitize(raw: String?): String =
        clean(raw, MAX_NAME_LENGTH).ifBlank { FALLBACK_NAME }

    /** Collapses whitespace and control characters, then caps the length. */
    internal fun clean(raw: String?, maxLength: Int): String =
        raw
            ?.replace(Regex("[\\p{Cntrl}]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(maxLength)
            ?.trim()
            .orEmpty()

    private fun fieldValue(raw: String?): String =
        clean(raw, MAX_FIELD_LENGTH).ifBlank { "unknown" }

    /**
     * Builds the first Discord message: app version, Android version, model,
     * hardware, locale, and what is about to be sent. No personal data
     * (photos, contacts, phone numbers, emails) is included here.
     */
    fun reportPayload(profile: DeviceProfile, plan: BackupPlan): JSONObject {
        fun field(name: String, value: String, inline: Boolean = true): JSONObject =
            JSONObject().apply {
                put("name", name)
                put("value", fieldValue(value))
                put("inline", inline)
            }

        val photoAccess = when {
            !plan.request.includePhotos -> "not included"
            plan.request.photoAccessPartial -> "selected photos only"
            else -> "full library"
        }

        val fields = JSONArray().apply {
            put(field("Device", profile.senderName))
            put(field("Manufacturer", profile.manufacturer))
            put(field("Brand", profile.brand))
            put(field("Model", profile.model))
            put(field("Android", "${profile.androidRelease} (SDK ${profile.sdkInt})"))
            put(field("Security patch", profile.securityPatch ?: "unknown"))
            put(field("Build ID", profile.buildId))
            put(field("Device codename", profile.device))
            put(field("Product", profile.product))
            put(field("ABI", profile.supportedAbis))
            put(field("Locale", profile.locale))
            put(field("Time zone", profile.timeZone))
            put(
                field(
                    "App",
                    "${BuildConfig.APPLICATION_ID} ${profile.appVersionName} " +
                        "(versionCode ${profile.appVersionCode})"
                )
            )
            put(field("Photos", "${plan.photos.size} files, ${Formats.bytes(plan.photoBytes)}"))
            put(field("Photo access", photoAccess))
            put(
                field(
                    "Contacts",
                    if (plan.contactsIncluded) {
                        "${plan.contactCount} records (contacts.json)"
                    } else {
                        "not included"
                    }
                )
            )
            put(field("Started", DateTimeFormatter.ISO_INSTANT.format(plan.createdAt), false))
        }

        val embed = JSONObject().apply {
            put("title", "HTML Backup device report")
            put("color", EMBED_COLOR)
            put("timestamp", DateTimeFormatter.ISO_INSTANT.format(profile.capturedAt))
            put("fields", fields)
            put("footer", JSONObject().put("text", "HTML Backup"))
        }

        return JSONObject().apply {
            put("content", "Backup started on ${profile.senderName}.")
            put("embeds", JSONArray().put(embed))
            put("allowed_mentions", JSONObject().put("parse", JSONArray()))
        }
    }
}
