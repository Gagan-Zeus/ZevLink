package com.zevclip.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ZevClipBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) {
            return
        }

        val appContext = context.applicationContext
        if (!ZevClipPreferences.isClipboardSyncEnabled(appContext)) {
            ZevClipPreferences.setLastAutoStatus(appContext, "Clipboard sync is off after restart.")
            ZevClipStatusNotification.update(appContext)
            return
        }

        ZevClipPreferences.setLastAutoStatus(appContext, "Restarting clipboard sync after phone restart.")
        ZevClipStatusNotification.update(appContext)

        try {
            AndroidClipboardReceiverService.start(appContext)
            AndroidCallMirrorService.start(appContext)
            Log.i(TAG, "Restarted ZevClip services after $action")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not restart ZevClip services after $action", error)
            ZevClipPreferences.setLastAutoStatus(
                appContext,
                "Android blocked ZevLink autostart. Allow Auto start or unrestricted battery."
            )
            ZevClipStatusNotification.update(appContext)
        }
    }

    private companion object {
        private const val TAG = "ZevClipBootReceiver"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
