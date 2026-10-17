package com.example.htmlbackup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ContactJsonEncoderTest {

    @Test
    fun encodesCountsAndAllowsMissingPhoneAndEmailFields() {
        val export = ContactExport(
            contacts = listOf(
                ContactRecord(
                    name = "Alice Example",
                    phoneNumbers = listOf("+1 555 0100", "+1 555 0101"),
                    emailAddresses = listOf("alice@example.com")
                ),
                ContactRecord(
                    name = "Bob Without Details",
                    phoneNumbers = emptyList(),
                    emailAddresses = emptyList()
                ),
                ContactRecord(
                    name = "",
                    phoneNumbers = listOf("12345"),
                    emailAddresses = emptyList()
                )
            ),
            exportedAt = Instant.parse("2026-10-17T12:00:00Z")
        )

        val json = JSONObject(String(ContactJsonEncoder.encode(export), Charsets.UTF_8))

        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("HTML Backup", json.getString("app"))
        assertEquals("Android Contacts Provider", json.getString("source"))
        assertEquals("2026-10-17T12:00:00Z", json.getString("exportedAt"))
        assertEquals(3, json.getInt("contactCount"))
        assertEquals(3, json.getInt("phoneCount"))
        assertEquals(1, json.getInt("emailCount"))

        val contacts = json.getJSONArray("contacts")
        assertEquals(3, contacts.length())

        val alice = contacts.getJSONObject(0)
        assertEquals("Alice Example", alice.getString("name"))
        assertEquals(2, alice.getJSONArray("phones").length())
        assertEquals("alice@example.com", alice.getJSONArray("emails").getString(0))

        val bob = contacts.getJSONObject(1)
        assertEquals("Bob Without Details", bob.getString("name"))
        assertEquals(0, bob.getJSONArray("phones").length())
        assertEquals(0, bob.getJSONArray("emails").length())
    }

    @Test
    fun outputIsValidUtf8Json() {
        val export = ContactExport(
            contacts = listOf(
                ContactRecord("नमस्ते", listOf("+91 90000 00000"), listOf("test@example.com"))
            ),
            exportedAt = Instant.parse("2026-01-02T03:04:05.123Z")
        )

        val text = String(ContactJsonEncoder.encode(export), Charsets.UTF_8)
        val json = JSONObject(text)
        assertEquals("नमस्ते", json.getJSONArray("contacts").getJSONObject(0).getString("name"))
        assertTrue(text.contains("test@example.com"))
    }
}
