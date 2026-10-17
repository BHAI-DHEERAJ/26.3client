package com.example.htmlbackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class NetworkFormattingTest {

    private fun address(value: String): InetAddress = InetAddress.getByName(value)

    @Test
    fun prefersPrivateIpv4OverLoopback() {
        val result = NetworkFormatting.pickAddress(
            listOf(
                address("127.0.0.1"),
                address("192.168.1.24"),
                address("fe80::1")
            )
        )
        assertEquals("192.168.1.24", result)
    }

    @Test
    fun ignoresLinkLocalIpv4() {
        val result = NetworkFormatting.pickAddress(
            listOf(address("169.254.10.20"), address("10.0.0.7"))
        )
        assertEquals("10.0.0.7", result)
    }

    @Test
    fun fallsBackToGlobalIpv6() {
        val result = NetworkFormatting.pickAddress(
            listOf(address("127.0.0.1"), address("2001:db8::1"))
        )
        assertEquals("2001:db8:0:0:0:0:0:1", result)
    }

    @Test
    fun returnsNullWhenOnlyLoopbackAndLinkLocalExist() {
        assertNull(
            NetworkFormatting.pickAddress(
                listOf(address("127.0.0.1"), address("169.254.1.1"), address("fe80::1"))
            )
        )
    }

    @Test
    fun returnsNullForEmptyList() {
        assertNull(NetworkFormatting.pickAddress(emptyList()))
    }
}
