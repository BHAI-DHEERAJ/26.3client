package com.example.htmlbackup

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A single exported contact. Missing fields are represented by empty lists. */
data class ContactRecord(
    val name: String,
    val phoneNumbers: List<String>,
    val emailAddresses: List<String>
)

/** All contacts read in one pass, plus the export timestamp. */
data class ContactExport(
    val contacts: List<ContactRecord>,
    val exportedAt: Instant
) {
    val phoneCount: Int
        get() = contacts.sumOf { it.phoneNumbers.size }

    val emailCount: Int
        get() = contacts.sumOf { it.emailAddresses.size }
}

/** Serializes contacts as UTF-8 JSON. Kept pure for unit testing. */
internal object ContactJsonEncoder {
    private const val SCHEMA_VERSION = 1

    fun encode(export: ContactExport): ByteArray {
        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("app", "HTML Backup")
            put("exportedAt", DateTimeFormatter.ISO_INSTANT.format(export.exportedAt))
            put("source", "Android Contacts Provider")
            put("contactCount", export.contacts.size)
            put("phoneCount", export.phoneCount)
            put("emailCount", export.emailCount)
        }

        val contactArray = JSONArray()
        export.contacts.forEach { contact ->
            contactArray.put(
                JSONObject().apply {
                    put("name", contact.name)
                    put("phones", JSONArray(contact.phoneNumbers))
                    put("emails", JSONArray(contact.emailAddresses))
                }
            )
        }
        root.put("contacts", contactArray)

        return root.toString(2).toByteArray(Charsets.UTF_8)
    }
}

/**
 * Reads actual contact records from the Android Contacts Provider.
 *
 * READ_CONTACTS must already be granted; this class never attempts to work
 * around a denied permission. Contact values are never logged.
 */
class ContactExporter(private val context: Context) {

    fun readContacts(): ContactExport {
        if (!PermissionSupport.currentAccess(context).contacts) {
            throw BackupException("Contacts access is not granted.")
        }

        val contactRows = LinkedHashMap<Long, MutableContact>()
        val contactProjection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )

        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            contactProjection,
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameColumn = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = if (nameColumn >= 0) {
                    cursor.getString(nameColumn).orEmpty().trim()
                } else {
                    ""
                }
                contactRows[id] = MutableContact(name)
            }
        } ?: throw BackupException("Contacts could not be read. Check access and try again.")

        val dataProjection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1
        )
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            dataProjection,
            "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
            ),
            null
        )?.use { cursor ->
            val contactIdColumn =
                cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val valueColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)

            while (cursor.moveToNext()) {
                if (cursor.isNull(contactIdColumn)) {
                    continue
                }
                val contact = contactRows[cursor.getLong(contactIdColumn)] ?: continue
                val mimeType = cursor.getString(mimeColumn) ?: continue
                val value = cursor.getString(valueColumn)?.trim().orEmpty()
                if (value.isEmpty()) {
                    continue
                }

                when (mimeType) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                        contact.phoneNumbers += value

                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                        contact.emailAddresses += value
                }
            }
        } ?: throw BackupException(
            "Contact details could not be read. Check access and try again."
        )

        val records = contactRows.values.map { contact ->
            ContactRecord(
                name = contact.name,
                phoneNumbers = contact.phoneNumbers.toList(),
                emailAddresses = contact.emailAddresses.toList()
            )
        }

        return ContactExport(contacts = records, exportedAt = Instant.now())
    }

    /** Writes contacts.json into the open ZIP and returns its uncompressed size. */
    fun writeJsonToZip(zipOut: ZipOutputStream, export: ContactExport): Long {
        val json = ContactJsonEncoder.encode(export)
        zipOut.putNextEntry(ZipEntry(ENTRY_NAME))
        zipOut.write(json)
        zipOut.closeEntry()
        return json.size.toLong()
    }

    private class MutableContact(val name: String) {
        val phoneNumbers: LinkedHashSet<String> = LinkedHashSet()
        val emailAddresses: LinkedHashSet<String> = LinkedHashSet()
    }

    private companion object {
        const val ENTRY_NAME = "contacts.json"
    }
}
