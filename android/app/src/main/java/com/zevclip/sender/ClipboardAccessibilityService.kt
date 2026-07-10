package com.zevclip.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
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
    private var zevPlayTextState: ZevPlayTextState? = null
    private var zevPlayTouchStroke: GestureDescription.StrokeDescription? = null
    private var zevPlayTouchPoint: Pair<Float, Float>? = null
    private var zevPlayTouchPendingPoint: Pair<Float, Float>? = null
    private var zevPlayTouchPendingEnd = false
    private var zevPlayTouchSegmentInFlight = false
    private var zevPlayTouchSequence = 0
    private var zevPlayZoomStrokeA: GestureDescription.StrokeDescription? = null
    private var zevPlayZoomStrokeB: GestureDescription.StrokeDescription? = null
    private var zevPlayZoomCenter: Pair<Float, Float>? = null
    private var zevPlayZoomRadius = 0f
    private var zevPlayZoomPendingCenter: Pair<Float, Float>? = null
    private var zevPlayZoomPendingRadius: Float? = null
    private var zevPlayZoomPendingEnd = false
    private var zevPlayZoomSegmentInFlight = false
    private var zevPlayZoomSequence = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
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
        if (activeService === this) activeService = null
        handler.removeCallbacksAndMessages(null)
        unregisterClipboardListener()
        ZevClipPreferences.setAccessibilityServiceState(this, bound = false, event = "unbound")
        Log.i(TAG, "Accessibility service unbound")
        AccessibilityServiceStatus.logCurrentState(this, "onUnbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
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

    private fun injectTap(x: Double, y: Double): Boolean {
        val point = absolutePoint(x, y) ?: return false
        val path = Path().apply {
            moveTo(point.first, point.second)
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
                .build(),
            null,
            null
        )
    }

    private fun injectSwipe(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        durationMs: Int
    ): Boolean {
        val start = absolutePoint(startX, startY) ?: return false
        val end = absolutePoint(endX, endY) ?: return false
        val path = Path().apply {
            moveTo(start.first, start.second)
            lineTo(end.first, end.second)
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        durationMs.coerceIn(45, 900).toLong()
                    )
                )
                .build(),
            null,
            null
        )
    }

    private fun injectTouch(action: String, x: Double, y: Double): Boolean {
        val point = absolutePoint(x, y) ?: return false
        return when (action) {
            "down" -> startZevPlayTouch(point)
            "move" -> continueZevPlayTouch(point, willContinue = true)
            "up", "cancel" -> continueZevPlayTouch(point, willContinue = false)
            else -> false
        }
    }

    private fun startZevPlayTouch(point: Pair<Float, Float>): Boolean {
        resetZevPlayZoom()
        zevPlayTouchSequence++
        zevPlayTouchPendingPoint = null
        zevPlayTouchPendingEnd = false
        zevPlayTouchSegmentInFlight = false
        val path = Path().apply {
            moveTo(point.first, point.second)
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            1,
            true
        )
        zevPlayTouchStroke = stroke
        zevPlayTouchPoint = point
        return dispatchZevPlayTouchStroke(stroke, zevPlayTouchSequence)
    }

    private fun continueZevPlayTouch(point: Pair<Float, Float>, willContinue: Boolean): Boolean {
        if (zevPlayTouchStroke == null) {
            return if (willContinue) startZevPlayTouch(point) else false
        }
        zevPlayTouchPendingPoint = point
        zevPlayTouchPendingEnd = zevPlayTouchPendingEnd || !willContinue
        if (!zevPlayTouchSegmentInFlight) {
            dispatchNextZevPlayTouchSegment()
        }
        return true
    }

    private fun dispatchNextZevPlayTouchSegment() {
        val targetPoint = zevPlayTouchPendingPoint ?: return
        val shouldEnd = zevPlayTouchPendingEnd
        zevPlayTouchPendingPoint = null
        zevPlayTouchPendingEnd = false
        val previousStroke = zevPlayTouchStroke ?: return
        val previousPoint = zevPlayTouchPoint ?: targetPoint
        val path = Path().apply {
            moveTo(previousPoint.first, previousPoint.second)
            lineTo(targetPoint.first, targetPoint.second)
        }
        val duration = if (previousPoint == targetPoint) 1L else ZEVPLAY_TOUCH_SEGMENT_MS
        val nextStroke = runCatching {
            previousStroke.continueStroke(path, 0, duration, !shouldEnd)
        }.getOrElse { error ->
            Log.w(TAG, "Could not continue ZevPlay touch stroke", error)
            resetZevPlayTouch()
            return
        }

        if (shouldEnd) {
            zevPlayTouchStroke = null
            zevPlayTouchPoint = null
        } else {
            zevPlayTouchStroke = nextStroke
            zevPlayTouchPoint = targetPoint
        }
        dispatchZevPlayTouchStroke(nextStroke, zevPlayTouchSequence)
    }

    private fun dispatchZevPlayTouchStroke(
        stroke: GestureDescription.StrokeDescription,
        sequence: Int
    ): Boolean {
        zevPlayTouchSegmentInFlight = true
        val dispatched = dispatchGesture(
            GestureDescription.Builder()
                .addStroke(stroke)
                .build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onZevPlayTouchSegmentFinished(sequence)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (sequence == zevPlayTouchSequence) {
                        resetZevPlayTouch()
                    }
                }
            },
            handler
        )
        if (!dispatched) {
            resetZevPlayTouch()
        }
        return dispatched
    }

    private fun onZevPlayTouchSegmentFinished(sequence: Int) {
        if (sequence != zevPlayTouchSequence) return
        zevPlayTouchSegmentInFlight = false
        if (zevPlayTouchStroke == null) {
            zevPlayTouchPendingPoint = null
            zevPlayTouchPendingEnd = false
            return
        }
        dispatchNextZevPlayTouchSegment()
    }

    private fun resetZevPlayTouch() {
        zevPlayTouchStroke = null
        zevPlayTouchPoint = null
        zevPlayTouchPendingPoint = null
        zevPlayTouchPendingEnd = false
        zevPlayTouchSegmentInFlight = false
    }

    private fun injectScroll(x: Double, y: Double, deltaX: Double, deltaY: Double): Boolean {
        if (deltaX == 0.0 && deltaY == 0.0) return false
        val bounds = displayBounds()
        val clampedX = x.coerceIn(0.05, 0.95)
        val clampedY = y.coerceIn(0.12, 0.88)
        val distanceX = (bounds.width() * deltaX.coerceIn(-1.0, 1.0) * 0.32).toFloat()
        val distanceY = (bounds.height() * deltaY.coerceIn(-1.0, 1.0) * 0.32).toFloat()
        if (kotlin.math.abs(distanceX) < 1f && kotlin.math.abs(distanceY) < 1f) return false
        val startY = (bounds.top + bounds.height() * clampedY).toFloat()
        val startX = (bounds.left + bounds.width() * clampedX).toFloat()
        val endY = (startY + distanceY).coerceIn(bounds.top + 8f, bounds.bottom - 8f)
        val endX = (startX + distanceX).coerceIn(bounds.left + 8f, bounds.right - 8f)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ZEVPLAY_SCROLL_TICK_MS))
                .build(),
            null,
            null
        )
    }

    private fun injectZoom(action: String, x: Double, y: Double, magnification: Double): Boolean {
        val center = boundedZoomCenter(x, y) ?: return false
        return when (action) {
            "begin" -> startZevPlayZoom(center)
            "move" -> continueZevPlayZoom(center, magnification, willContinue = true)
            "end", "cancel" -> continueZevPlayZoom(center, 0.0, willContinue = false)
            else -> false
        }
    }

    private fun startZevPlayZoom(center: Pair<Float, Float>): Boolean {
        resetZevPlayTouch()
        val radius = initialZoomRadius(center) ?: return false
        zevPlayZoomSequence++
        zevPlayZoomPendingCenter = null
        zevPlayZoomPendingRadius = null
        zevPlayZoomPendingEnd = false
        zevPlayZoomSegmentInFlight = false
        val strokeA = zoomStroke(center, radius, -1f, willContinue = true)
        val strokeB = zoomStroke(center, radius, 1f, willContinue = true)
        zevPlayZoomStrokeA = strokeA
        zevPlayZoomStrokeB = strokeB
        zevPlayZoomCenter = center
        zevPlayZoomRadius = radius
        return dispatchZevPlayZoomStrokes(strokeA, strokeB, zevPlayZoomSequence)
    }

    private fun continueZevPlayZoom(
        center: Pair<Float, Float>,
        magnification: Double,
        willContinue: Boolean
    ): Boolean {
        if (zevPlayZoomStrokeA == null || zevPlayZoomStrokeB == null) {
            return if (willContinue) startZevPlayZoom(center) else false
        }
        val nextRadius = nextZoomRadius(center, magnification) ?: return false
        zevPlayZoomPendingCenter = center
        zevPlayZoomPendingRadius = nextRadius
        zevPlayZoomPendingEnd = zevPlayZoomPendingEnd || !willContinue
        if (!zevPlayZoomSegmentInFlight) {
            dispatchNextZevPlayZoomSegment()
        }
        return true
    }

    private fun dispatchNextZevPlayZoomSegment() {
        val targetCenter = zevPlayZoomPendingCenter ?: return
        val targetRadius = zevPlayZoomPendingRadius ?: return
        val shouldEnd = zevPlayZoomPendingEnd
        zevPlayZoomPendingCenter = null
        zevPlayZoomPendingRadius = null
        zevPlayZoomPendingEnd = false
        val previousStrokeA = zevPlayZoomStrokeA ?: return
        val previousStrokeB = zevPlayZoomStrokeB ?: return
        val previousCenter = zevPlayZoomCenter ?: targetCenter
        val previousRadius = zevPlayZoomRadius
        val pathA = zoomPath(previousCenter, previousRadius, targetCenter, targetRadius, -1f)
        val pathB = zoomPath(previousCenter, previousRadius, targetCenter, targetRadius, 1f)
        val distance = kotlin.math.abs(targetRadius - previousRadius) +
            kotlin.math.abs(targetCenter.first - previousCenter.first) +
            kotlin.math.abs(targetCenter.second - previousCenter.second)
        val duration = if (distance < 1f) 1L else ZEVPLAY_ZOOM_SEGMENT_MS
        val nextStrokeA = runCatching {
            previousStrokeA.continueStroke(pathA, 0, duration, !shouldEnd)
        }.getOrElse { error ->
            Log.w(TAG, "Could not continue first ZevPlay zoom stroke", error)
            resetZevPlayZoom()
            return
        }
        val nextStrokeB = runCatching {
            previousStrokeB.continueStroke(pathB, 0, duration, !shouldEnd)
        }.getOrElse { error ->
            Log.w(TAG, "Could not continue second ZevPlay zoom stroke", error)
            resetZevPlayZoom()
            return
        }

        if (shouldEnd) {
            zevPlayZoomStrokeA = null
            zevPlayZoomStrokeB = null
            zevPlayZoomCenter = null
            zevPlayZoomRadius = 0f
        } else {
            zevPlayZoomStrokeA = nextStrokeA
            zevPlayZoomStrokeB = nextStrokeB
            zevPlayZoomCenter = targetCenter
            zevPlayZoomRadius = targetRadius
        }
        dispatchZevPlayZoomStrokes(nextStrokeA, nextStrokeB, zevPlayZoomSequence)
    }

    private fun dispatchZevPlayZoomStrokes(
        strokeA: GestureDescription.StrokeDescription,
        strokeB: GestureDescription.StrokeDescription,
        sequence: Int
    ): Boolean {
        zevPlayZoomSegmentInFlight = true
        val dispatched = dispatchGesture(
            GestureDescription.Builder()
                .addStroke(strokeA)
                .addStroke(strokeB)
                .build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onZevPlayZoomSegmentFinished(sequence)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (sequence == zevPlayZoomSequence) {
                        resetZevPlayZoom()
                    }
                }
            },
            handler
        )
        if (!dispatched) {
            resetZevPlayZoom()
        }
        return dispatched
    }

    private fun onZevPlayZoomSegmentFinished(sequence: Int) {
        if (sequence != zevPlayZoomSequence) return
        zevPlayZoomSegmentInFlight = false
        if (zevPlayZoomStrokeA == null || zevPlayZoomStrokeB == null) {
            zevPlayZoomPendingCenter = null
            zevPlayZoomPendingRadius = null
            zevPlayZoomPendingEnd = false
            return
        }
        dispatchNextZevPlayZoomSegment()
    }

    private fun resetZevPlayZoom() {
        zevPlayZoomStrokeA = null
        zevPlayZoomStrokeB = null
        zevPlayZoomCenter = null
        zevPlayZoomRadius = 0f
        zevPlayZoomPendingCenter = null
        zevPlayZoomPendingRadius = null
        zevPlayZoomPendingEnd = false
        zevPlayZoomSegmentInFlight = false
    }

    private fun boundedZoomCenter(x: Double, y: Double): Pair<Float, Float>? {
        val bounds = displayBounds()
        val minDimension = minOf(bounds.width(), bounds.height()).toFloat()
        if (minDimension <= 0f) return null
        val margin = maxOf(12f, minDimension * 0.08f)
        val center = absolutePoint(x, y) ?: return null
        return center.first.coerceIn(bounds.left + margin, bounds.right - margin) to
            center.second.coerceIn(bounds.top + margin, bounds.bottom - margin)
    }

    private fun initialZoomRadius(center: Pair<Float, Float>): Float? {
        val bounds = displayBounds()
        val availableRadius = availableZoomRadius(bounds, center)
        if (availableRadius < ZEVPLAY_ZOOM_MIN_RADIUS_PX) return null
        val minDimension = minOf(bounds.width(), bounds.height()).toFloat()
        return (minDimension * 0.18f).coerceIn(ZEVPLAY_ZOOM_MIN_RADIUS_PX, availableRadius)
    }

    private fun nextZoomRadius(center: Pair<Float, Float>, magnification: Double): Float? {
        val bounds = displayBounds()
        val availableRadius = availableZoomRadius(bounds, center)
        if (availableRadius < ZEVPLAY_ZOOM_MIN_RADIUS_PX) return null
        val minDimension = minOf(bounds.width(), bounds.height()).toFloat()
        val radiusDelta = (minDimension * magnification * ZEVPLAY_ZOOM_RADIUS_SCALE).toFloat()
        return (zevPlayZoomRadius + radiusDelta).coerceIn(ZEVPLAY_ZOOM_MIN_RADIUS_PX, availableRadius)
    }

    private fun availableZoomRadius(bounds: Rect, center: Pair<Float, Float>): Float {
        return minOf(
            center.first - bounds.left - 8f,
            bounds.right - center.first - 8f,
            center.second - bounds.top - 8f,
            bounds.bottom - center.second - 8f
        ).coerceAtLeast(0f)
    }

    private fun zoomStroke(
        center: Pair<Float, Float>,
        radius: Float,
        direction: Float,
        willContinue: Boolean
    ): GestureDescription.StrokeDescription {
        val point = zoomPoint(center, radius, direction)
        val path = Path().apply {
            moveTo(point.first, point.second)
        }
        return GestureDescription.StrokeDescription(path, 0, 1, willContinue)
    }

    private fun zoomPath(
        startCenter: Pair<Float, Float>,
        startRadius: Float,
        endCenter: Pair<Float, Float>,
        endRadius: Float,
        direction: Float
    ): Path {
        val start = zoomPoint(startCenter, startRadius, direction)
        val end = zoomPoint(endCenter, endRadius, direction)
        return Path().apply {
            moveTo(start.first, start.second)
            lineTo(end.first, end.second)
        }
    }

    private fun zoomPoint(center: Pair<Float, Float>, radius: Float, direction: Float): Pair<Float, Float> {
        val offset = radius * ZEVPLAY_ZOOM_DIAGONAL
        return center.first + direction * offset to center.second + direction * offset
    }

    private fun injectNavigation(action: String): Boolean {
        val globalAction = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return false
        }
        return performGlobalAction(globalAction)
    }

    private fun injectText(text: String): Boolean {
        if (text.isEmpty()) return false
        val node = focusedEditableNode() ?: return false
        val edit = zevPlayEditableState(node)
        val low = minOf(edit.selectionStart, edit.selectionEnd).coerceIn(0, edit.text.length)
        val high = maxOf(edit.selectionStart, edit.selectionEnd).coerceIn(0, edit.text.length)
        val next = edit.text.replaceRange(low, high, text)
        val nextCursor = (low + text.length).coerceIn(0, next.length)
        return setNodeText(node, next).also { success ->
            if (success) {
                setNodeSelection(node, nextCursor)
                rememberZevPlayText(edit.signature, next, nextCursor, nextCursor)
            }
        }
    }

    private fun injectKey(key: String): Boolean {
        val node = focusedEditableNode()
        return when (key) {
            "delete" -> {
                node ?: return false
                val edit = zevPlayEditableState(node)
                val current = edit.text
                val low = minOf(edit.selectionStart, edit.selectionEnd).coerceIn(0, current.length)
                val high = maxOf(edit.selectionStart, edit.selectionEnd).coerceIn(0, current.length)
                val next = if (low != high) {
                    current.removeRange(low, high)
                } else if (low > 0) {
                    current.removeRange(low - 1, low)
                } else {
                    current
                }
                val nextCursor = (if (low != high) low else maxOf(0, low - 1)).coerceIn(0, next.length)
                setNodeText(node, next).also { success ->
                    if (success) {
                        setNodeSelection(node, nextCursor)
                        rememberZevPlayText(edit.signature, next, nextCursor, nextCursor)
                    }
                }
            }
            "enter" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && node != null) {
                    if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                        return true
                    }
                }
                injectText("\n")
            }
            "tab" -> injectText("\t")
            "arrow_left" -> moveCursor(node, -1, extendSelection = false)
            "arrow_right" -> moveCursor(node, 1, extendSelection = false)
            "arrow_up" -> moveCursorToBoundary(node, toStart = true)
            "arrow_down" -> moveCursorToBoundary(node, toStart = false)
            else -> false
        }
    }

    private fun injectShortcut(action: String): Boolean {
        val node = focusedEditableNode() ?: return false
        return when (action) {
            "select_all" -> {
                val edit = zevPlayEditableState(node)
                setNodeSelection(node, 0, edit.text.length).also { success ->
                    if (success) {
                        rememberZevPlayText(edit.signature, edit.text, 0, edit.text.length)
                    }
                }
            }
            "copy" -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            "cut" -> {
                val edit = zevPlayEditableState(node)
                node.performAction(AccessibilityNodeInfo.ACTION_CUT).also { success ->
                    if (success) {
                        val low = minOf(edit.selectionStart, edit.selectionEnd).coerceIn(0, edit.text.length)
                        val high = maxOf(edit.selectionStart, edit.selectionEnd).coerceIn(0, edit.text.length)
                        if (low != high) {
                            val next = edit.text.removeRange(low, high)
                            rememberZevPlayText(edit.signature, next, low, low, allowStaleActual = true)
                        } else {
                            zevPlayTextState = null
                        }
                    }
                }
            }
            "paste" -> {
                val edit = zevPlayEditableState(node)
                val pasteText = clipboardTextForPaste()
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE).also { success ->
                    if (success) {
                        if (pasteText != null) {
                            val low = minOf(edit.selectionStart, edit.selectionEnd).coerceIn(0, edit.text.length)
                            val high = maxOf(edit.selectionStart, edit.selectionEnd).coerceIn(0, edit.text.length)
                            val next = edit.text.replaceRange(low, high, pasteText)
                            val nextCursor = (low + pasteText.length).coerceIn(0, next.length)
                            rememberZevPlayText(
                                edit.signature,
                                next,
                                nextCursor,
                                nextCursor,
                                allowStaleActual = true
                            )
                        } else {
                            zevPlayTextState = null
                        }
                    }
                }
            }
            else -> false
        }
    }

    private fun moveCursor(node: AccessibilityNodeInfo?, delta: Int, extendSelection: Boolean): Boolean {
        node ?: return false
        val edit = zevPlayEditableState(node)
        val anchor = if (delta < 0) {
            minOf(edit.selectionStart, edit.selectionEnd)
        } else {
            maxOf(edit.selectionStart, edit.selectionEnd)
        }
        val next = (anchor + delta).coerceIn(0, edit.text.length)
        val start = if (extendSelection) edit.selectionStart else next
        val end = next
        return setNodeSelection(node, start, end).also { success ->
            if (success) {
                rememberZevPlayText(edit.signature, edit.text, start, end)
            }
        }
    }

    private fun moveCursorToBoundary(node: AccessibilityNodeInfo?, toStart: Boolean): Boolean {
        node ?: return false
        val edit = zevPlayEditableState(node)
        val next = if (toStart) 0 else edit.text.length
        return setNodeSelection(node, next, next).also { success ->
            if (success) {
                rememberZevPlayText(edit.signature, edit.text, next, next)
            }
        }
    }

    private fun zevPlayEditableState(node: AccessibilityNodeInfo): ZevPlayEditableState {
        val signature = zevPlayNodeSignature(node)
        val rawText = node.text?.toString().orEmpty()
        val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString().orEmpty()
        } else {
            ""
        }
        val isShowingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val hasSelection = node.textSelectionStart >= 0 && node.textSelectionEnd >= 0
        val accessibleTextIsHint = isShowingHint ||
            hintText.isNotEmpty() && rawText == hintText ||
            looksLikeEditablePlaceholder(rawText, hintText, node, hasSelection)
        val visibleText = if (accessibleTextIsHint || (!hasSelection && zevPlayTextState?.signature != signature)) {
            ""
        } else {
            rawText
        }

        val now = SystemClock.elapsedRealtime()
        val remembered = zevPlayTextState?.takeIf {
            it.signature == signature &&
                now - it.updatedAtMillis <= ZEVPLAY_TEXT_STATE_MAX_AGE_MS &&
                (accessibleTextIsHint || visibleText.isEmpty() || visibleText == it.text || now <= it.allowStaleActualUntilMillis)
        }
        if (remembered != null) {
            val start = remembered.selectionStart.coerceIn(0, remembered.text.length)
            val end = remembered.selectionEnd.coerceIn(0, remembered.text.length)
            return ZevPlayEditableState(signature, remembered.text, start, end)
        }

        val start = if (hasSelection) node.textSelectionStart else visibleText.length
        val end = if (hasSelection) node.textSelectionEnd else start
        return ZevPlayEditableState(
            signature = signature,
            text = visibleText,
            selectionStart = start.coerceIn(0, visibleText.length),
            selectionEnd = end.coerceIn(0, visibleText.length)
        )
    }

    private fun rememberZevPlayText(
        signature: String,
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        allowStaleActual: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        zevPlayTextState = ZevPlayTextState(
            signature = signature,
            text = text,
            selectionStart = selectionStart.coerceIn(0, text.length),
            selectionEnd = selectionEnd.coerceIn(0, text.length),
            updatedAtMillis = now,
            allowStaleActualUntilMillis = if (allowStaleActual) {
                now + ZEVPLAY_TEXT_STALE_ACTUAL_GRACE_MS
            } else {
                0L
            }
        )
    }

    private fun clipboardTextForPaste(): String? {
        return try {
            getSystemService(ClipboardManager::class.java).primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
        } catch (error: SecurityException) {
            Log.w(TAG, "Could not read clipboard while predicting ZevPlay paste", error)
            null
        }
    }

    private fun zevPlayNodeSignature(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return listOf(
            node.packageName?.toString().orEmpty(),
            node.viewIdResourceName.orEmpty(),
            node.className?.toString().orEmpty(),
            "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        ).joinToString("|")
    }

    private fun focusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf {
            it.isEditable || it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        }
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun setNodeSelection(node: AccessibilityNodeInfo, cursor: Int): Boolean {
        return setNodeSelection(node, cursor, cursor)
    }

    private fun setNodeSelection(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun looksLikeEditablePlaceholder(
        rawText: String,
        hintText: String,
        node: AccessibilityNodeInfo,
        hasSelection: Boolean
    ): Boolean {
        val normalized = rawText.trim().lowercase()
            .replace(Regex("\\s+"), " ")
            .trim('.', ':')
        if (normalized.isEmpty()) return false
        val hintNormalized = hintText.trim().lowercase().replace(Regex("\\s+"), " ").trim('.', ':')
        if (hintNormalized.isNotEmpty() && normalized == hintNormalized) return true
        val selectionAtStart = !hasSelection || node.textSelectionStart <= 0 && node.textSelectionEnd <= 0
        return selectionAtStart && normalized in ZEVPLAY_PLACEHOLDER_TEXTS
    }

    private fun absolutePoint(x: Double, y: Double): Pair<Float, Float>? {
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
        val bounds = displayBounds()
        return (bounds.left + bounds.width() * x).toFloat() to
            (bounds.top + bounds.height() * y).toFloat()
    }

    private fun displayBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = getSystemService(android.view.WindowManager::class.java)
            manager?.currentWindowMetrics?.bounds?.let { return Rect(it) }
        }
        val metrics = resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
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
            event.text.forEach { addCandidate(it) }
            addCandidate(event.contentDescription)
            addCandidate(event.className)

            event.source?.let { source ->
                addCandidate(source.text)
                addCandidate(source.contentDescription)
                addCandidate(source.className)
            }
        }

        return candidates
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun MutableList<String>.addCandidate(value: Any?) {
        value?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
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
            addCandidate(source?.text)
            event.text.forEach { addCandidate(it) }
            addCandidate(source?.contentDescription)
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

    companion object {
        @Volatile
        private var activeService: ClipboardAccessibilityService? = null

        fun injectZevPlayTap(x: Double, y: Double): Boolean {
            return activeService?.postInput { injectTap(x, y) } ?: false
        }

        fun injectZevPlayTouch(action: String, x: Double, y: Double): Boolean {
            return activeService?.postInput { injectTouch(action, x, y) } ?: false
        }

        fun injectZevPlaySwipe(
            startX: Double,
            startY: Double,
            endX: Double,
            endY: Double,
            durationMs: Int
        ): Boolean {
            return activeService?.postInput {
                injectSwipe(startX, startY, endX, endY, durationMs)
            } ?: false
        }

        fun injectZevPlayScroll(x: Double, y: Double, deltaX: Double, deltaY: Double): Boolean {
            return activeService?.postInput { injectScroll(x, y, deltaX, deltaY) } ?: false
        }

        fun injectZevPlayZoom(action: String, x: Double, y: Double, magnification: Double): Boolean {
            return activeService?.postInput { injectZoom(action, x, y, magnification) } ?: false
        }

        fun injectZevPlayNavigation(action: String): Boolean {
            return activeService?.postInput { injectNavigation(action) } ?: false
        }

        fun injectZevPlayShortcut(action: String): Boolean {
            return activeService?.postInput { injectShortcut(action) } ?: false
        }

        fun injectZevPlayText(text: String): Boolean {
            return activeService?.postInput { injectText(text) } ?: false
        }

        fun injectZevPlayKey(key: String): Boolean {
            return activeService?.postInput { injectKey(key) } ?: false
        }

        private fun ClipboardAccessibilityService.postInput(block: ClipboardAccessibilityService.() -> Boolean): Boolean {
            handler.post {
                runCatching { block() }
                    .onFailure { Log.w(TAG, "ZevPlay input injection failed", it) }
            }
            return true
        }

        const val TAG = "ZevClipAccessibility"
        const val DEBOUNCE_MS = 750L
        const val CLIPBOARD_READ_DELAY_MS = 180L
        const val COPY_SIGNAL_RECENT_MS = 2_000L
        const val PASSIVE_CLIPBOARD_ECHO_SUPPRESSION_MS = 6_000L
        const val SELECTION_MAX_AGE_MS = 15_000L
        const val LONG_PRESS_TEXT_MAX_AGE_MS = 12_000L
        const val AIRPLAY_VOLUME_TOAST_THROTTLE_MS = 2_000L
        const val ZEVPLAY_TEXT_STATE_MAX_AGE_MS = 15_000L
        const val ZEVPLAY_TEXT_STALE_ACTUAL_GRACE_MS = 1_500L
        const val ZEVPLAY_TOUCH_SEGMENT_MS = 16L
        const val ZEVPLAY_SCROLL_TICK_MS = 55L
        const val ZEVPLAY_ZOOM_SEGMENT_MS = 16L
        const val ZEVPLAY_ZOOM_MIN_RADIUS_PX = 36f
        const val ZEVPLAY_ZOOM_RADIUS_SCALE = 0.95f
        const val ZEVPLAY_ZOOM_DIAGONAL = 0.70710677f
        const val MIN_TRUSTED_FALLBACK_LENGTH = 3
        const val MAX_TRUSTED_FALLBACK_LENGTH = 20_000

        val COPY_READ_DELAYS_MS = longArrayOf(90L, 220L)
        val TRUSTED_PUNCTUATION = setOf('.', ',', '?', '!', ':', ';', '-', '_', '/', '@', '#')
        val TELEGRAM_MESSAGE_PREFIXES = listOf("Message,", "message,", "Message:", "message:")
        val ZEVPLAY_PLACEHOLDER_TEXTS = setOf(
            "search",
            "search…",
            "search...",
            "search or start a new chat",
            "type a message",
            "message",
            "write a message"
        )
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

    private data class ZevPlayEditableState(
        val signature: String,
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private data class ZevPlayTextState(
        val signature: String,
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val updatedAtMillis: Long,
        val allowStaleActualUntilMillis: Long
    )
}
