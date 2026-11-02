package com.zevclip.sender

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

object ResilientClipboardSender {
    private const val TAG = "ZevClip"

    fun sendSavedEndpoint(context: Context, text: String): SendResult {
        val appContext = context.applicationContext
        val endpoint = ZevClipPreferences.endpoint(appContext)
        val pairingToken = ZevClipPreferences.pairingToken(appContext)

        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val hasLocalNetwork = LocalNetworkAccess.canReachLocalPeers(connectivityManager)
        val activeMode = ZevClipPreferences.activeTransportMode(appContext)

        // Fast-path: If active transport mode is Bluetooth and BT is connected, send via Bluetooth directly
        if (activeMode == "Bluetooth" && BluetoothClientManager.isConnected()) {
            val sent = BluetoothClientManager.sendClipboard(appContext, text)
            if (sent) {
                return SendResult.Success("Sent clipboard to Mac via Bluetooth.")
            }
        }

        // Fast-path: If local network is not available (no Wi-Fi/Hotspot), try Bluetooth directly
        if (!hasLocalNetwork) {
            if (BluetoothClientManager.isConnected()) {
                val sent = BluetoothClientManager.sendClipboard(appContext, text)
                if (sent) {
                    ZevClipPreferences.setActiveTransportMode(appContext, "Bluetooth")
                    return SendResult.Success("Sent clipboard to Mac via Bluetooth.")
                }
            }
        }

        // Priority 1: Try Wi-Fi first if local network is available and endpoint is present
        if (hasLocalNetwork && endpoint != null && pairingToken.isNotBlank()) {
            val firstResult = ClipboardSender.send(
                appContext,
                endpoint.ipAddress,
                endpoint.port,
                text,
                pairingToken
            )

            if (firstResult is SendResult.Success) {
                ZevClipPreferences.setActiveTransportMode(appContext, "Wi-Fi")
                return firstResult
            }

            val failure = firstResult as? SendResult.Failure
            val deviceId = ZevClipPreferences.deviceId(appContext)
            if (deviceId.isNotBlank() && failure?.retryableWithDiscovery == true) {
                Log.i(TAG, "Saved Mac endpoint failed. Rediscovering paired Mac by deviceId.")
                ZevClipPreferences.setDiscoveryStatus(
                    appContext,
                    "Saved Mac address failed. Rediscovering paired Mac…"
                )

                val rediscoveredEndpoint = MacAddressRediscoverer.resolvePairedMac(appContext, deviceId)
                if (rediscoveredEndpoint != null) {
                    ZevClipPreferences.saveEndpoint(
                        appContext,
                        rediscoveredEndpoint.ipAddress,
                        rediscoveredEndpoint.port.toString()
                    )
                    ZevClipPreferences.setDiscoveryStatus(
                        appContext,
                        "Rediscovered paired Mac at ${rediscoveredEndpoint.ipAddress}:${rediscoveredEndpoint.port}."
                    )
                    val retryResult = ClipboardSender.send(
                        appContext,
                        rediscoveredEndpoint.ipAddress,
                        rediscoveredEndpoint.port,
                        text,
                        pairingToken
                    )
                    if (retryResult is SendResult.Success) {
                        ZevClipPreferences.setActiveTransportMode(appContext, "Wi-Fi")
                        return SendResult.Success(
                            "Sent successfully after rediscovering Mac at ${rediscoveredEndpoint.ipAddress}:${rediscoveredEndpoint.port}."
                        )
                    }
                }
            }
        }

        // Priority 2: Fallback to Bluetooth
        if (BluetoothClientManager.isConnected()) {
            Log.i(TAG, "Wi-Fi failed or unavailable. Falling back to Bluetooth for clipboard sync.")
            val sent = BluetoothClientManager.sendClipboard(appContext, text)
            if (sent) {
                ZevClipPreferences.setActiveTransportMode(appContext, "Bluetooth")
                return SendResult.Success("Sent clipboard to Mac via Bluetooth fallback.")
            }
        }

        return SendResult.Failure("Failed to send clipboard via Wi-Fi or Bluetooth.")
    }
}
