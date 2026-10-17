package com.example.htmlbackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFormatsTest {

    @Test
    fun supportsExpectedMimeTypes() {
        assertEquals(
            listOf("image/jpeg", "image/png", "image/webp"),
            PhotoFormats.supportedMimeTypes
        )
    }

    @Test
    fun extensionsFollowMimeType() {
        assertEquals("jpg", PhotoFormats.extensionFor("image/jpeg"))
        assertEquals("png", PhotoFormats.extensionFor("image/png"))
        assertEquals("webp", PhotoFormats.extensionFor("image/webp"))
    }

    @Test
    fun entryNameUsesIndexPrefixAndSanitizedName() {
        assertEquals(
            "photos/00001_IMG_1234.jpg",
            PhotoFormats.zipEntryName(1, "IMG_1234.jpg", "image/jpeg")
        )
        assertEquals(
            "photos/00042_holiday_photo.png",
            PhotoFormats.zipEntryName(42, "holiday photo.png", "image/png")
        )
    }

    @Test
    fun extensionIsTakenFromMimeTypeNotFileName() {
        assertEquals(
            "photos/00003_photo.webp",
            PhotoFormats.zipEntryName(3, "photo.jpg", "image/webp")
        )
    }

    @Test
    fun traversalAndSpecialCharactersAreNeutralized() {
        val entry = PhotoFormats.zipEntryName(7, "../../etc/passwd.jpg", "image/jpeg")
        assertEquals("photos/00007_passwd.jpg", entry)
        assertTrue(!entry.contains(".."))
        assertTrue(!entry.contains("/etc/"))
    }

    @Test
    fun blankOrDotOnlyNamesFallBackToPhoto() {
        assertEquals(
            "photos/00001_photo.jpg",
            PhotoFormats.zipEntryName(1, null, "image/jpeg")
        )
        assertEquals(
            "photos/00002_photo.png",
            PhotoFormats.zipEntryName(2, "   ", "image/png")
        )
        assertEquals(
            "photos/00003_photo.webp",
            PhotoFormats.zipEntryName(3, "...webp", "image/webp")
        )
    }

    @Test
    fun indexesKeepDuplicateDisplayNamesUnique() {
        val first = PhotoFormats.zipEntryName(1, "image.jpg", "image/jpeg")
        val second = PhotoFormats.zipEntryName(2, "image.jpg", "image/jpeg")
        assertNotEquals(first, second)
    }

    @Test
    fun longNamesAreBound() {
        val entry = PhotoFormats.zipEntryName(9, "a".repeat(200) + ".png", "image/png")
        val base = entry.removePrefix("photos/00009_").removeSuffix(".png")
        assertTrue(base.length <= 60)
    }
}
