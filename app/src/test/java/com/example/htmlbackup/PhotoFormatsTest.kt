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
    fun uploadNameUsesIndexPrefixAndSanitizedName() {
        assertEquals(
            "00001_IMG_1234.jpg",
            PhotoFormats.uploadFileName(1, "IMG_1234.jpg", "image/jpeg")
        )
        assertEquals(
            "00042_holiday_photo.png",
            PhotoFormats.uploadFileName(42, "holiday photo.png", "image/png")
        )
    }

    @Test
    fun extensionIsTakenFromMimeTypeNotFileName() {
        assertEquals(
            "00003_photo.webp",
            PhotoFormats.uploadFileName(3, "photo.jpg", "image/webp")
        )
    }

    @Test
    fun traversalAndSpecialCharactersAreNeutralized() {
        val name = PhotoFormats.uploadFileName(7, "../../etc/passwd.jpg", "image/jpeg")
        assertEquals("00007_passwd.jpg", name)
        assertTrue(!name.contains(".."))
        assertTrue(!name.contains("/etc/"))
    }

    @Test
    fun blankOrDotOnlyNamesFallBackToPhoto() {
        assertEquals("00001_photo.jpg", PhotoFormats.uploadFileName(1, null, "image/jpeg"))
        assertEquals("00002_photo.png", PhotoFormats.uploadFileName(2, "   ", "image/png"))
        assertEquals("00003_photo.webp", PhotoFormats.uploadFileName(3, "...webp", "image/webp"))
    }

    @Test
    fun indexesKeepDuplicateDisplayNamesUnique() {
        val first = PhotoFormats.uploadFileName(1, "image.jpg", "image/jpeg")
        val second = PhotoFormats.uploadFileName(2, "image.jpg", "image/jpeg")
        assertNotEquals(first, second)
    }

    @Test
    fun longNamesAreBound() {
        val name = PhotoFormats.uploadFileName(9, "a".repeat(200) + ".png", "image/png")
        val base = name.removePrefix("00009_").removeSuffix(".png")
        assertTrue(base.length <= 60)
    }

    @Test
    fun compressedNamesAreJpegAndDistinctFromOriginals() {
        assertEquals(
            "00005_photo-compressed.jpg",
            PhotoFormats.compressedFileName(5, "photo.png")
        )
        assertNotEquals(
            PhotoFormats.uploadFileName(5, "photo.png", "image/png"),
            PhotoFormats.compressedFileName(5, "photo.png")
        )
    }

    @Test
    fun quarterResolutionNamesAreMarkedAndDistinct() {
        assertEquals(
            "00005_photo-compressed-4x.jpg",
            PhotoFormats.compressedFileName(5, "photo.png", quarterResolution = true)
        )
        assertNotEquals(
            PhotoFormats.compressedFileName(5, "photo.png"),
            PhotoFormats.compressedFileName(5, "photo.png", quarterResolution = true)
        )
    }
}
