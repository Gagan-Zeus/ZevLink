package com.zevclip.sender

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object LocalNetworkAccess {
    fun canReachLocalPeers(connectivityManager: ConnectivityManager): Boolean {
        return hasLocalNetworkTransport(connectivityManager) || hasHotspotLanInterface()
    }

    fun localIPv4Addresses(): List<String> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return emptyList()
        }

        return interfaces
            .filter { try { it.isUp && !it.isLoopback } catch (_: Exception) { false } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isPrivateLanAddress() }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
    }

    fun hotspotClientIPs(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val file = java.io.File("/proc/net/arp")
            if (file.exists() && file.canRead()) {
                file.forEachLine { line ->
                    val tokens = line.trim().split("\\s+".toRegex())
                    if (tokens.size >= 4 && tokens[0] != "IP" && tokens[2] == "0x2") {
                        val ip = tokens[0]
                        if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                            ips.add(ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ips
    }

    @Suppress("DEPRECATION")
    fun isEndpointOnPhoneHotspot(connectivityManager: ConnectivityManager, endpointIp: String): Boolean {
        val endpointAddress = parseIPv4(endpointIp) ?: return false
        val wifiClientInterfaces = connectivityManager.allNetworks
            .filter { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .mapNotNull { network -> connectivityManager.getLinkProperties(network)?.interfaceName }
            .toSet()

        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return false
        }

        return interfaces.any { networkInterface ->
            runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false) &&
                networkInterface.name.isPotentialHotspotInterface() &&
                networkInterface.name !in wifiClientInterfaces &&
                runCatching {
                    networkInterface.interfaceAddresses.any { interfaceAddress ->
                        val localAddress = interfaceAddress.address as? Inet4Address ?: return@any false
                        !localAddress.isLoopbackAddress &&
                            !localAddress.isLinkLocalAddress &&
                            localAddress.isPrivateLanAddress() &&
                            endpointAddress.isOnSubnet(localAddress, interfaceAddress.networkPrefixLength.toInt())
                    }
                }.getOrDefault(false)
        }
    }

    fun unavailableMessage(): String {
        return "No local network found. Connect to Wi-Fi/LAN or turn on the phone hotspot with the Mac connected to it."
    }

    @Suppress("DEPRECATION")
    private fun hasLocalNetworkTransport(connectivityManager: ConnectivityManager): Boolean {
        return connectivityManager.allNetworks.any { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    private fun hasHotspotLanInterface(): Boolean {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return false
        }

        return interfaces.any { networkInterface ->
            networkInterface.isUsableHotspotInterface() &&
                networkInterface.inetAddresses.toList().any { address ->
                    address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        address.isPrivateLanAddress()
                }
        }
    }

    private fun NetworkInterface.isUsableHotspotInterface(): Boolean {
        val interfaceName = name.lowercase()
        return try {
            isUp &&
                !isLoopback &&
                hotspotInterfacePrefixes.any { interfaceName.startsWith(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun java.net.InetAddress.isPrivateLanAddress(): Boolean {
        val bytes = address
        if (bytes.size != 4) {
            return false
        }

        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff

        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun String.isPotentialHotspotInterface(): Boolean {
        val interfaceName = lowercase()
        return listOf("wlan", "swlan", "ap", "softap", "wifi").any { interfaceName.startsWith(it) }
    }

    private fun parseIPv4(value: String): Inet4Address? {
        val octets = value.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        return try {
            InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray()) as? Inet4Address
        } catch (_: Exception) {
            null
        }
    }

    private fun Inet4Address.isOnSubnet(other: Inet4Address, prefixLength: Int): Boolean {
        if (prefixLength !in 1..32) return false
        fun addressValue(address: Inet4Address): Long = address.address.fold(0L) { value, byte ->
            (value shl 8) or (byte.toLong() and 0xff)
        }
        val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
        return (addressValue(this) and mask) == (addressValue(other) and mask)
    }

    private val hotspotInterfacePrefixes = listOf(
        "wlan",
        "swlan",
        "ap",
        "softap",
        "wifi",
        "eth"
    )
}
