package com.zevclip.sender

import android.util.Log
import org.json.JSONObject
import java.util.Locale

object ZevPlayInputController {
    fun dispatch(context: android.content.Context, jsonText: String): Boolean {
        val json = runCatching { JSONObject(jsonText) }.getOrElse { error ->
            Log.w(TAG, "Invalid ZevPlay input command", error)
            return false
        }
        return when (json.optString("type").trim().lowercase(Locale.US)) {
            "touch" -> ClipboardAccessibilityService.injectZevPlayTouch(
                action = json.optString("action").trim().lowercase(Locale.US),
                x = json.optDouble("x", -1.0),
                y = json.optDouble("y", -1.0)
            )
            "tap" -> ClipboardAccessibilityService.injectZevPlayTap(
                x = json.optDouble("x", -1.0),
                y = json.optDouble("y", -1.0)
            )
            "swipe" -> ClipboardAccessibilityService.injectZevPlaySwipe(
                startX = json.optDouble("startX", -1.0),
                startY = json.optDouble("startY", -1.0),
                endX = json.optDouble("endX", -1.0),
                endY = json.optDouble("endY", -1.0),
                durationMs = json.optInt("durationMs", 180)
            )
            "scroll" -> ClipboardAccessibilityService.injectZevPlayScroll(
                x = json.optDouble("x", 0.5),
                y = json.optDouble("y", 0.5),
                deltaX = json.optDouble("deltaX", 0.0),
                deltaY = json.optDouble("deltaY", 0.0)
            )
            "nav" -> ClipboardAccessibilityService.injectZevPlayNavigation(
                action = json.optString("action").trim().lowercase(Locale.US)
            )
            "text" -> ClipboardAccessibilityService.injectZevPlayText(
                text = json.optString("text")
            )
            "key" -> ClipboardAccessibilityService.injectZevPlayKey(
                key = json.optString("key").trim().lowercase(Locale.US)
            )
            else -> {
                Log.w(TAG, "Unknown ZevPlay input command: ${json.optString("type")}")
                false
            }
        }.also { handled ->
            if (!handled) {
                Log.w(TAG, "ZevPlay input command was not handled: $jsonText")
            }
        }
    }

    private const val TAG = "ZevPlayInput"
}
