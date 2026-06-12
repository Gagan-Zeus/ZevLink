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
        val airPlayScreenMirroring = ZevClipPreferences.isAirPlayScreenMirroring(context)
        val airPlayBroadcastStreaming = ZevClipPreferences.isAirPlayBroadcastStreaming(context)
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
            .setOngoing((syncEnabled && receiverRunning) || airPlayStreaming || airPlayScreenMirroring || airPlayBroadcastStreaming)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)

        airPlayActions(context, airPlayStreaming, airPlayScreenMirroring, airPlayBroadcastStreaming)
            .forEach { notification.addAction(it) }

        return notification.build()
    }

    private fun connectionNotificationText(context: Context, canConnect: Boolean): String {
        val connectionText = connectionStatusText(context, canConnect)
        if (ZevClipPreferences.isAirPlayBroadcastStreaming(context)) {
            val broadcastText = ZevClipPreferences.airPlayBroadcastStatus(context)
                .takeIf { it.isNotBlank() }
                ?: context.getString(R.string.airplay_broadcast_live, 1)
            return "$connectionText\n$broadcastText"
        }

        if (!ZevClipPreferences.isAirPlayStreaming(context) &&
            !ZevClipPreferences.isAirPlayScreenMirroring(context)
        ) return connectionText

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

    private fun airPlayActions(
        context: Context,
        isStreaming: Boolean,
        isScreenMirroring: Boolean,
        isBroadcastStreaming: Boolean
    ): List<Notification.Action> {
        if (isBroadcastStreaming) {
            return listOf(Notification.Action.Builder(
                0,
                context.getString(R.string.notification_airplay_stop_broadcast_action),
                PendingIntent.getService(
                    context,
                    AIRPLAY_BROADCAST_ACTION_REQUEST_CODE,
                    AirPlayBroadcastAudioService.stopIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build())
        }

        val audioIntent = if (isStreaming) {
            PendingIntent.getService(
                context,
                AIRPLAY_AUDIO_ACTION_REQUEST_CODE,
                AirPlayAudioCaptureService.stopIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getActivity(
                context,
                AIRPLAY_AUDIO_ACTION_REQUEST_CODE,
                Intent(context, AirPlayCaptureActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_HISTORY
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val screenMirrorIntent = if (isScreenMirroring) {
            PendingIntent.getService(
                context,
                AIRPLAY_SCREEN_ACTION_REQUEST_CODE,
                AirPlayScreenMirrorService.stopIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getActivity(
                context,
                AIRPLAY_SCREEN_ACTION_REQUEST_CODE,
                Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_START_AIRPLAY_SCREEN_MIRROR
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return listOf(
            Notification.Action.Builder(
                0,
                context.getString(
                    if (isStreaming) {
                        R.string.notification_airplay_stop_audio_action
                    } else {
                        R.string.notification_airplay_audio_action
                    }
                ),
                audioIntent
            ).build(),
            Notification.Action.Builder(
                0,
                context.getString(
                    if (isScreenMirroring) {
                        R.string.notification_airplay_stop_screen_action
                    } else {
                        R.string.notification_airplay_screen_mirror_action
                    }
                ),
                screenMirrorIntent
            ).build()
        )
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
            "ZevLink status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether ZevLink clipboard sync is running."
            setShowBadge(false)
        }
        createNotificationChannel(channel)
    }

    const val CHANNEL_ID = "zevclip_status"
    const val NOTIFICATION_ID = 1042
    private const val AIRPLAY_AUDIO_ACTION_REQUEST_CODE = 1043
    private const val AIRPLAY_SCREEN_ACTION_REQUEST_CODE = 1044
    private const val AIRPLAY_BROADCAST_ACTION_REQUEST_CODE = 1045
    private const val MAC_STATUS_STALE_MS = 90 * 1000L
    private const val EXPIRY_REFRESH_GRACE_MS = 1_000L
    private val refreshHandler = Handler(Looper.getMainLooper())
}
