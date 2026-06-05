package com.zevclip.sender

import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object EndpointSelector {
    private const val CONNECT_TIMEOUT_MS = 900

    fun serviceHosts(serviceInfo: NsdServiceInfo): List<String> {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(serviceInfo.host)
        }

        return addresses
            .mapNotNull { it.hostAddress?.substringBefore('%')?.takeIf(String::isNotBlank) }
            .distinct()
    }

    fun selectReachableHost(hosts: List<String>, port: Int): String? {
        val validHosts = hosts
            .map { NetworkInputValidator.normalizeHost(it) }
            .filter { NetworkInputValidator.validateHost(it) }
            .distinct()
            .sortedWith(compareByDescending<String> { it.isGlobalIPv6Literal() }.thenBy { it })

        return validHosts.firstOrNull { host -> canConnect(host, port) }
    }

    private fun canConnect(host: String, port: Int): Boolean {
        return try {
            val address = InetAddress.getByName(host)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun String.isGlobalIPv6Literal(): Boolean {
        return try {
            val address = InetAddress.getByName(this)
            address is Inet6Address &&
                !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress
        } catch (_: Exception) {
            false
        }
    }
}
