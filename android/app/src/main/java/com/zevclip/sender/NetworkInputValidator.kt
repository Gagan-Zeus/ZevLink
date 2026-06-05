package com.zevclip.sender

import java.net.Inet6Address
import java.net.InetAddress

object NetworkInputValidator {
    private val hostNameRegex = Regex("^[A-Za-z0-9][A-Za-z0-9.-]{0,251}[A-Za-z0-9]$")

    fun normalizeHost(value: String): String {
        val host = value.trim()
        return if (host.startsWith("[") && host.endsWith("]")) {
            host.drop(1).dropLast(1)
        } else {
            host
        }
    }

    fun validateIPv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false

        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    fun validateHost(value: String): Boolean {
        val host = normalizeHost(value)
        if (host.isEmpty()) return false
        if (validateIPv4(host)) return true
        if (validateIPv6(host)) return true
        if (host.length > 253) return false
        if (!hostNameRegex.matches(host)) return false

        return host.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && !label.startsWith("-") && !label.endsWith("-")
        }
    }

    fun parsePort(value: String): Int? {
        return value.toIntOrNull()?.takeIf { it in 1..65_535 }
    }

    private fun validateIPv6(value: String): Boolean {
        if (!value.contains(':')) return false

        return try {
            val address = InetAddress.getByName(value)
            address is Inet6Address
        } catch (_: Exception) {
            false
        }
    }
}
