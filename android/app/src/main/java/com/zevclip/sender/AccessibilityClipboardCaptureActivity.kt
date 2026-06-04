package com.zevclip.sender

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log

class AccessibilityClipboardCaptureActivity : Activity() {
    private var captureStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || captureStarted) {
            return
        }

        captureStarted = true
        window.decorView.postDelayed(::captureAndFinish, CLIPBOARD_READ_DELAY_MS)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun captureAndFinish() {
        val text = try {
            getSystemService(ClipboardManager::class.java)
                .primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        } catch (error: SecurityException) {
            AccessibilityClipboardAutoSender.recordClipboardReadDenied(this, error)
            finish()
            return
        }

        Log.i(TAG, "Focused clipboard reader captured ${text?.length ?: 0} characters")
        AccessibilityClipboardAutoSender.sendIfChanged(applicationContext, text)
        finish()
    }

    private companion object {
        const val TAG = "ZevClipAccessibility"
        const val CLIPBOARD_READ_DELAY_MS = 120L
    }
}
