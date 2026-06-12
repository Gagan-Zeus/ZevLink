package com.zevclip.sender

import org.json.JSONException
import org.json.JSONObject

data class PairingQrPayload(
    val name: String,
    val deviceId: String?,
    val host: String,
    val hosts: List<String>,
    val port: Int,
    val token: String
) {
    companion object {
        fun parse(rawValue: String): Result<PairingQrPayload> {
            return try {
                val json = JSONObject(rawValue)
                val name = json.optString("name").trim()
                val deviceId = json.optString("deviceId").trim().takeIf { it.isNotEmpty() }
                val host = NetworkInputValidator.normalizeHost(json.optString("host"))
                val hosts = buildList {
                    add(host)
                    val hostArray = json.optJSONArray("hosts")
                    if (hostArray != null) {
                        for (index in 0 until hostArray.length()) {
                            add(NetworkInputValidator.normalizeHost(hostArray.optString(index)))
                        }
                    }
                }.filter { it.isNotEmpty() }.distinct()
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
                        Result.success(PairingQrPayload(name, deviceId, host, hosts, port, token))
                }
            } catch (_: JSONException) {
                Result.failure(IllegalArgumentException("QR code is not valid ZevLink JSON."))
            }
        }
    }
}
