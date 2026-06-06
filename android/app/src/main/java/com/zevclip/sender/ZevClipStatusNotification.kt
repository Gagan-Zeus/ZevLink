package com.zevclip.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

object ZevClipStatusNotification {
    fun update(context: Context) {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) {
            return
        }

        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createStatusChannel()
        manager.notify(NOTIFICATION_ID, buildNotification(appContext))
        scheduleConnectionExpiryRefresh(appContext)
    }

    fun cancel(context: Context) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
        refreshHandler.removeCallbacksAndMessages(null)
    }

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(context: Context): Notification {
        val syncEnabled = ZevClipPreferences.isClipboardSyncEnabled(context)
        val receiverRunning = ZevClipPreferences.isAndroidReceiverRunning(context)
        val title = if (syncEnabled && receiverRunning) {
            context.getString(R.string.notification_title_running)
        } else {
            context.getString(R.string.notification_title_stopped)
        }
        val text = connectionNotificationText(context, syncEnabled && receiverRunning)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_clipboard)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(syncEnabled && receiverRunning)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun connectionNotificationText(context: Context, canConnect: Boolean): String {
        if (!canConnect) {
            return context.getString(R.string.notification_status_not_connected)
        }

        val status = ZevClipPreferences.macBatteryStatus(context)
        if (status.lastSeenAtMillis <= 0L) {
            return context.getString(R.string.notification_status_not_connected)
        }

        val ageMillis = System.currentTimeMillis() - status.lastSeenAtMillis
        if (ageMillis > MAC_STATUS_STALE_MS) {
            return context.getString(R.string.notification_status_not_connected)
        }

        val connectedText = context.getString(R.string.notification_status_connected)
        if (!status.isAvailable) return connectedText

        val percentage = status.percentage ?: return connectedText
        val chargingSuffix = if (status.isCharging) " charging" else ""
        return "$connectedText · Mac $percentage%$chargingSuffix"
    }

    private fun scheduleConnectionExpiryRefresh(context: Context) {
        refreshHandler.removeCallbacksAndMessages(null)

        if (!ZevClipPreferences.isClipboardSyncEnabled(context) ||
            !ZevClipPreferences.isAndroidReceiverRunning(context)
        ) {
            return
        }

        val status = ZevClipPreferences.macBatteryStatus(context)
        if (status.lastSeenAtMillis <= 0L) {
            return
        }

        val ageMillis = System.currentTimeMillis() - status.lastSeenAtMillis
        if (ageMillis >= MAC_STATUS_STALE_MS) {
            return
        }

        val appContext = context.applicationContext
        refreshHandler.postDelayed(
            { update(appContext) },
            MAC_STATUS_STALE_MS - ageMillis + EXPIRY_REFRESH_GRACE_MS
        )
    }

    private fun NotificationManager.createStatusChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "ZevClip status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether ZevClip clipboard sync is running."
            setShowBadge(false)
        }
        createNotificationChannel(channel)
    }

    private const val CHANNEL_ID = "zevclip_status"
    private const val NOTIFICATION_ID = 1042
    private const val MAC_STATUS_STALE_MS = 90 * 1000L
    private const val EXPIRY_REFRESH_GRACE_MS = 1_000L
    private val refreshHandler = Handler(Looper.getMainLooper())
}
