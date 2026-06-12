package com.zevclip.sender

import android.app.Application
import android.content.Context
import android.util.Log
import java.text.DateFormat
import java.util.Date

object AccessibilityClipboardAutoSender {
    private const val TAG = "ZevClipAccessibility"

    fun sendIfChanged(context: Context, text: String?) {
        val appContext = context.applicationContext
        val uiVisible = isUiVisible(appContext)

        if (!ZevClipPreferences.isClipboardSyncEnabled(appContext)) {
            updateStatus(appContext, "Skipped: clipboard sync is off.", uiVisible)
            return
        }

        ClipboardSyncCoordinator.sendIfChanged(appContext, text) { result ->
            when (result) {
                is ClipboardSyncResult.Success -> {
                    val now = System.currentTimeMillis()
                    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(now))
                    ZevClipPreferences.setLastAutoSendAt(appContext, now)
                    updateStatus(
                        appContext,
                        "Succeeded at $time (${result.characterCount} characters).",
                        uiVisible
                    )
                    Log.i(TAG, "Automatic clipboard send succeeded; uiVisible=$uiVisible")
                }
                is ClipboardSyncResult.Failure -> {
                    updateStatus(appContext, "Failed: ${result.message}", uiVisible)
                    Log.w(
                        TAG,
                        "Automatic clipboard send failed; uiVisible=$uiVisible: ${result.message}"
                    )
                }
                ClipboardSyncResult.Empty -> {
                    updateStatus(
                        appContext,
                        "Skipped: clipboard text unavailable or empty; Android may have blocked background access.",
                        uiVisible
                    )
                }
                ClipboardSyncResult.NoEndpoint -> {
                    updateStatus(
                        appContext,
                        "Skipped: enter a valid Mac IP and port in ZevLink.",
                        uiVisible
                    )
                }
                ClipboardSyncResult.NoToken -> {
                    updateStatus(
                        appContext,
                        "Skipped: enter the Mac pairing token in ZevLink.",
                        uiVisible
                    )
                }
                ClipboardSyncResult.Duplicate -> {
                    Log.d(TAG, "Ignoring clipboard text already sent or in flight; uiVisible=$uiVisible")
                }
                ClipboardSyncResult.RemoteEcho -> {
                    updateStatus(
                        appContext,
                        "Skipped: clipboard text came from Mac.",
                        uiVisible
                    )
                    Log.d(TAG, "Ignoring clipboard text received from Mac; uiVisible=$uiVisible")
                }
            }
        }
    }

    fun recordClipboardReadDenied(context: Context, error: SecurityException) {
        val appContext = context.applicationContext
        val uiVisible = isUiVisible(appContext)
        updateStatus(appContext, "Skipped: Android denied clipboard access.", uiVisible)
        Log.w(TAG, "Android denied clipboard access; uiVisible=$uiVisible", error)
    }

    private fun updateStatus(context: Context, status: String, uiVisible: Boolean) {
        ZevClipPreferences.setLastAutoStatus(context, status)
        Log.i(TAG, "$status uiVisible=$uiVisible")
    }

    private fun isUiVisible(context: Context): Boolean {
        return ZevClipApplication.isUiVisible(context.applicationContext as Application)
    }
}
