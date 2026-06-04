package com.zevclip.sender

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class ClipboardAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZevClipAutoSender")
    }
    private val pendingHashes = mutableSetOf<String>()
    private val sendStateLock = Any()

    private var lastCandidateSignature = ""
    private var lastCandidateAt = 0L
    private var lastSentTextHash: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastSentTextHash = ZevClipPreferences.lastAutoSentHash(this)
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
        networkExecutor.shutdownNow()
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
        val endpoint = ZevClipPreferences.endpoint(this)
        if (endpoint == null) {
            updateStatus("Skipped: enter a valid Mac IP and port in ZevClip.")
            return
        }

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

        if (text.isNullOrEmpty()) {
            updateStatus("Skipped: copied text was unavailable or empty.")
            return
        }

        val textHash = sha256(text)
        val shouldSend = synchronized(sendStateLock) {
            if (textHash == lastSentTextHash || textHash in pendingHashes) {
                false
            } else {
                pendingHashes += textHash
                true
            }
        }

        if (!shouldSend) {
            Log.d(TAG, "Ignoring clipboard text already sent or in flight")
            return
        }

        Log.i(TAG, "Sending copied text automatically (${text.length} characters)")
        networkExecutor.execute {
            val result = ClipboardSender.send(endpoint.ipAddress, endpoint.port, text)

            synchronized(sendStateLock) {
                pendingHashes -= textHash
                if (result is SendResult.Success) {
                    lastSentTextHash = textHash
                    ZevClipPreferences.setLastAutoSentHash(this, textHash)
                }
            }

            when (result) {
                is SendResult.Success -> {
                    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
                    updateStatus("Succeeded at $time (${text.length} characters).")
                    Log.i(TAG, "Automatic clipboard send succeeded")
                }
                is SendResult.Failure -> {
                    updateStatus("Failed: ${result.message}")
                    Log.w(TAG, "Automatic clipboard send failed: ${result.message}")
                }
            }
        }
    }

    private fun updateStatus(status: String) {
        ZevClipPreferences.setLastAutoStatus(this, status)
        Log.i(TAG, status)
    }

    private fun sha256(text: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val TAG = "ZevClip"
        const val DEBOUNCE_MS = 750L
        const val CLIPBOARD_READ_DELAY_MS = 180L
    }
}
