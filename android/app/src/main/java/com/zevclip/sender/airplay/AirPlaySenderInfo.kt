package com.zevclip.sender.airplay

import android.os.Build

data class AirPlaySenderInfo(
    val deviceId: String,
    val name: String,
    val model: String,
    val osName: String,
    val osVersion: String,
    val osBuildVersion: String,
    val sourceVersion: String = SOURCE_VERSION
) {
    init {
        require(deviceId.isNotBlank()) { "AirPlay device id must not be blank." }
        require(name.isNotBlank()) { "AirPlay sender name must not be blank." }
        require(model.isNotBlank()) { "AirPlay sender model must not be blank." }
        require(osName.isNotBlank()) { "AirPlay sender OS name must not be blank." }
    }

    fun setupEntries(): Array<Pair<String, BPlist.Value>> {
        return arrayOf(
            "deviceID" to BPlist.string(deviceId),
            "macAddress" to BPlist.string(deviceId),
            "name" to BPlist.string(name),
            "model" to BPlist.string(model),
            "osName" to BPlist.string(osName),
            "osVersion" to BPlist.string(osVersion),
            "osBuildVersion" to BPlist.string(osBuildVersion),
            "sourceVersion" to BPlist.string(sourceVersion)
        )
    }

    companion object {
        const val SOURCE_VERSION = "950.7.1"

        fun fromIdentity(identity: AirPlayIdentity): AirPlaySenderInfo {
            return fromDevice(identity.deviceId, identity.senderName)
        }

        fun fromDevice(deviceId: String, senderName: String): AirPlaySenderInfo {
            return AirPlaySenderInfo(
                deviceId = deviceId,
                name = senderName,
                model = androidModelName(),
                osName = "Android",
                osVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() }
                    ?: Build.VERSION.SDK_INT.toString(),
                osBuildVersion = firstNonBlank(Build.ID, Build.VERSION.INCREMENTAL, Build.FINGERPRINT)
                    ?: Build.VERSION.SDK_INT.toString()
            )
        }

        private fun androidModelName(): String {
            val manufacturer = Build.MANUFACTURER.orEmpty().trim()
            val model = Build.MODEL.orEmpty().trim()
            return when {
                model.isBlank() -> "Android"
                manufacturer.isBlank() -> model
                model.startsWith(manufacturer, ignoreCase = true) -> model
                else -> "$manufacturer $model"
            }.replace(Regex("\\s+"), " ").take(64).ifBlank { "Android" }
        }

        private fun firstNonBlank(vararg values: String?): String? {
            return values.firstOrNull { !it.isNullOrBlank() }?.trim()
        }
    }
}
