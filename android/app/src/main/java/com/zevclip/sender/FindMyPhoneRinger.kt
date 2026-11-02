package com.zevclip.sender

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

class FindMyPhoneRinger(
    context: Context,
    private val onRingingChanged: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext

    init {
        appContext.getSystemService(NotificationManager::class.java)?.apply {
            cancel(LEGACY_NOTIFICATION_ID)
            deleteNotificationChannel(LEGACY_CHANNEL_ID)
            deleteNotificationChannel(LEGACY_ALARM_CHANNEL_ID)
        }
        listeners.add(onRingingChanged)
    }

    fun start(): FindPhoneActionResult = start(appContext)

    fun stop(): FindPhoneActionResult = stop(appContext)

    companion object {
        private const val TAG = "ZevClipFindPhone"
        private const val LEGACY_NOTIFICATION_ID = 1046
        private const val LEGACY_CHANNEL_ID = "zevlink_find_phone"
        private const val LEGACY_ALARM_CHANNEL_ID = "zevlink_find_phone_alarm_v2"

        @Volatile
        var isRinging = false
            private set

        @Volatile
        private var activeRingtone: Ringtone? = null

        @Volatile
        private var savedAlarmVolume: Int? = null

        private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

        fun addListener(listener: (Boolean) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (Boolean) -> Unit) {
            listeners.remove(listener)
        }

        @Synchronized
        fun start(context: Context): FindPhoneActionResult {
            val appContext = context.applicationContext
            if (activeRingtone != null || isRinging) {
                return FindPhoneActionResult(true, "Phone is already ringing.")
            }

            stop(appContext)

            return try {
                if (!hasAudioPermission(appContext)) {
                    return FindPhoneActionResult(
                        false,
                        "Allow Music and audio access in ZevLink on Android to use the phone's alarm sound."
                    )
                }

                val audioManager = appContext.getSystemService(AudioManager::class.java)
                if (audioManager != null) {
                    savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                        0
                    )
                }

                val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(
                    appContext,
                    RingtoneManager.TYPE_ALARM
                ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE)

                val newRingtone = alarmUri?.let { RingtoneManager.getRingtone(appContext, it) }
                    ?: return failure(appContext, "Android could not load configured alarm sound.")

                newRingtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    newRingtone.isLooping = true
                }

                activeRingtone = newRingtone
                newRingtone.play()
                isRinging = true

                FindMyPhoneActivity.show(appContext)
                listeners.forEach { it(true) }

                // Notify Mac via Bluetooth if connected
                if (BluetoothClientManager.isConnected()) {
                    BluetoothClientManager.sendPresence(appContext, findPhoneRinging = true)
                }

                FindPhoneActionResult(true, "Phone is ringing until stopped.")
            } catch (error: Exception) {
                Log.w(TAG, "Could not ring phone", error)
                failure(appContext, "Could not ring phone: ${error.message ?: "audio playback failed"}")
            }
        }

        @Synchronized
        fun stop(context: Context): FindPhoneActionResult {
            val appContext = context.applicationContext
            val wasRinging = isRinging || activeRingtone != null

            try {
                activeRingtone?.stop()
            } catch (error: Exception) {
                Log.w(TAG, "Error stopping ringtone", error)
            }
            activeRingtone = null
            isRinging = false

            FindMyPhoneActivity.dismiss(appContext)
            restoreAlarmVolume(appContext)

            if (wasRinging) {
                listeners.forEach { it(false) }

                // Notify Mac via Bluetooth that phone stopped ringing
                if (BluetoothClientManager.isConnected()) {
                    BluetoothClientManager.sendPresence(appContext, findPhoneRinging = false)
                }
            }

            return FindPhoneActionResult(true, "Phone stopped ringing.")
        }

        private fun hasAudioPermission(context: Context): Boolean {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

        private fun failure(context: Context, message: String): FindPhoneActionResult {
            restoreAlarmVolume(context)
            return FindPhoneActionResult(false, message)
        }

        private fun restoreAlarmVolume(context: Context) {
            val volume = savedAlarmVolume ?: return
            savedAlarmVolume = null
            val audioManager = context.getSystemService(AudioManager::class.java) ?: return
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
            }.onFailure { error ->
                Log.w(TAG, "Could not restore alarm volume", error)
            }
        }
    }
}

data class FindPhoneActionResult(
    val success: Boolean,
    val message: String
)
