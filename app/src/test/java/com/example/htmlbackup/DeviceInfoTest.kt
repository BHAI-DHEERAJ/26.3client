package com.example.htmlbackup

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {

    @Test
    fun trimsAndCollapsesWhitespace() {
        assertEquals("Rahul's Pixel 7", DeviceInfo.sanitize("  Rahul's   Pixel 7  "))
    }

    @Test
    fun fallsBackWhenMissingOrBlank() {
        assertEquals("Android phone", DeviceInfo.sanitize(null))
        assertEquals("Android phone", DeviceInfo.sanitize(""))
        assertEquals("Android phone", DeviceInfo.sanitize("   \t  "))
    }

    @Test
    fun removesControlCharacters() {
        assertEquals("Pixel 7", DeviceInfo.sanitize("Pixel\u0000\u0007 7"))
    }

    @Test
    fun truncatesLongDeviceNames() {
        assertEquals(80, DeviceInfo.sanitize("a".repeat(200)).length)
        assertEquals(80, DeviceInfo.sanitize("b".repeat(79) + " " + "c".repeat(20)).length)
    }

    @Test
    fun preservesUnicodeNames() {
        assertEquals("राहुल का फ़ोन", DeviceInfo.sanitize("राहुल का फ़ोन"))
    }
}
