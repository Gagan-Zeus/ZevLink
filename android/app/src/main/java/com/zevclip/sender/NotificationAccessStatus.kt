package com.zevclip.sender

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object NotificationAccessStatus {
    fun isEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(context, AndroidNotificationMirrorService::class.java)
            .flattenToString()
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        return enabledListeners
            .split(':')
            .any { it.equals(expectedComponent, ignoreCase = true) }
    }
}
