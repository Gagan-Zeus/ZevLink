package com.zevclip.sender

import android.app.PendingIntent
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
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
    private val actionIntentsByNotificationKey = mutableMapOf<String, MutableMap<String, MirroredActionIntent>>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeService = this
        ZevClipPreferences.setNotificationMirrorState(
            this,
            isConnected = true,
            status = "Notification mirroring connected."
        )
        mirrorActiveMediaNotifications()
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
        if (notification.category == Notification.CATEGORY_CALL) {
            return null
        }
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            return null
        }
        val isMediaNotification = isMediaNotification(notification)
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0 && !isMediaNotification) {
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
        if (isRedactedSensitiveNotification(title, text, bigText, subtext)) {
            rememberRecentSMSNotificationKey(sbn)
            return null
        }

        return AndroidNotificationMirrorPayload(
            event = EVENT_POSTED,
            appName = appName,
            packageName = sbn.packageName,
            appIconPngBase64 = AndroidPackageIconLoader.appIconPngBase64ForPackage(this, sbn.packageName),
            title = title.takeUnless { it.isNullOrBlank() },
            body = (bigText ?: text).takeUnless { it.isNullOrBlank() },
            subtext = subtext.takeUnless { it.isNullOrBlank() },
            actions = mirroredActionsFor(notification, sbn.key),
            notificationKey = sbn.key,
            postedAtMillis = sbn.postTime,
            isMediaNotification = isMediaNotification
        )
    }

    private fun removedPayload(from: StatusBarNotification): AndroidNotificationMirrorPayload {
        actionIntentsByNotificationKey.remove(from.key)
        return AndroidNotificationMirrorPayload(
            event = EVENT_REMOVED,
            appName = appNameForPackage(from.packageName),
            packageName = from.packageName,
            appIconPngBase64 = null,
            title = null,
            body = null,
            subtext = null,
            actions = emptyList(),
            notificationKey = from.key,
            postedAtMillis = from.postTime
        )
    }

    private fun mirrorActiveMediaNotifications() {
        if (!ZevClipPreferences.isClipboardSyncEnabled(this)) {
            return
        }

        activeNotifications
            ?.asSequence()
            ?.mapNotNull { sbn ->
                val notification = sbn.notification ?: return@mapNotNull null
                if (!isMediaNotification(notification)) {
                    return@mapNotNull null
                }
                postedPayload(from = sbn)?.let { payload -> payload to sbn }
            }
            ?.filterNot { (payload, sbn) -> shouldSkip(payload, sbn) }
            ?.forEach { (payload, _) -> send(payload) }
    }

    private fun isMediaNotification(notification: Notification): Boolean {
        return notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            notification.extras.containsKey("android.mediaSession")
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
                payload.subtext.orEmpty(),
                payload.actions.joinToString(separator = ",") { action ->
                    listOf(
                        action.id,
                        action.title,
                        action.requiresTextInput.toString(),
                        action.inputLabel.orEmpty()
                    ).joinToString(separator = ":")
                }
            ).joinToString("|")
        )
        val now = System.currentTimeMillis()
        val lastSent = lastSentByKey[key]

        if (lastSent != null) {
            if (lastSent.signature == signature && now - lastSent.sentAtMillis < DUPLICATE_WINDOW_MS) {
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

    private fun mirroredActionsFor(
        notification: Notification,
        notificationKey: String
    ): List<AndroidNotificationMirrorAction> {
        val mappedActions = mutableMapOf<String, MirroredActionIntent>()
        val actionPayloads = notification.actions
            ?.mapIndexedNotNull { index, action ->
                val title = action.title?.toString()?.trim().orEmpty()
                val actionIntent = action.actionIntent
                if (title.isBlank() || actionIntent == null) {
                    return@mapIndexedNotNull null
                }

                val actionId = "$index-${sha256(title).take(10)}"
                val remoteInputs = (action.remoteInputs ?: emptyArray()).map { it }.toTypedArray()
                mappedActions[actionId] = MirroredActionIntent(
                    actionIntent = actionIntent,
                    remoteInputs = remoteInputs
                )

                AndroidNotificationMirrorAction(
                    id = actionId,
                    title = title,
                    requiresTextInput = remoteInputs.any { it.allowFreeFormInput },
                    inputLabel = remoteInputs.firstOrNull { it.allowFreeFormInput }
                        ?.label
                        ?.toString()
                        ?.trim()
                        ?.takeUnless { it.isBlank() }
                )
            }
            .orEmpty()

        if (mappedActions.isEmpty()) {
            actionIntentsByNotificationKey.remove(notificationKey)
        } else {
            actionIntentsByNotificationKey[notificationKey] = mappedActions
        }

        pruneTrackedActions()
        return actionPayloads
    }

    private fun performMirroredNotificationAction(
        notificationKey: String,
        actionId: String,
        replyText: String?
    ): Boolean {
        val action = actionIntentsByNotificationKey[notificationKey]?.get(actionId) ?: return false
        return try {
            val fillInIntent = if (replyText != null && action.remoteInputs.isNotEmpty()) {
                Intent().also { intent ->
                    val results = Bundle()
                    action.remoteInputs
                        .filter { it.allowFreeFormInput }
                        .forEach { remoteInput ->
                            results.putCharSequence(remoteInput.resultKey, replyText)
                        }
                    RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
                }
            } else {
                null
            }

            action.actionIntent.send(this, 0, fillInIntent)
            true
        } catch (error: PendingIntent.CanceledException) {
            Log.w(TAG, "Android notification action was already cancelled", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not perform Android notification action", error)
            false
        }
    }

    private fun pruneTrackedActions() {
        if (actionIntentsByNotificationKey.size <= MAX_TRACKED_NOTIFICATIONS) {
            return
        }

        val activeKeys = lastSentByKey.entries
            .sortedByDescending { it.value.sentAtMillis }
            .take(MAX_TRACKED_NOTIFICATIONS)
            .map { it.key }
            .toSet()
        actionIntentsByNotificationKey.keys
            .filter { it !in activeKeys }
            .forEach { actionIntentsByNotificationKey.remove(it) }
    }

    private fun appNameForPackage(packageName: String): String {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun isRedactedSensitiveNotification(vararg textParts: String?): Boolean {
        val joinedText = textParts
            .filterNotNull()
            .joinToString(separator = " ")
            .trim()
            .lowercase()
        return joinedText == "sensitive notification content hidden"
            || joinedText.contains("sensitive notification content hidden")
    }

    private fun rememberRecentSMSNotificationKey(sbn: StatusBarNotification) {
        synchronized(recentSMSNotificationKeysByPackage) {
            recentSMSNotificationKeysByPackage[sbn.packageName] = RecentNotificationKey(
                key = sbn.key,
                postedAtMillis = sbn.postTime,
                recordedAtMillis = System.currentTimeMillis()
            )
        }
        OTPMessageReceiver.flushPendingOTPForPackage(applicationContext, sbn.packageName, sbn.key, sbn.postTime)
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

    private data class RecentNotificationKey(
        val key: String,
        val postedAtMillis: Long,
        val recordedAtMillis: Long
    )

    private data class MirroredActionIntent(
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>
    )

    companion object {
        private const val TAG = "ZevClipNotifyMirror"
        private const val DUPLICATE_WINDOW_MS = 30_000L
        private const val MAX_TRACKED_NOTIFICATIONS = 100
        private const val RECENT_SMS_NOTIFICATION_WINDOW_MS = 20_000L
        private const val EVENT_POSTED = "posted"
        private const val EVENT_REMOVED = "removed"

        @Volatile
        private var activeService: AndroidNotificationMirrorService? = null
        private val recentSMSNotificationKeysByPackage = mutableMapOf<String, RecentNotificationKey>()

        fun recentSMSNotificationKey(packageName: String): Pair<String, Long>? {
            val now = System.currentTimeMillis()
            return synchronized(recentSMSNotificationKeysByPackage) {
                val recent = recentSMSNotificationKeysByPackage[packageName] ?: return@synchronized null
                if (now - recent.recordedAtMillis > RECENT_SMS_NOTIFICATION_WINDOW_MS) {
                    recentSMSNotificationKeysByPackage.remove(packageName)
                    null
                } else {
                    recent.key to recent.postedAtMillis
                }
            }
        }

        fun cancelMirroredNotification(notificationKey: String): Boolean {
            val service = activeService ?: return false
            return try {
                service.actionIntentsByNotificationKey.remove(notificationKey)
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

        fun performMirroredNotificationAction(
            notificationKey: String,
            actionId: String,
            replyText: String?
        ): Boolean {
            val service = activeService ?: return false
            return service.performMirroredNotificationAction(notificationKey, actionId, replyText)
        }
    }
}
