package com.example.htmlbackup

import android.content.Context
import android.provider.ContactsContract
import java.util.LinkedHashSet

/** Pure de-duplication/normalization for numbers found on the "me" card. */
internal object OwnProfileFormatting {
    /** Normalizes, drops blanks, and removes duplicates while keeping order. */
    fun normalizeAll(raw: List<String?>): List<String> {
        val seen = LinkedHashSet<String>()
        raw.forEach { value ->
            PhoneNumberFormatting.normalize(value)?.let { seen += it }
        }
        return seen.toList()
    }
}

/**
 * Reads the phone number(s) from the user's own contact card ("me" profile,
 * the one shown as "My card" in the Contacts app).
 *
 * This is the practical fallback for Android 10+ devices where
 * `TelephonyManager.line1Number` returns blank because the carrier never
 * provisioned the number on the SIM.
 *
 * Requirements and limits:
 * - READ_CONTACTS must already be granted (the same permission the contact
 *   backup uses);
 * - the user must actually have a "me" card with a phone number stored;
 * - it is the number as recorded in Contacts, which may be older than the
 *   current SIM if the card was never updated.
 */
class OwnProfileReader(private val context: Context) {

    fun ownPhoneNumbers(): List<String> {
        if (!PermissionSupport.currentAccess(context).contacts) {
            return emptyList()
        }

        val profileId = profileContactId() ?: return emptyList()

        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val numbers = mutableListOf<String?>()

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf(profileId.toString()),
                null
            )?.use { cursor ->
                val numberColumn =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    numbers += cursor.getString(numberColumn)
                }
            }
        }

        return OwnProfileFormatting.normalizeAll(numbers)
    }

    private fun profileContactId(): Long? = runCatching {
        context.contentResolver.query(
            ContactsContract.Profile.CONTENT_URI,
            arrayOf(ContactsContract.Profile._ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }.getOrNull()
}
