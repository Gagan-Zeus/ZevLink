package com.zevclip.sender

import org.json.JSONException
import org.json.JSONObject

data class PairingQrPayload(
    val name: String,
    val deviceId: String?,
    val host: String,
    val port: Int,
    val token: String
) {
    companion object {
        fun parse(rawValue: String): Result<PairingQrPayload> {
            return try {
                val json = JSONObject(rawValue)
                val name = json.optString("name").trim()
                val deviceId = json.optString("deviceId").trim().takeIf { it.isNotEmpty() }
                val host = json.optString("host").trim()
                val port = json.optInt("port", -1)
                val token = json.optString("token").trim()

                when {
                    name.isEmpty() ->
                        Result.failure(IllegalArgumentException("QR pairing data is missing a name."))
                    !NetworkInputValidator.validateHost(host) ->
                        Result.failure(IllegalArgumentException("QR pairing data has an invalid host."))
                    port !in 1..65_535 ->
                        Result.failure(IllegalArgumentException("QR pairing data has an invalid port."))
                    token.isEmpty() ->
                        Result.failure(IllegalArgumentException("QR pairing data is missing a token."))
                    else ->
                        Result.success(PairingQrPayload(name, deviceId, host, port, token))
                }
            } catch (_: JSONException) {
                Result.failure(IllegalArgumentException("QR code is not valid ZevClip JSON."))
            }
        }
    }
}
