package com.zevclip.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object FileTransferNotificationCenter {
    enum class Direction(val activeVerb: String, val completeVerb: String) {
        SENDING("Sending", "Sent"),
        RECEIVING("Receiving", "Received")
    }

    private val cancelCallbacks = ConcurrentHashMap<String, () -> Unit>()

    fun registerCancelCallback(transferId: String, callback: () -> Unit) {
        cancelCallbacks[transferId] = callback
    }

    fun unregisterCancelCallback(transferId: String) {
        cancelCallbacks.remove(transferId)
    }

    fun cancelTransfer(context: Context, transferId: String) {
        cancelCallbacks.remove(transferId)?.invoke()
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(notificationId(transferId))
    }

    fun showActive(
        context: Context,
        transferId: String,
        fileName: String,
        direction: Direction,
        transferredBytes: Long,
        totalBytes: Long
    ) {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) return

        ensureChannel(appContext)
        val safeTotal = totalBytes.coerceAtLeast(0L)
        val progress = if (safeTotal == 0L) {
            PROGRESS_MAX
        } else {
            ((transferredBytes.coerceIn(0L, safeTotal).toDouble() / safeTotal.toDouble()) * PROGRESS_MAX)
                .toInt()
                .coerceIn(0, PROGRESS_MAX)
        }
        val numbers = "${Formatter.formatFileSize(appContext, transferredBytes)} / ${Formatter.formatFileSize(appContext, safeTotal)}"
        val status = when (direction) {
            Direction.SENDING -> "Sending to Mac"
            Direction.RECEIVING -> "Receiving from Mac"
        }

        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_clipboard)
            .setContentTitle(fileName)
            .setContentText("$status · $numbers")
            .setStyle(Notification.BigTextStyle().bigText("$status\n$numbers"))
            .setProgress(PROGRESS_MAX, progress, safeTotal == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    0,
                    "Cancel",
                    FileTransferNotificationActionReceiver.cancelIntent(appContext, transferId)
                ).build()
            )
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(notificationId(transferId), notification)
    }

    fun showComplete(
        context: Context,
        transferId: String,
        fileName: String,
        direction: Direction,
        openUri: Uri?,
        mimeType: String?
    ) {
        unregisterCancelCallback(transferId)
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) return

        ensureChannel(appContext)
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_clipboard)
            .setContentTitle(fileName)
            .setContentText("${direction.completeVerb} successfully")
            .setStyle(Notification.BigTextStyle().bigText("${direction.completeVerb} successfully"))
            .setOngoing(false)
            .setAutoCancel(true)
            .setShowWhen(true)

        if (direction == Direction.RECEIVING && openUri != null) {
            notification.setContentIntent(
                FileTransferNotificationActionReceiver.openIntent(appContext, transferId, openUri, mimeType)
            )
            notification.addAction(
                Notification.Action.Builder(
                    0,
                    "Open",
                    FileTransferNotificationActionReceiver.openIntent(appContext, transferId, openUri, mimeType)
                ).build()
            )
        }

        if (direction == Direction.RECEIVING) {
            notification.addAction(
                Notification.Action.Builder(
                    0,
                    "Show in Files",
                    FileTransferNotificationActionReceiver.showInFilesIntent(appContext, transferId)
                ).build()
            )
        }

        appContext.getSystemService(NotificationManager::class.java)
            .notify(notificationId(transferId), notification.build())
    }

    fun showFailed(
        context: Context,
        transferId: String,
        fileName: String,
        direction: Direction,
        message: String
    ) {
        unregisterCancelCallback(transferId)
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) return

        ensureChannel(appContext)
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_clipboard)
            .setContentTitle(fileName)
            .setContentText("${direction.activeVerb} failed")
            .setStyle(Notification.BigTextStyle().bigText("${direction.activeVerb} failed\n$message"))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(notificationId(transferId), notification)
    }

    fun clear(context: Context, transferId: String) {
        unregisterCancelCallback(transferId)
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(notificationId(transferId))
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ZevLink file transfers",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows file transfer progress and actions."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationId(transferId: String): Int {
        return NOTIFICATION_ID_BASE + abs(transferId.hashCode() % NOTIFICATION_ID_RANGE)
    }

    private const val CHANNEL_ID = "zevclip_file_transfers"
    private const val NOTIFICATION_ID_BASE = 3000
    private const val NOTIFICATION_ID_RANGE = 20_000
    private const val PROGRESS_MAX = 1000
}
