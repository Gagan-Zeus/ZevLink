package com.zevclip.sender.airplay

object AirPlayTxt {
    fun targetFromTxt(
        host: String,
        port: Int,
        name: String? = null,
        txt: Map<String, ByteArray>
    ): AirPlayTarget {
        val text = txt.mapKeys { (key, _) -> key.lowercase() }
            .mapValues { (_, value) -> value.toString(Charsets.UTF_8) }
        return AirPlayTarget(
            host = host,
            port = port,
            name = name,
            deviceId = text["deviceid"] ?: text["id"],
            model = text["model"] ?: text["am"],
            features = text["features"] ?: text["ft"],
            requiresPassword = text["pw"]?.let { it == "1" || it.equals("true", ignoreCase = true) }
        )
    }

    fun parseRawTxt(records: List<ByteArray>): Map<String, ByteArray> {
        return linkedMapOf<String, ByteArray>().apply {
            records.forEach { record ->
                val separator = record.indexOf('='.code.toByte())
                if (separator <= 0) return@forEach
                val key = record.copyOfRange(0, separator).toString(Charsets.UTF_8)
                val value = record.copyOfRange(separator + 1, record.size)
                put(key, value)
            }
        }
    }

    private fun ByteArray.indexOf(value: Byte): Int {
        for (index in indices) {
            if (this[index] == value) return index
        }
        return -1
    }
}
