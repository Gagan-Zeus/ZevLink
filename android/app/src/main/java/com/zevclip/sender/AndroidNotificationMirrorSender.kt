package com.zevclip.sender

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL

data class AndroidNotificationMirrorPayload(
    val event: String,
    val appName: String,
    val packageName: String,
    val appIconPngBase64: String?,
    val title: String?,
    val body: String?,
    val subtext: String?,
    val actions: List<AndroidNotificationMirrorAction>,
    val notificationKey: String?,
    val postedAtMillis: Long
) {
    fun toJsonBytes(): ByteArray {
        val actionArray = JSONArray()
        actions.forEach { action ->
            actionArray.put(
                JSONObject()
                    .put("id", action.id)
                    .put("title", action.title)
                    .put("requiresTextInput", action.requiresTextInput)
                    .put("inputLabel", action.inputLabel)
            )
        }

        return JSONObject()
            .put("event", event)
            .put("appName", appName)
            .put("packageName", packageName)
            .put("appIconPngBase64", appIconPngBase64)
            .put("title", title)
            .put("body", body)
            .put("subtext", subtext)
            .put("actions", actionArray)
            .put("notificationKey", notificationKey)
            .put("postedAtMillis", postedAtMillis)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }
}

data class AndroidNotificationMirrorAction(
    val id: String,
    val title: String,
    val requiresTextInput: Boolean,
    val inputLabel: String?
)

object AndroidNotificationMirrorSender {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_RESPONSE_PREVIEW = 200

    fun sendSavedEndpoint(
        context: Context,
        payload: AndroidNotificationMirrorPayload
    ): SendResult {
        val appContext = context.applicationContext
        val endpoint = ZevClipPreferences.endpoint(appContext)
            ?: return SendResult.Failure("No valid Mac IP and port.")
        val pairingToken = ZevClipPreferences.pairingToken(appContext)
        if (pairingToken.isBlank()) {
            return SendResult.Failure("No pairing token.")
        }

        val firstResult = send(appContext, endpoint.ipAddress, endpoint.port, pairingToken, payload)
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
            payload
        )
    }

    private fun send(
        context: Context,
        ipAddress: String,
        port: Int,
        pairingToken: String,
        payload: AndroidNotificationMirrorPayload
    ): SendResult {
        var connection: HttpURLConnection? = null

        return try {
            val activeConnection = URL("http://${ipAddress.toUrlHost()}:$port/android-notification")
                .openConnection() as HttpURLConnection
            connection = activeConnection

            val body = payload.toJsonBytes()

            activeConnection.requestMethod = "POST"
            activeConnection.connectTimeout = CONNECT_TIMEOUT_MS
            activeConnection.readTimeout = READ_TIMEOUT_MS
            activeConnection.doOutput = true
            activeConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            activeConnection.setRequestProperty("X-ZevClip-Token", pairingToken)
            AndroidReceiverIdentityHeaders.apply(context, activeConnection)
            activeConnection.setFixedLengthStreamingMode(body.size)

            activeConnection.outputStream.use { output ->
                output.write(body)
            }

            val responseCode = activeConnection.responseCode
            val responseBody = readResponseBody(activeConnection, responseCode)

            if (responseCode in 200..299) {
                SendResult.Success("Mirrored notification.")
            } else if (responseCode == 401) {
                SendResult.Failure("Pairing token rejected while mirroring notification.")
            } else {
                val detail = responseBody.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                SendResult.Failure("Mac notification endpoint returned HTTP $responseCode$detail")
            }
        } catch (_: SocketTimeoutException) {
            SendResult.Failure("Notification mirror timed out.", retryableWithDiscovery = true)
        } catch (_: ConnectException) {
            SendResult.Failure("Mac notification endpoint refused connection.", retryableWithDiscovery = true)
        } catch (_: NoRouteToHostException) {
            SendResult.Failure("Mac notification endpoint is unreachable.", retryableWithDiscovery = true)
        } catch (error: IOException) {
            SendResult.Failure(
                "Notification mirror network error: ${error.message ?: "request failed"}",
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
