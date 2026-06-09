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
import com.zevclip.sender.airplay.AirPlayFeatureGate

object ZevClipStatusNotification {
    fun update(context: Context) {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) {
            return
        }

        appContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(appContext))
        scheduleConnectionExpiryRefresh(appContext)
    }

    fun ensureChannel(context: Context) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .createStatusChannel()
    }

    fun build(context: Context): Notification {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        return buildNotification(appContext)
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
        val airPlayStreaming = ZevClipPreferences.isAirPlayStreaming(context)
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

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_clipboard)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing((syncEnabled && receiverRunning) || airPlayStreaming)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)

        if (AirPlayFeatureGate.isEnabled(context)) {
            notification.addAction(airPlayAction(context, airPlayStreaming))
        }

        return notification.build()
    }

    private fun connectionNotificationText(context: Context, canConnect: Boolean): String {
        val connectionText = connectionStatusText(context, canConnect)
        if (!ZevClipPreferences.isAirPlayStreaming(context)) return connectionText

        val airPlayText = ZevClipPreferences.airPlayTestStatus(context)
            .takeIf { it.isNotBlank() }
            ?: context.getString(R.string.airplay_streaming_live)
        return "$connectionText\n$airPlayText"
    }

    private fun connectionStatusText(context: Context, canConnect: Boolean): String {
        if (!canConnect) return context.getString(R.string.notification_status_not_connected)

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

    private fun airPlayAction(context: Context, isStreaming: Boolean): Notification.Action {
        val intent = if (isStreaming) {
            PendingIntent.getService(
                context,
                AIRPLAY_ACTION_REQUEST_CODE,
                AirPlayAudioCaptureService.stopIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getActivity(
                context,
                AIRPLAY_ACTION_REQUEST_CODE,
                Intent(context, AirPlayCaptureActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val title = context.getString(
            if (isStreaming) R.string.stop_airplay_audio else R.string.start_airplay_audio
        )
        return Notification.Action.Builder(0, title, intent).build()
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

    const val CHANNEL_ID = "zevclip_status"
    const val NOTIFICATION_ID = 1042
    private const val AIRPLAY_ACTION_REQUEST_CODE = 1043
    private const val MAC_STATUS_STALE_MS = 90 * 1000L
    private const val EXPIRY_REFRESH_GRACE_MS = 1_000L
    private val refreshHandler = Handler(Looper.getMainLooper())
}
