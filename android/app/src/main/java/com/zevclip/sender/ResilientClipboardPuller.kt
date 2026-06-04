package com.zevclip.sender

import android.content.Context
import android.util.Log

object ResilientClipboardPuller {
    private const val TAG = "ZevClip"
    private var lastRediscoveryAttemptAt = 0L

    fun pullSavedEndpoint(context: Context, rediscoveryCooldownMs: Long = 0L): PullResult {
        val appContext = context.applicationContext
        val endpoint = ZevClipPreferences.endpoint(appContext)
            ?: return PullResult.Failure("No valid Mac IP and port.")
        val pairingToken = ZevClipPreferences.pairingToken(appContext)
        if (pairingToken.isBlank()) {
            return PullResult.Failure("No pairing token.")
        }

        val firstResult = ClipboardSender.pull(
            endpoint.ipAddress,
            endpoint.port,
            pairingToken
        )

        if (firstResult !is PullResult.Failure || !firstResult.retryableWithDiscovery) {
            return firstResult
        }

        val deviceId = ZevClipPreferences.deviceId(appContext)
        if (deviceId.isBlank()) {
            Log.i(TAG, "Pull failed, but no paired deviceId is saved; skipping rediscovery")
            return firstResult
        }

        val now = System.currentTimeMillis()
        synchronized(this) {
            if (
                rediscoveryCooldownMs > 0L &&
                now - lastRediscoveryAttemptAt < rediscoveryCooldownMs
            ) {
                Log.i(TAG, "Pull rediscovery skipped during cooldown")
                return firstResult
            }
            lastRediscoveryAttemptAt = now
        }

        Log.i(TAG, "Saved Mac endpoint pull failed. Rediscovering paired Mac by deviceId.")
        ZevClipPreferences.setDiscoveryStatus(
            appContext,
            "Saved Mac address failed during pull. Rediscovering paired Mac…"
        )

        val rediscoveredEndpoint = MacAddressRediscoverer.resolvePairedMac(appContext, deviceId)
            ?: return PullResult.Failure(
                "${firstResult.message} Rediscovery did not find the paired Mac."
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

        return ClipboardSender.pull(
            rediscoveredEndpoint.ipAddress,
            rediscoveredEndpoint.port,
            pairingToken
        )
    }
}
