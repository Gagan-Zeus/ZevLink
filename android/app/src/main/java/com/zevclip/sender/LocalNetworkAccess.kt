package com.zevclip.sender

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
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

    private val hotspotInterfacePrefixes = listOf(
        "wlan",
        "swlan",
        "ap",
        "softap",
        "wifi",
        "eth"
    )
}
