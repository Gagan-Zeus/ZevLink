package com.zevclip.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class ClipboardAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val clipboardChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClipboardSignalAt < DEBOUNCE_MS) {
            return@OnPrimaryClipChangedListener
        }

        lastClipboardSignalAt = now
        if (now - lastOutboundSendRequestAt < PASSIVE_CLIPBOARD_ECHO_SUPPRESSION_MS) {
            Log.d(TAG, "Ignoring passive clipboard change soon after ZevClip sent to Mac")
            return@OnPrimaryClipChangedListener
        }

        Log.i(
            TAG,
            "Primary clipboard changed; reading clipboard for sync. " +
                "recentCopySignal=${now - lastCandidateAt <= COPY_SIGNAL_RECENT_MS} " +
                "uiVisible=${ZevClipApplication.isUiVisible(application)}"
        )
        handler.postDelayed(
            { readClipboardAndSend() },
            CLIPBOARD_READ_DELAY_MS
        )
    }

    private var lastCandidateSignature = ""
    private var lastCandidateAt = 0L
    private var lastClipboardSignalAt = 0L
    private var lastOutboundSendRequestAt = 0L
    private var selectedText: String? = null
    private var selectedTextAt = 0L
    private var longPressedText: String? = null
    private var longPressedTextAt = 0L
    private var clipboardListenerRegistered = false
    private var lastAirPlayVolumeToastAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        registerClipboardListener()
        ZevClipPreferences.setAccessibilityServiceState(
            this,
            bound = true,
            event = "connected",
            connectedAtMillis = System.currentTimeMillis()
        )
        Log.i(TAG, "Accessibility service connected; watching clipboard changes")
        AccessibilityServiceStatus.logCurrentState(this, "onServiceConnected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isAirPlayActive()) {
            return super.onKeyEvent(event)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    showAirPlayVolumeBlockedToast()
                    Log.i(TAG, "Blocked Android volume key during AirPlay: keyCode=${event.keyCode}")
                }
                true
            }
            else -> super.onKeyEvent(event)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        rememberSelectedText(event)
        rememberLongPressedText(event)

        val candidates = eventCandidates(event)
        val matchingCandidate = candidates.firstOrNull(::looksLikeClipboardExportAction) ?: return
        val signature = matchingCandidate
        val now = SystemClock.elapsedRealtime()

        if (signature == lastCandidateSignature && now - lastCandidateAt < DEBOUNCE_MS) {
            Log.d(TAG, "Ignoring duplicate clipboard export event: $matchingCandidate")
            return
        }

        lastCandidateSignature = signature
        lastCandidateAt = now

        Log.i(
            TAG,
            "Likely clipboard export event detected: ${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "package=${event.packageName} candidate=$matchingCandidate " +
                "uiVisible=${ZevClipApplication.isUiVisible(application)}"
        )

        scheduleClipboardReadAfterCopy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        unregisterClipboardListener()
        ZevClipPreferences.setAccessibilityServiceState(this, bound = false, event = "unbound")
        Log.i(TAG, "Accessibility service unbound")
        AccessibilityServiceStatus.logCurrentState(this, "onUnbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterClipboardListener()
        ZevClipPreferences.setAccessibilityServiceState(this, bound = false, event = "destroyed")
        Log.i(TAG, "Accessibility service destroyed")
        AccessibilityServiceStatus.logCurrentState(this, "onDestroy")
        super.onDestroy()
    }

    private fun registerClipboardListener() {
        if (clipboardListenerRegistered) {
            return
        }

        getSystemService(ClipboardManager::class.java)
            .addPrimaryClipChangedListener(clipboardChangedListener)
        clipboardListenerRegistered = true
    }

    private fun unregisterClipboardListener() {
        if (!clipboardListenerRegistered) {
            return
        }

        getSystemService(ClipboardManager::class.java)
            .removePrimaryClipChangedListener(clipboardChangedListener)
        clipboardListenerRegistered = false
    }

    private fun isAirPlayActive(): Boolean {
        return ZevClipPreferences.isAirPlayStreaming(this) ||
            ZevClipPreferences.isAirPlayScreenMirroring(this) ||
            ZevClipPreferences.isAirPlayBroadcastStreaming(this)
    }

    private fun showAirPlayVolumeBlockedToast() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAirPlayVolumeToastAt < AIRPLAY_VOLUME_TOAST_THROTTLE_MS) {
            return
        }

        lastAirPlayVolumeToastAt = now
        Toast.makeText(
            applicationContext,
            getString(R.string.airplay_local_volume_blocked),
            Toast.LENGTH_SHORT
        ).show()
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

    private fun looksLikeClipboardExportAction(candidate: String): Boolean {
        return candidate == "copy" ||
            candidate == "copied" ||
            candidate == "cut" ||
            candidate.startsWith("copy ") ||
            candidate.startsWith("copied ") ||
            candidate.startsWith("cut ") ||
            candidate.contains("copied to clipboard") ||
            candidate.contains("cut to clipboard")
    }

    private fun rememberSelectedText(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            return
        }

        val source = event.source
        val text = selectedRange(
            source?.text,
            source?.textSelectionStart ?: -1,
            source?.textSelectionEnd ?: -1
        ) ?: event.text.firstNotNullOfOrNull { candidate ->
            selectedRange(candidate, event.fromIndex, event.toIndex)
        }

        if (text.isNullOrBlank()) {
            return
        }

        selectedText = text
        selectedTextAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "Remembered selected text (${text.length} characters)")
    }

    private fun selectedRange(text: CharSequence?, start: Int, end: Int): String? {
        if (text == null || start < 0 || end <= start || end > text.length) {
            return null
        }

        return text.subSequence(start, end).toString().takeIf { it.isNotBlank() }
    }

    private fun recentSelectedText(): String? {
        return selectedText?.takeIf {
            SystemClock.elapsedRealtime() - selectedTextAt <= SELECTION_MAX_AGE_MS
        }
    }

    private fun scheduleClipboardReadAfterCopy(attempt: Int = 0) {
        val delay = COPY_READ_DELAYS_MS.getOrElse(attempt) { COPY_READ_DELAYS_MS.last() }
        handler.postDelayed(
            {
                val sent = readClipboardAndSend()

                if (!sent && attempt < COPY_READ_DELAYS_MS.lastIndex) {
                    scheduleClipboardReadAfterCopy(attempt + 1)
                } else if (!sent) {
                    sendTrustedAccessibilityFallback()
                }
            },
            delay
        )
    }

    private fun readClipboardAndSend(): Boolean {
        val text = try {
            val primaryClip = getSystemService(ClipboardManager::class.java).primaryClip
            if (primaryClip != null && !primaryClip.description.hasMimeType("text/*")) {
                Log.d(TAG, "Ignoring non-text clipboard content; file sync is Mac to Android only")
                return true
            }

            primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        } catch (error: SecurityException) {
            AccessibilityClipboardAutoSender.recordClipboardReadDenied(this, error)
            return false
        }

        return when {
            !text.isNullOrBlank() -> sendAndRememberOutbound(text)
            else -> {
                ZevClipPreferences.setLastAutoStatus(
                    this,
                    "Skipped: clipboard text unavailable or not text."
                )
                Log.i(TAG, "Skipping clipboard auto-send because no text was readable")
                false
            }
        }
    }

    private fun sendAndRememberOutbound(text: String): Boolean {
        lastOutboundSendRequestAt = SystemClock.elapsedRealtime()
        AccessibilityClipboardAutoSender.sendIfChanged(this, text)
        return true
    }

    private fun sendTrustedAccessibilityFallback(): Boolean {
        val fallback = recentSelectedText()?.takeIf(::isTrustedCopiedText)
            ?: recentLongPressedText()?.takeIf(::isTrustedCopiedText)

        if (fallback.isNullOrBlank()) {
            Log.i(TAG, "No trusted Accessibility text fallback found after clipboard read stayed empty")
            return false
        }

        Log.i(TAG, "Using trusted Accessibility text fallback (${fallback.length} characters)")
        clearRememberedText()
        return sendAndRememberOutbound(fallback)
    }

    private fun rememberLongPressedText(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED
        ) {
            return
        }

        val text = trustedEventText(event) ?: return
        longPressedText = text
        longPressedTextAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "Remembered trusted long-pressed text (${text.length} characters)")
    }

    private fun trustedEventText(event: AccessibilityEvent): String? {
        val source = event.source
        val candidates = buildList {
            source?.text?.toString()?.let(::add)
            event.text.mapTo(this) { it.toString() }
            source?.contentDescription?.toString()?.let(::add)
            source?.directChildTextCandidates()?.let(::addAll)
        }

        return candidates
            .asSequence()
            .map(::extractCopiedTextCandidate)
            .filter { it.isNotBlank() }
            .filter(::isTrustedCopiedText)
            .distinct()
            .maxByOrNull { it.length }
    }

    private fun AccessibilityNodeInfo.directChildTextCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        for (index in 0 until childCount) {
            val child = getChild(index) ?: continue
            child.text?.toString()?.let(candidates::add)
            child.contentDescription?.toString()?.let(candidates::add)
            child.recycle()
        }
        return candidates
    }

    private fun recentLongPressedText(): String? {
        return longPressedText?.takeIf {
            SystemClock.elapsedRealtime() - longPressedTextAt <= LONG_PRESS_TEXT_MAX_AGE_MS
        }
    }

    private fun clearRememberedText() {
        selectedText = null
        selectedTextAt = 0L
        longPressedText = null
        longPressedTextAt = 0L
    }

    private fun extractCopiedTextCandidate(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) {
            return ""
        }

        return TELEGRAM_MESSAGE_PREFIXES.firstNotNullOfOrNull { prefix ->
            normalized
                .substringAfter(prefix, missingDelimiterValue = "")
                .trim(' ', ',', ':', '-')
                .takeIf { it.isNotBlank() }
        } ?: normalized
    }

    private fun isTrustedCopiedText(text: String): Boolean {
        val normalized = text.trim()
        val lower = normalized.lowercase()

        if (normalized.isBlank() || normalized.length > MAX_TRUSTED_FALLBACK_LENGTH) {
            return false
        }

        if (lower in GENERIC_ACCESSIBILITY_LABELS) {
            return false
        }

        if (lower.startsWith("android.") || lower.startsWith("com.")) {
            return false
        }

        return normalized.length >= MIN_TRUSTED_FALLBACK_LENGTH ||
            normalized.any { it.isWhitespace() } ||
            normalized.any { it.isDigit() } ||
            normalized.any { it in TRUSTED_PUNCTUATION }
    }

    private companion object {
        const val TAG = "ZevClipAccessibility"
        const val DEBOUNCE_MS = 750L
        const val CLIPBOARD_READ_DELAY_MS = 180L
        const val COPY_SIGNAL_RECENT_MS = 2_000L
        const val PASSIVE_CLIPBOARD_ECHO_SUPPRESSION_MS = 6_000L
        const val SELECTION_MAX_AGE_MS = 15_000L
        const val LONG_PRESS_TEXT_MAX_AGE_MS = 12_000L
        const val AIRPLAY_VOLUME_TOAST_THROTTLE_MS = 2_000L
        const val MIN_TRUSTED_FALLBACK_LENGTH = 3
        const val MAX_TRUSTED_FALLBACK_LENGTH = 20_000

        val COPY_READ_DELAYS_MS = longArrayOf(90L, 220L)
        val TRUSTED_PUNCTUATION = setOf('.', ',', '?', '!', ':', ';', '-', '_', '/', '@', '#')
        val TELEGRAM_MESSAGE_PREFIXES = listOf("Message,", "message,", "Message:", "message:")
        val GENERIC_ACCESSIBILITY_LABELS = setOf(
            "message",
            "messages",
            "copy",
            "copied",
            "cut",
            "paste",
            "select all",
            "share",
            "forward",
            "reply",
            "delete",
            "edit",
            "more",
            "options",
            "selected",
            "unread",
            "photo",
            "image",
            "video",
            "audio",
            "document"
        )
    }
}
