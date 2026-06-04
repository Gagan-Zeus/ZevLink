package com.zevclip.sender

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Intent
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
    private var lastFocusedReadAt = 0L
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
            Log.w(TAG, "Android denied background clipboard read; trying focused fallback", error)
            openFocusedClipboardReader()
            return
        }

        when {
            !text.isNullOrBlank() -> AccessibilityClipboardAutoSender.sendIfChanged(this, text)
            !selectionFallback.isNullOrBlank() -> {
                Log.i(TAG, "Using Accessibility selected-text fallback")
                AccessibilityClipboardAutoSender.sendIfChanged(this, selectionFallback)
            }
            else -> openFocusedClipboardReader()
        }
    }

    private fun openFocusedClipboardReader() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFocusedReadAt < FOCUSED_READ_DEBOUNCE_MS) {
            return
        }
        lastFocusedReadAt = now

        ZevClipPreferences.setLastAutoStatus(
            this,
            "Android hid the background clipboard; trying focused auto-sync."
        )
        Log.i(TAG, "Launching focused clipboard reader for OEM-restricted clipboard access")

        val intent = Intent(this, AccessibilityClipboardCaptureActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }

        try {
            startActivity(intent)
        } catch (error: RuntimeException) {
            AccessibilityClipboardAutoSender.recordFocusedReadFailure(this, error)
        }
    }

    private companion object {
        const val TAG = "ZevClipAccessibility"
        const val DEBOUNCE_MS = 750L
        const val CLIPBOARD_READ_DELAY_MS = 180L
        const val FOCUSED_READ_DEBOUNCE_MS = 1_500L
        const val SELECTION_MAX_AGE_MS = 15_000L
    }
}
