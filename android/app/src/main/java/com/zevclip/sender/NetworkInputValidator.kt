package com.zevclip.sender

object NetworkInputValidator {
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

    fun parsePort(value: String): Int? {
        return value.toIntOrNull()?.takeIf { it in 1..65_535 }
    }
}
