package com.zevclip.sender

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.Executors

sealed interface ClipboardSyncResult {
    data class Success(val characterCount: Int) : ClipboardSyncResult
    data object Empty : ClipboardSyncResult
    data object NoEndpoint : ClipboardSyncResult
    data object NoToken : ClipboardSyncResult
    data object Duplicate : ClipboardSyncResult
    data class Failure(val message: String) : ClipboardSyncResult
}

object ClipboardSyncCoordinator {
    private val pendingHashes = mutableSetOf<String>()
    private val sendStateLock = Any()
    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZevClipClipboardSender")
    }

    fun sendIfChanged(
        context: Context,
        text: String?,
        onComplete: (ClipboardSyncResult) -> Unit
    ) {
        if (text.isNullOrBlank()) {
            onComplete(ClipboardSyncResult.Empty)
            return
        }

        val endpoint = ZevClipPreferences.endpoint(context)
        if (endpoint == null) {
            onComplete(ClipboardSyncResult.NoEndpoint)
            return
        }

        if (ZevClipPreferences.pairingToken(context).isBlank()) {
            onComplete(ClipboardSyncResult.NoToken)
            return
        }

        val textHash = sha256(text)
        val shouldSend = synchronized(sendStateLock) {
            val lastSentHash = ZevClipPreferences.lastSentHash(context)
            if (textHash == lastSentHash || textHash in pendingHashes) {
                false
            } else {
                pendingHashes += textHash
                true
            }
        }

        if (!shouldSend) {
            onComplete(ClipboardSyncResult.Duplicate)
            return
        }

        networkExecutor.execute {
            val result = ResilientClipboardSender.sendSavedEndpoint(context, text)

            synchronized(sendStateLock) {
                pendingHashes -= textHash
                if (result is SendResult.Success) {
                    ZevClipPreferences.setLastSentHash(context, textHash)
                }
            }

            onComplete(
                when (result) {
                    is SendResult.Success -> ClipboardSyncResult.Success(text.length)
                    is SendResult.Failure -> ClipboardSyncResult.Failure(result.message)
                }
            )
        }
    }

    private fun sha256(text: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
