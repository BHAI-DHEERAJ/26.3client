package com.example.htmlbackup

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Connection facts that need no runtime permission. */
data class NetworkFacts(
    val transport: String,
    val localIp: String?,
    val vpnActive: Boolean,
    val metered: Boolean?
) {
    companion object {
        val UNAVAILABLE = NetworkFacts(
            transport = "unavailable",
            localIp = null,
            vpnActive = false,
            metered = null
        )
    }
}

/**
 * Reports the active connection type, the device's local (private) IP address,
 * and whether a VPN is active.
 *
 * Only local addresses are read. The public IP is not collected: the receiver
 * sees it at the transport level already, and looking it up would require an
 * extra request to a third party.
 *
 * Wi-Fi SSID is intentionally not read, because Android requires location
 * permission for it and a backup tool has no business asking for location.
 */
class NetworkReporter(private val context: Context) {

    fun facts(): NetworkFacts {
        if (!ReportConfig.INCLUDE_NETWORK_INFO) {
            return NetworkFacts.UNAVAILABLE
        }

        val manager = runCatching {
            context.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return NetworkFacts.UNAVAILABLE

        val active = runCatching { manager.activeNetwork }.getOrNull()
        val capabilities = active?.let { network ->
            runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
        }
        val linkProperties = active?.let { network ->
            runCatching { manager.getLinkProperties(network) }.getOrNull()
        }

        val vpnActive = runCatching {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)

        return NetworkFacts(
            transport = describeTransport(capabilities),
            localIp = pickLocalAddress(linkProperties),
            vpnActive = vpnActive,
            metered = capabilities?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
        )
    }

    private fun describeTransport(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) {
            return "offline"
        }

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "connected"
        }
    }

    private fun pickLocalAddress(linkProperties: LinkProperties?): String? {
        val addresses = linkProperties?.linkAddresses?.map { it.address } ?: return null
        return NetworkFormatting.pickAddress(addresses)
    }
}

/** Pure address-selection rules, unit tested off-device. */
internal object NetworkFormatting {
    /** Prefers a routable IPv4 address, then a global IPv6 address. */
    fun pickAddress(addresses: List<InetAddress>): String? {
        val ipv4 = addresses.firstOrNull { address ->
            address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
        }
        if (ipv4 != null) {
            return ipv4.hostAddress
        }

        val ipv6 = addresses.firstOrNull { address ->
            address is Inet6Address &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isAnyLocalAddress
        }
        return ipv6?.hostAddress?.substringBefore('%')
    }
}
