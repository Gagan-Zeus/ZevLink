package com.zevclip.sender

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.Executors

class AndroidNotificationMirrorService : NotificationListenerService() {
    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZevClipNotificationMirror")
    }
    private val lastSentByKey = mutableMapOf<String, SentNotification>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        ZevClipPreferences.setNotificationMirrorState(
            this,
            isConnected = true,
            status = "Notification mirroring connected."
        )
        Log.i(TAG, "Notification mirror listener connected")
    }

    override fun onListenerDisconnected() {
        ZevClipPreferences.setNotificationMirrorState(
            this,
            isConnected = false,
            status = "Notification mirroring disconnected."
        )
        Log.i(TAG, "Notification mirror listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!ZevClipPreferences.isClipboardSyncEnabled(this)) {
            return
        }

        val payload = payload(from = sbn) ?: return
        if (shouldSkip(payload, sbn)) {
            return
        }

        networkExecutor.execute {
            val result = AndroidNotificationMirrorSender.sendSavedEndpoint(this, payload)
            val status = when (result) {
                is SendResult.Success -> "Mirrored ${payload.appName} notification."
                is SendResult.Failure -> "Notification mirror failed: ${result.message}"
            }
            ZevClipPreferences.setLastNotificationMirrorStatus(this, status)
            Log.i(TAG, status)
        }
    }

    private fun payload(from: StatusBarNotification): AndroidNotificationMirrorPayload? {
        val sbn = from
        if (sbn.packageName == packageName) {
            return null
        }

        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            return null
        }
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            return null
        }

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val subtext = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        val appName = appNameForPackage(sbn.packageName)

        if (title.isNullOrBlank() && text.isNullOrBlank() && bigText.isNullOrBlank()) {
            return null
        }

        return AndroidNotificationMirrorPayload(
            appName = appName,
            packageName = sbn.packageName,
            title = title.takeUnless { it.isNullOrBlank() },
            body = (bigText ?: text).takeUnless { it.isNullOrBlank() },
            subtext = subtext.takeUnless { it.isNullOrBlank() },
            notificationKey = sbn.key,
            postedAtMillis = sbn.postTime
        )
    }

    private fun shouldSkip(
        payload: AndroidNotificationMirrorPayload,
        sbn: StatusBarNotification
    ): Boolean {
        val key = sbn.key
        val signature = sha256(
            listOf(
                payload.packageName,
                payload.title.orEmpty(),
                payload.body.orEmpty(),
                payload.subtext.orEmpty()
            ).joinToString("|")
        )
        val now = System.currentTimeMillis()
        val lastSent = lastSentByKey[key]

        if (lastSent != null) {
            if (lastSent.signature == signature && now - lastSent.sentAtMillis < DUPLICATE_WINDOW_MS) {
                return true
            }
            if (now - lastSent.sentAtMillis < UPDATE_THROTTLE_MS) {
                return true
            }
        }

        lastSentByKey[key] = SentNotification(signature, now)
        if (lastSentByKey.size > MAX_TRACKED_NOTIFICATIONS) {
            val oldestKey = lastSentByKey.minByOrNull { it.value.sentAtMillis }?.key
            if (oldestKey != null) {
                lastSentByKey.remove(oldestKey)
            }
        }

        return false
    }

    private fun appNameForPackage(packageName: String): String {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun sha256(text: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private data class SentNotification(
        val signature: String,
        val sentAtMillis: Long
    )

    private companion object {
        const val TAG = "ZevClipNotifyMirror"
        const val DUPLICATE_WINDOW_MS = 30_000L
        const val UPDATE_THROTTLE_MS = 2_000L
        const val MAX_TRACKED_NOTIFICATIONS = 100
    }
}
