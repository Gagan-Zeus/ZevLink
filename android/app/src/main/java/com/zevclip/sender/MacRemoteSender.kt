package com.zevclip.sender

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL

object MacRemoteSender {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_RESPONSE_PREVIEW = 200

    fun send(
        context: Context,
        ipAddress: String,
        port: Int,
        action: String,
        pairingToken: String,
        url: String? = null
    ): SendResult {
        var connection: HttpURLConnection? = null

        return try {
            val activeConnection = URL("http://${ipAddress.toUrlHost()}:$port/remote")
                .openConnection() as HttpURLConnection
            connection = activeConnection

            val payload = JSONObject()
                .put("action", action)
                .apply {
                    if (!url.isNullOrBlank()) {
                        put("url", url.trim())
                    }
                }
                .toString()
                .toByteArray(Charsets.UTF_8)

            activeConnection.requestMethod = "POST"
            activeConnection.connectTimeout = CONNECT_TIMEOUT_MS
            activeConnection.readTimeout = READ_TIMEOUT_MS
            activeConnection.doOutput = true
            activeConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            activeConnection.setRequestProperty("X-ZevClip-Token", pairingToken)
            AndroidReceiverIdentityHeaders.apply(context, activeConnection)
            activeConnection.setFixedLengthStreamingMode(payload.size)

            activeConnection.outputStream.use { output ->
                output.write(payload)
            }

            val responseCode = activeConnection.responseCode
            val responseBody = readResponseBody(activeConnection, responseCode)

            when {
                responseCode in 200..299 ->
                    SendResult.Success(responseBody.ifEmpty { "Mac remote command sent." })
                responseCode == 401 ->
                    SendResult.Failure("Pairing token rejected (HTTP 401). Check the token shown on the Mac.")
                responseCode == 403 ->
                    SendResult.Failure("Phone remote control is disabled on the Mac. Enable it in ZevLink Settings.")
                else -> {
                    val detail = responseBody.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                    SendResult.Failure("Mac remote returned HTTP $responseCode$detail")
                }
            }
        } catch (_: SocketTimeoutException) {
            SendResult.Failure(
                "Connection timed out. Check the Mac IP, port, and local network or hotspot.",
                retryableWithDiscovery = true
            )
        } catch (_: ConnectException) {
            SendResult.Failure(
                "Connection refused. Confirm the Mac receiver is running on port $port.",
                retryableWithDiscovery = true
            )
        } catch (_: NoRouteToHostException) {
            SendResult.Failure(
                "Mac is unreachable. Confirm both devices are on the same local network or phone hotspot.",
                retryableWithDiscovery = true
            )
        } catch (error: IOException) {
            SendResult.Failure(
                "Network error: ${error.message ?: "request failed"}",
                retryableWithDiscovery = true
            )
        } catch (error: SecurityException) {
            SendResult.Failure("Android blocked the request: ${error.message ?: "permission denied"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readResponseBody(
        connection: HttpURLConnection,
        responseCode: Int
    ): String {
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""

        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText().trim().take(MAX_RESPONSE_PREVIEW)
        }
    }

    private fun String.toUrlHost(): String {
        val host = NetworkInputValidator.normalizeHost(this)
        return if (host.contains(':')) "[$host]" else host
    }
}
