package com.zevclip.sender

import android.content.Context
import com.zevclip.sender.airplay.RaopTestToneClient
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

class AndroidNowPlayingSender(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZevClipNowPlayingSender")
    }
    @Volatile private var lastSignature: String? = null

    fun sendIfChanged(metadata: RaopTestToneClient.NowPlayingMetadata?) {
        if (metadata == null || metadata.isEmpty()) return
        val signature = metadata.signature()
        if (signature == lastSignature) return
        lastSignature = signature

        executor.execute {
            send(metadata, allowRediscovery = true)
        }
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun send(metadata: RaopTestToneClient.NowPlayingMetadata, allowRediscovery: Boolean) {
        val endpoint = ZevClipPreferences.endpoint(appContext) ?: return
        val pairingToken = ZevClipPreferences.pairingToken(appContext).trim()
        if (pairingToken.isBlank()) return

        var connection: HttpURLConnection? = null
        try {
            val activeConnection = URL("http://${endpoint.ipAddress.toUrlHost()}:${endpoint.port}/android-now-playing")
                .openConnection() as HttpURLConnection
            connection = activeConnection

            val body = JSONObject()
                .put("title", metadata.title.orEmpty())
                .put("artist", metadata.artist.orEmpty())
                .put("album", metadata.album.orEmpty())
                .put("isPlaying", metadata.isPlaying)
                .apply {
                    metadata.durationMillis?.let { put("durationMillis", it) }
                    metadata.positionMillis?.let { put("positionMillis", it) }
                    metadata.artworkBase64?.takeIf { it.isNotBlank() }?.let { put("artworkBase64", it) }
                }
                .toString()
                .toByteArray(Charsets.UTF_8)

            activeConnection.requestMethod = "POST"
            activeConnection.connectTimeout = CONNECT_TIMEOUT_MS
            activeConnection.readTimeout = READ_TIMEOUT_MS
            activeConnection.doOutput = true
            activeConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            activeConnection.setRequestProperty("X-ZevClip-Token", pairingToken)
            AndroidReceiverIdentityHeaders.apply(appContext, activeConnection, AndroidClipboardHttpReceiver.DEFAULT_PORT)
            activeConnection.setFixedLengthStreamingMode(body.size)

            activeConnection.outputStream.use { output ->
                output.write(body)
            }

            activeConnection.responseCode
        } catch (_: SocketTimeoutException) {
            if (allowRediscovery) rediscoverAndRetry(metadata)
        } catch (_: ConnectException) {
            if (allowRediscovery) rediscoverAndRetry(metadata)
        } catch (_: NoRouteToHostException) {
            if (allowRediscovery) rediscoverAndRetry(metadata)
        } catch (_: IOException) {
            if (allowRediscovery) rediscoverAndRetry(metadata)
        } finally {
            connection?.disconnect()
        }
    }

    private fun rediscoverAndRetry(metadata: RaopTestToneClient.NowPlayingMetadata) {
        val deviceId = ZevClipPreferences.deviceId(appContext)
        if (deviceId.isBlank()) return
        val rediscoveredEndpoint = MacAddressRediscoverer.resolvePairedMac(appContext, deviceId) ?: return
        ZevClipPreferences.saveEndpoint(
            appContext,
            rediscoveredEndpoint.ipAddress,
            rediscoveredEndpoint.port.toString()
        )
        send(metadata, allowRediscovery = false)
    }

    private fun String.toUrlHost(): String {
        val host = NetworkInputValidator.normalizeHost(this)
        return if (host.contains(':')) "[$host]" else host
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 4_000
    }
}
