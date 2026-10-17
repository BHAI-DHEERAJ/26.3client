package com.example.htmlbackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberFormattingTest {

    @Test
    fun keepsValidInternationalNumbers() {
        assertEquals("+91 98765 43210", PhoneNumberFormatting.normalize("+91 98765 43210"))
        assertEquals("+1 (555) 010-0100", PhoneNumberFormatting.normalize("+1 (555) 010-0100"))
        assertEquals("9876543210", PhoneNumberFormatting.normalize(" 9876543210 "))
    }

    @Test
    fun trimsWhitespaceAndControlCharacters() {
        assertEquals("+44 20 7946 0958", PhoneNumberFormatting.normalize("  +44\n20\t7946 0958  "))
    }

    @Test
    fun rejectsBlanksAndPlaceholders() {
        assertNull(PhoneNumberFormatting.normalize(null))
        assertNull(PhoneNumberFormatting.normalize(""))
        assertNull(PhoneNumberFormatting.normalize("   "))
        assertNull(PhoneNumberFormatting.normalize("null"))
        assertNull(PhoneNumberFormatting.normalize("unknown"))
    }

    @Test
    fun rejectsValuesThatAreNotPhoneNumbers() {
        assertNull(PhoneNumberFormatting.normalize("call me maybe"))
        assertNull(PhoneNumberFormatting.normalize("12345"))
        assertNull(PhoneNumberFormatting.normalize("+<script>alert(1)</script>"))
        assertNull(PhoneNumberFormatting.normalize("1".repeat(40)))
    }

    @Test
    fun rejectsTooFewDigits() {
        assertNull(PhoneNumberFormatting.normalize("+--"))
        assertNull(PhoneNumberFormatting.normalize("(+)"))
        assertNull(PhoneNumberFormatting.normalize("112"))
    }

    @Test
    fun acceptsShortestPlausibleNumber() {
        assertEquals("123456", PhoneNumberFormatting.normalize("123456"))
    }

    @Test
    fun rejectsNumbersLongerThanE164() {
        assertNull(PhoneNumberFormatting.normalize("+1234567890123456"))
    }
}
