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
        activeService = this
        ZevClipPreferences.setNotificationMirrorState(
            this,
            isConnected = true,
            status = "Notification mirroring connected."
        )
        Log.i(TAG, "Notification mirror listener connected")
    }

    override fun onListenerDisconnected() {
        if (activeService === this) {
            activeService = null
        }
        ZevClipPreferences.setNotificationMirrorState(
            this,
            isConnected = false,
            status = "Notification mirroring disconnected."
        )
        Log.i(TAG, "Notification mirror listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!ZevClipPreferences.isClipboardSyncEnabled(this)) {
            return
        }

        val payload = postedPayload(from = sbn) ?: return
        if (shouldSkip(payload, sbn)) {
            return
        }

        send(payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!ZevClipPreferences.isClipboardSyncEnabled(this)) {
            return
        }
        if (sbn.packageName == packageName) {
            return
        }

        send(removedPayload(from = sbn))
    }

    private fun postedPayload(from: StatusBarNotification): AndroidNotificationMirrorPayload? {
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
            event = EVENT_POSTED,
            appName = appName,
            packageName = sbn.packageName,
            title = title.takeUnless { it.isNullOrBlank() },
            body = (bigText ?: text).takeUnless { it.isNullOrBlank() },
            subtext = subtext.takeUnless { it.isNullOrBlank() },
            notificationKey = sbn.key,
            postedAtMillis = sbn.postTime
        )
    }

    private fun removedPayload(from: StatusBarNotification): AndroidNotificationMirrorPayload {
        return AndroidNotificationMirrorPayload(
            event = EVENT_REMOVED,
            appName = appNameForPackage(from.packageName),
            packageName = from.packageName,
            title = null,
            body = null,
            subtext = null,
            notificationKey = from.key,
            postedAtMillis = from.postTime
        )
    }

    private fun send(payload: AndroidNotificationMirrorPayload) {
        networkExecutor.execute {
            val result = AndroidNotificationMirrorSender.sendSavedEndpoint(this, payload)
            val status = when (result) {
                is SendResult.Success -> {
                    if (payload.event == EVENT_REMOVED) {
                        "Cleared mirrored ${payload.appName} notification."
                    } else {
                        "Mirrored ${payload.appName} notification."
                    }
                }
                is SendResult.Failure -> "Notification mirror failed: ${result.message}"
            }
            ZevClipPreferences.setLastNotificationMirrorStatus(this, status)
            Log.i(TAG, status)
        }
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

    companion object {
        private const val TAG = "ZevClipNotifyMirror"
        private const val DUPLICATE_WINDOW_MS = 30_000L
        private const val UPDATE_THROTTLE_MS = 2_000L
        private const val MAX_TRACKED_NOTIFICATIONS = 100
        private const val EVENT_POSTED = "posted"
        private const val EVENT_REMOVED = "removed"

        @Volatile
        private var activeService: AndroidNotificationMirrorService? = null

        fun cancelMirroredNotification(notificationKey: String): Boolean {
            val service = activeService ?: return false
            return try {
                service.cancelNotification(notificationKey)
                true
            } catch (error: SecurityException) {
                Log.w(TAG, "Android denied notification cancellation", error)
                false
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not cancel Android notification", error)
                false
            }
        }
    }
}
