package com.zevclip.sender.airplay

interface AirPlayRtspTransport {
    fun request(
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
        contentType: String? = null
    ): AirPlayRtspClient.Response
}
