package com.zevclip.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ZevBoardClipboardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CLIPBOARD_SYNC) {
            if (ZevClipPreferences.isZevBoardEnabled(context)) {
                val text = intent.getStringExtra(EXTRA_TEXT)
                Log.i("ZevBoardClipboardReceiver", "Received clipboard from ZevBoard")
                AccessibilityClipboardAutoSender.sendIfChanged(context, text)
            } else {
                Log.d("ZevBoardClipboardReceiver", "Ignored ZevBoard clipboard; toggle is off.")
            }
        }
    }

    companion object {
        const val ACTION_CLIPBOARD_SYNC = "com.zevclip.sender.action.ZEVBOARD_CLIPBOARD"
        const val EXTRA_TEXT = "clipboard_text"
    }
}
