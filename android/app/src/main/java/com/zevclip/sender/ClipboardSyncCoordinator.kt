package com.zevclip.sender

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest
import java.util.concurrent.Executors

sealed interface ClipboardSyncResult {
    data class Success(val characterCount: Int) : ClipboardSyncResult
    data object Empty : ClipboardSyncResult
    data object NoEndpoint : ClipboardSyncResult
    data object NoToken : ClipboardSyncResult
    data object Duplicate : ClipboardSyncResult
    data object RemoteEcho : ClipboardSyncResult
    data class Failure(val message: String) : ClipboardSyncResult
}

object ClipboardSyncCoordinator {
    private const val RECENT_DUPLICATE_WINDOW_MS = 1_500L
    private const val REMOTE_CLIPBOARD_ECHO_WINDOW_MS = 2_000L

    private val pendingHashes = mutableSetOf<String>()
    private val sendStateLock = Any()
    private var lastCompletedHash: String? = null
    private var lastCompletedAt = 0L
    private var lastRemoteClipboardHash: String? = null
    private var lastRemoteClipboardAt = 0L

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
        val decision = synchronized(sendStateLock) {
            val isRecentlyReceivedFromMac = textHash == lastRemoteClipboardHash &&
                SystemClock.elapsedRealtime() - lastRemoteClipboardAt < REMOTE_CLIPBOARD_ECHO_WINDOW_MS
            val isRecentlyCompletedDuplicate = textHash == lastCompletedHash &&
                SystemClock.elapsedRealtime() - lastCompletedAt < RECENT_DUPLICATE_WINDOW_MS

            when {
                isRecentlyReceivedFromMac ->
                    SendDecision.RemoteEcho
                textHash in pendingHashes || isRecentlyCompletedDuplicate ->
                    SendDecision.Duplicate
                else -> {
                    pendingHashes += textHash
                    SendDecision.Send
                }
            }
        }

        when (decision) {
            SendDecision.RemoteEcho -> {
                onComplete(ClipboardSyncResult.RemoteEcho)
                return
            }
            SendDecision.Duplicate -> {
                onComplete(ClipboardSyncResult.Duplicate)
                return
            }
            SendDecision.Send -> Unit
        }

        networkExecutor.execute {
            val result = ResilientClipboardSender.sendSavedEndpoint(context, text)

            synchronized(sendStateLock) {
                pendingHashes -= textHash
                if (result is SendResult.Success) {
                    lastCompletedHash = textHash
                    lastCompletedAt = SystemClock.elapsedRealtime()
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

    fun rememberRemoteClipboardWrite(text: String) {
        synchronized(sendStateLock) {
            lastRemoteClipboardHash = sha256(text)
            lastRemoteClipboardAt = SystemClock.elapsedRealtime()
        }
    }

    private fun sha256(text: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private enum class SendDecision {
        Send,
        Duplicate,
        RemoteEcho
    }
}
