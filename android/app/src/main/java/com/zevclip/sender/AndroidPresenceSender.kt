package com.zevclip.sender

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL

object AndroidPresenceSender {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_RESPONSE_PREVIEW = 200

    fun sendSavedEndpoint(
        context: Context,
        androidReceiverPort: Int,
        findPhoneRinging: Boolean? = null
    ): SendResult {
        val appContext = context.applicationContext
        val endpoint = ZevClipPreferences.endpoint(appContext)
            ?: return SendResult.Failure("No valid Mac IP and port.")
        val pairingToken = ZevClipPreferences.pairingToken(appContext)
        if (pairingToken.isBlank()) {
            return SendResult.Failure("No pairing token.")
        }

        val firstResult = send(
            appContext,
            endpoint.ipAddress,
            endpoint.port,
            pairingToken,
            androidReceiverPort,
            findPhoneRinging
        )
        if (firstResult !is SendResult.Failure || !firstResult.retryableWithDiscovery) {
            return firstResult
        }

        val deviceId = ZevClipPreferences.deviceId(appContext)
        if (deviceId.isBlank()) {
            return firstResult
        }

        val rediscoveredEndpoint = MacAddressRediscoverer.resolvePairedMac(appContext, deviceId)
            ?: return SendResult.Failure(
                "${firstResult.message} Rediscovery did not find the paired Mac.",
                retryableWithDiscovery = false
            )

        ZevClipPreferences.saveEndpoint(
            appContext,
            rediscoveredEndpoint.ipAddress,
            rediscoveredEndpoint.port.toString()
        )

        return send(
            appContext,
            rediscoveredEndpoint.ipAddress,
            rediscoveredEndpoint.port,
            pairingToken,
            androidReceiverPort,
            findPhoneRinging
        )
    }

    private fun send(
        context: Context,
        ipAddress: String,
        port: Int,
        pairingToken: String,
        androidReceiverPort: Int,
        findPhoneRinging: Boolean?
    ): SendResult {
        var connection: HttpURLConnection? = null

        return try {
            val activeConnection = URL("http://${ipAddress.toUrlHost()}:$port/android-presence")
                .openConnection() as HttpURLConnection
            connection = activeConnection

            val body = JSONObject()
                .put("receiver", "running")
                .put("port", androidReceiverPort)
                .toString()
                .toByteArray(Charsets.UTF_8)

            activeConnection.requestMethod = "POST"
            activeConnection.connectTimeout = CONNECT_TIMEOUT_MS
            activeConnection.readTimeout = READ_TIMEOUT_MS
            activeConnection.doOutput = true
            activeConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            activeConnection.setRequestProperty("X-ZevClip-Token", pairingToken)
            AndroidReceiverIdentityHeaders.apply(context, activeConnection, androidReceiverPort)
            findPhoneRinging?.let { isRinging ->
                activeConnection.setRequestProperty(
                    "X-ZevClip-Find-Phone-Ringing",
                    isRinging.toString()
                )
            }
            activeConnection.setFixedLengthStreamingMode(body.size)

            activeConnection.outputStream.use { output ->
                output.write(body)
            }

            val responseCode = activeConnection.responseCode
            val responseBody = readResponseBody(activeConnection, responseCode)

            if (responseCode in 200..299) {
                SendResult.Success("Updated Mac with Android receiver presence.")
            } else if (responseCode == 401) {
                SendResult.Failure("Pairing token rejected while updating Android presence.")
            } else {
                val detail = responseBody.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                SendResult.Failure("Mac presence endpoint returned HTTP $responseCode$detail")
            }
        } catch (_: SocketTimeoutException) {
            SendResult.Failure("Android presence update timed out.", retryableWithDiscovery = true)
        } catch (_: ConnectException) {
            SendResult.Failure("Mac presence endpoint refused connection.", retryableWithDiscovery = true)
        } catch (_: NoRouteToHostException) {
            SendResult.Failure("Mac presence endpoint is unreachable.", retryableWithDiscovery = true)
        } catch (error: IOException) {
            SendResult.Failure(
                "Android presence network error: ${error.message ?: "request failed"}",
                retryableWithDiscovery = true
            )
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
