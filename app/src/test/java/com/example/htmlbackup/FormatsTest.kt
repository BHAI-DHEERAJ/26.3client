package com.example.htmlbackup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Locale

class FormatsTest {

    private lateinit var originalLocale: Locale

    @Before
    fun useStableLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun archiveNameIsDeterministicUtcAndFilesystemSafe() {
        val name = Formats.archiveName(Instant.parse("2026-10-17T12:34:56.789Z"))
        assertEquals("html-backup-20261017-123456-789.zip", name)
        assertEquals(
            name,
            Formats.archiveName(Instant.parse("2026-10-17T12:34:56.789Z"))
        )
    }

    @Test
    fun bytesUsesReadableUnits() {
        assertEquals("0 B", Formats.bytes(0))
        assertEquals("1023 B", Formats.bytes(1023))
        assertEquals("1.0 KB", Formats.bytes(1024))
        assertEquals("1.5 KB", Formats.bytes(1536))
        assertEquals("1.0 MB", Formats.bytes(1024L * 1024L))
        assertEquals("20.0 MB", Formats.bytes(20L * 1024L * 1024L))
    }
}
