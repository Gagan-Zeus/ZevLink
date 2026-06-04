package com.zevclip.sender

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.text.DateFormat
import java.util.Date

class ClipboardAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    private var lastCandidateSignature = ""
    private var lastCandidateAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected; waiting for likely copy events")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val candidates = eventCandidates(event)
        val matchingCandidate = candidates.firstOrNull(::looksLikeCopyAction) ?: return
        val signature = matchingCandidate
        val now = SystemClock.elapsedRealtime()

        if (signature == lastCandidateSignature && now - lastCandidateAt < DEBOUNCE_MS) {
            Log.d(TAG, "Ignoring duplicate copy event: $matchingCandidate")
            return
        }

        lastCandidateSignature = signature
        lastCandidateAt = now

        Log.i(
            TAG,
            "Likely copy event detected: ${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "package=${event.packageName} candidate=$matchingCandidate"
        )

        handler.postDelayed(
            { readClipboardAndSend() },
            CLIPBOARD_READ_DELAY_MS
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    private fun eventCandidates(event: AccessibilityEvent): List<String> {
        val candidates = buildList {
            event.text.mapTo(this) { it.toString() }
            event.contentDescription?.toString()?.let(::add)
            event.className?.toString()?.let(::add)

            event.source?.let { source ->
                source.text?.toString()?.let(::add)
                source.contentDescription?.toString()?.let(::add)
                source.className?.toString()?.let(::add)
            }
        }

        return candidates
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun looksLikeCopyAction(candidate: String): Boolean {
        return candidate == "copy" ||
            candidate == "copied" ||
            candidate.startsWith("copy ") ||
            candidate.startsWith("copied ") ||
            candidate.contains("copied to clipboard")
    }

    private fun readClipboardAndSend() {
        val text = try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        } catch (error: SecurityException) {
            Log.w(TAG, "Android denied clipboard access", error)
            updateStatus("Skipped: Android denied clipboard access.")
            return
        }

        ClipboardSyncCoordinator.sendIfChanged(this, text) { result ->
            when (result) {
                is ClipboardSyncResult.Success -> {
                    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
                    updateStatus("Succeeded at $time (${result.characterCount} characters).")
                    Log.i(TAG, "Automatic clipboard send succeeded")
                }
                is ClipboardSyncResult.Failure -> {
                    updateStatus("Failed: ${result.message}")
                    Log.w(TAG, "Automatic clipboard send failed: ${result.message}")
                }
                ClipboardSyncResult.Empty -> {
                    updateStatus("Skipped: copied text was unavailable or empty.")
                }
                ClipboardSyncResult.NoEndpoint -> {
                    updateStatus("Skipped: enter a valid Mac IP and port in ZevClip.")
                }
                ClipboardSyncResult.Duplicate -> {
                    Log.d(TAG, "Ignoring clipboard text already sent or in flight")
                }
            }
        }
    }

    private fun updateStatus(status: String) {
        ZevClipPreferences.setLastAutoStatus(this, status)
        Log.i(TAG, status)
    }

    private companion object {
        const val TAG = "ZevClip"
        const val DEBOUNCE_MS = 750L
        const val CLIPBOARD_READ_DELAY_MS = 180L
    }
}
