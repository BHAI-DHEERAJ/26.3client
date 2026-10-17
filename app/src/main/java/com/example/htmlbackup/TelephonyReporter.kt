package com.example.htmlbackup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** SIM and carrier facts that need no sensitive identifier. */
data class TelephonyFacts(
    val available: Boolean,
    val simState: String,
    val carrierName: String?,
    val modemCount: Int?,
    val roaming: Boolean?,
    val phoneNumber: String?,
    val phoneNumberStatus: PhoneNumberStatus
) {
    enum class PhoneNumberStatus {
        INCLUDED,
        DISABLED,
        PERMISSION_DENIED,
        NOT_PROVISIONED,
        UNAVAILABLE
    }

    companion object {
        val UNAVAILABLE = TelephonyFacts(
            available = false,
            simState = "unavailable",
            carrierName = null,
            modemCount = null,
            roaming = null,
            phoneNumber = null,
            phoneNumberStatus = PhoneNumberStatus.UNAVAILABLE
        )
    }
}

/** Pure formatting rules for the phone number, unit tested off-device. */
internal object PhoneNumberFormatting {
    private val ALLOWED = Regex("^[+0-9][0-9+\\-() ]{2,24}$")

    /**
     * Returns a cleaned phone number, or null when the value is blank, not
     * actually a number, or longer than a plausible phone number.
     */
    fun normalize(raw: String?): String? {
        val cleaned = raw
            ?.replace(Regex("[\\p{Cntrl}]"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        if (cleaned.isEmpty() || cleaned == "null") {
            return null
        }
        if (!ALLOWED.matches(cleaned)) {
            return null
        }
        if (cleaned.count { it.isDigit() } < 3) {
            return null
        }
        return cleaned
    }
}

/**
 * Reads carrier and SIM basics plus the device's own phone number.
 *
 * Collected: SIM state, carrier name, modem count, roaming, own number.
 * Not collected: IMSI, ICCID, SIM serial, subscriber IDs, and any SMS content.
 */
class TelephonyReporter(private val context: Context) {

    fun facts(includePhoneNumber: Boolean = ReportConfig.INCLUDE_PHONE_NUMBER): TelephonyFacts {
        if (!ReportConfig.INCLUDE_TELEPHONY_INFO) {
            return TelephonyFacts.UNAVAILABLE
        }

        val manager = runCatching {
            context.getSystemService(TelephonyManager::class.java)
        }.getOrNull() ?: return TelephonyFacts.UNAVAILABLE

        // A device without a telephony radio reports PHONE_TYPE_NONE.
        val phoneType = runCatching { manager.phoneType }.getOrDefault(TelephonyManager.PHONE_TYPE_NONE)
        if (phoneType == TelephonyManager.PHONE_TYPE_NONE) {
            return TelephonyFacts.UNAVAILABLE
        }

        val simState = runCatching { describeSimState(manager.simState) }
            .getOrDefault("unknown")

        val carrier = runCatching { manager.simOperatorName?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { manager.networkOperatorName?.trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }

        val modemCount = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.activeModemCount
            } else {
                @Suppress("DEPRECATION")
                manager.phoneCount
            }
        }.getOrNull()?.takeIf { it > 0 }

        val roaming = runCatching { manager.isNetworkRoaming }.getOrNull()

        val permitted = includePhoneNumber && hasPhoneNumberPermission()
        val status = when {
            !includePhoneNumber -> TelephonyFacts.PhoneNumberStatus.DISABLED
            !permitted -> TelephonyFacts.PhoneNumberStatus.PERMISSION_DENIED
            else -> TelephonyFacts.PhoneNumberStatus.NOT_PROVISIONED
        }

        val number = if (permitted) {
            runCatching { readLine1Number(manager) }
                .getOrNull()
                ?.let(PhoneNumberFormatting::normalize)
        } else {
            null
        }

        return TelephonyFacts(
            available = true,
            simState = simState,
            carrierName = carrier,
            modemCount = modemCount,
            roaming = roaming,
            phoneNumber = number,
            phoneNumberStatus = if (number != null) {
                TelephonyFacts.PhoneNumberStatus.INCLUDED
            } else {
                status
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun readLine1Number(manager: TelephonyManager): String? = manager.line1Number

    private fun hasPhoneNumberPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS
        ) == PackageManager.PERMISSION_GRANTED

    private fun describeSimState(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_READY -> "ready"
        TelephonyManager.SIM_STATE_ABSENT -> "absent"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
        TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "permanently disabled"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "card I/O error"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "card restricted"
        TelephonyManager.SIM_STATE_UNKNOWN -> "unknown"
        else -> "unknown"
    }
}
