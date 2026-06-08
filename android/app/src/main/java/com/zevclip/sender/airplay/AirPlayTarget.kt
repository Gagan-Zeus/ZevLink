package com.zevclip.sender.airplay

data class AirPlayTarget(
    val host: String,
    val port: Int = DEFAULT_RTSP_PORT,
    val name: String? = null,
    val deviceId: String? = null,
    val model: String? = null,
    val features: String? = null,
    val requiresPassword: Boolean? = null
) {
    init {
        require(host.isNotBlank()) { "AirPlay host must not be blank." }
        require(port in 1..65535) { "AirPlay port must be between 1 and 65535." }
    }

    companion object {
        const val DEFAULT_RTSP_PORT = 7000
    }
}
