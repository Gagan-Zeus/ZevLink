package com.zevclip.sender

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ClipboardAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val clipboardChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClipboardSignalAt < DEBOUNCE_MS) {
            return@OnPrimaryClipChangedListener
        }

        lastClipboardSignalAt = now
        if (now - lastCandidateAt > CLIPBOARD_CHANGE_COPY_WINDOW_MS) {
            Log.d(TAG, "Ignoring clipboard change without a recent user copy action")
            return@OnPrimaryClipChangedListener
        }

        Log.i(
            TAG,
            "Primary clipboard changed; uiVisible=${ZevClipApplication.isUiVisible(application)}"
        )
        handler.postDelayed(
            { readClipboardAndSend(recentSelectedText()) },
            CLIPBOARD_READ_DELAY_MS
        )
    }

    private var lastCandidateSignature = ""
    private var lastCandidateAt = 0L
    private var lastClipboardSignalAt = 0L
    private var selectedText: String? = null
    private var selectedTextAt = 0L
    private var clipboardListenerRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerClipboardListener()
        ZevClipPreferences.setAccessibilityServiceState(
            this,
            bound = true,
            event = "connected",
            connectedAtMillis = System.currentTimeMillis()
        )
        Log.i(TAG, "Accessibility service connected; waiting for likely copy events")
        AccessibilityServiceStatus.logCurrentState(this, "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        rememberSelectedText(event)

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
                "package=${event.packageName} candidate=$matchingCandidate " +
                "uiVisible=${ZevClipApplication.isUiVisible(application)}"
        )

        handler.postDelayed(
            { readClipboardAndSend(recentSelectedText()) },
            CLIPBOARD_READ_DELAY_MS
        )
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

    private fun readClipboardAndSend(selectionFallback: String?) {
        val text = try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        } catch (error: SecurityException) {
            AccessibilityClipboardAutoSender.recordClipboardReadDenied(this, error)
            return
        }

        when {
            !text.isNullOrBlank() -> AccessibilityClipboardAutoSender.sendIfChanged(this, text)
            !selectionFallback.isNullOrBlank() -> {
                Log.i(TAG, "Using Accessibility selected-text fallback")
                AccessibilityClipboardAutoSender.sendIfChanged(this, selectionFallback)
            }
            else -> {
                ZevClipPreferences.setLastAutoStatus(
                    this,
                    "Skipped: clipboard text unavailable or not text."
                )
                Log.i(TAG, "Skipping clipboard auto-send because no text was readable")
            }
        }
    }

    private companion object {
        const val TAG = "ZevClipAccessibility"
        const val DEBOUNCE_MS = 750L
        const val CLIPBOARD_READ_DELAY_MS = 180L
        const val CLIPBOARD_CHANGE_COPY_WINDOW_MS = 2_000L
        const val SELECTION_MAX_AGE_MS = 15_000L
    }
}
