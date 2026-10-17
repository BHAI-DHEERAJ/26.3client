package com.example.htmlbackup

/**
 * Build-time switches for the device report.
 *
 * Everything enabled here is shown in the review dialog before sending and is
 * only sent after the user taps Send. Turn a section off to omit it entirely.
 *
 * Deliberately not available at any setting:
 * - SMS/MMS contents or message metadata (READ_SMS; reserved for the default
 *   SMS app and used for surveillance in this kind of tool),
 * - IMSI, ICCID, SIM serial number, or subscriber IDs (persistent identity
 *   identifiers, restricted by Android, and not needed for a backup),
 * - camera capture.
 */
object ReportConfig {
    /** Connection type, local IP address, VPN state. No extra permission. */
    const val INCLUDE_NETWORK_INFO: Boolean = true

    /** SIM state, carrier name, modem count, roaming state. No extra permission. */
    const val INCLUDE_TELEPHONY_INFO: Boolean = true

    /**
     * The device's own phone number via READ_PHONE_NUMBERS. Many carriers do
     * not provision a number on the SIM, so this is often unavailable; the
     * report says so instead of guessing.
     */
    const val INCLUDE_PHONE_NUMBER: Boolean = true
}
