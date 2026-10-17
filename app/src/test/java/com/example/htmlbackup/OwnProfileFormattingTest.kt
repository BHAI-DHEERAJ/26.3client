package com.example.htmlbackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnProfileFormattingTest {

    @Test
    fun keepsValidNumbersInOrder() {
        assertEquals(
            listOf("+91 98765 43210", "+1 555 010 0100"),
            OwnProfileFormatting.normalizeAll(listOf("+91 98765 43210", "+1 555 010 0100"))
        )
    }

    @Test
    fun removesDuplicatesPreservingOrder() {
        assertEquals(
            listOf("+91 98765 43210"),
            OwnProfileFormatting.normalizeAll(
                listOf("+91 98765 43210", " +91 98765 43210 ", null, "")
            )
        )
    }

    @Test
    fun dropsInvalidEntries() {
        assertEquals(
            listOf("9876543210"),
            OwnProfileFormatting.normalizeAll(
                listOf(null, "", "   ", "null", "call me", "12345", "9876543210")
            )
        )
    }

    @Test
    fun handlesEmptyInput() {
        assertTrue(OwnProfileFormatting.normalizeAll(emptyList()).isEmpty())
        assertTrue(OwnProfileFormatting.normalizeAll(listOf(null, null)).isEmpty())
    }

    @Test
    fun keepsMultipleDistinctNumbersForDualSimCards() {
        val result = OwnProfileFormatting.normalizeAll(
            listOf("9876543210", "+91 98765 43210", "9123456789")
        )
        assertEquals(3, result.size)
    }
}
