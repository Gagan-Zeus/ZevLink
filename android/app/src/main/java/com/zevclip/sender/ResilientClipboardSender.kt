package com.zevclip.sender

import android.content.Context
import android.util.Log

object ResilientClipboardSender {
    private const val TAG = "ZevClip"

    fun sendSavedEndpoint(context: Context, text: String): SendResult {
        val appContext = context.applicationContext
        val endpoint = ZevClipPreferences.endpoint(appContext)
            ?: return SendResult.Failure("No valid Mac IP and port.")
        val pairingToken = ZevClipPreferences.pairingToken(appContext)
        if (pairingToken.isBlank()) {
            return SendResult.Failure("No pairing token.")
        }

        val firstResult = ClipboardSender.send(
            endpoint.ipAddress,
            endpoint.port,
            text,
            pairingToken
        )

        if (firstResult !is SendResult.Failure || !firstResult.retryableWithDiscovery) {
            return firstResult
        }

        val deviceId = ZevClipPreferences.deviceId(appContext)
        if (deviceId.isBlank()) {
            Log.i(TAG, "Send failed, but no paired deviceId is saved; skipping rediscovery")
            return firstResult
        }

        Log.i(TAG, "Saved Mac endpoint failed. Rediscovering paired Mac by deviceId.")
        ZevClipPreferences.setDiscoveryStatus(
            appContext,
            "Saved Mac address failed. Rediscovering paired Mac…"
        )

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
        ZevClipPreferences.setDiscoveryStatus(
            appContext,
            "Rediscovered paired Mac at ${rediscoveredEndpoint.ipAddress}:${rediscoveredEndpoint.port}."
        )

        return when (
            val retryResult = ClipboardSender.send(
                rediscoveredEndpoint.ipAddress,
                rediscoveredEndpoint.port,
                text,
                pairingToken
            )
        ) {
            is SendResult.Success -> SendResult.Success(
                "Sent successfully after rediscovering Mac at ${rediscoveredEndpoint.ipAddress}:${rediscoveredEndpoint.port}."
            )
            is SendResult.Failure -> SendResult.Failure(
                "Rediscovered Mac at ${rediscoveredEndpoint.ipAddress}:${rediscoveredEndpoint.port}, but retry failed: ${retryResult.message}",
                retryableWithDiscovery = false
            )
        }
    }
}
