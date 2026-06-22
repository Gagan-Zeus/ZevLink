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

class FindMyPhoneRinger(
    context: Context,
    private val onRingingChanged: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null
    private var previousAlarmVolume: Int? = null

    init {
        appContext.getSystemService(NotificationManager::class.java).apply {
            cancel(LEGACY_NOTIFICATION_ID)
            deleteNotificationChannel(LEGACY_CHANNEL_ID)
            deleteNotificationChannel(LEGACY_ALARM_CHANNEL_ID)
        }
    }

    @Synchronized
    fun start(): FindPhoneActionResult {
        if (ringtone != null) {
            return FindPhoneActionResult(true, "Phone is already ringing.")
        }

        stop()

        return try {
            if (!hasAudioPermission()) {
                return FindPhoneActionResult(
                    false,
                    "Allow Music and audio access in ZevLink on Android to use the phone's alarm sound."
                )
            }

            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )

            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(
                appContext,
                RingtoneManager.TYPE_ALARM
            ) ?: return failure("No default alarm sound is configured on this phone.")
            val newRingtone = RingtoneManager.getRingtone(appContext, alarmUri)
                ?: return failure("Android could not load the configured alarm sound.")
            newRingtone.audioAttributes =
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                newRingtone.isLooping = true
            }

            ringtone = newRingtone
            newRingtone.play()
            isRinging = true
            FindMyPhoneActivity.show(appContext)
            onRingingChanged(true)
            FindPhoneActionResult(true, "Phone is ringing until stopped.")
        } catch (error: Exception) {
            Log.w(TAG, "Could not ring phone", error)
            failure("Could not ring phone: ${error.message ?: "audio playback failed"}")
        }
    }

    @Synchronized
    fun stop(): FindPhoneActionResult {
        val wasRinging = ringtone != null
        ringtone?.runCatching { stop() }
        ringtone = null
        isRinging = false
        FindMyPhoneActivity.dismiss(appContext)
        restoreAlarmVolume()
        if (wasRinging) onRingingChanged(false)
        return FindPhoneActionResult(true, "Phone stopped ringing.")
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun failure(message: String): FindPhoneActionResult {
        restoreAlarmVolume()
        return FindPhoneActionResult(false, message)
    }

    private fun restoreAlarmVolume() {
        val volume = previousAlarmVolume ?: return
        previousAlarmVolume = null
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
        }.onFailure { error ->
            Log.w(TAG, "Could not restore alarm volume", error)
        }
    }

    companion object {
        @Volatile
        var isRinging = false
            private set

        private const val TAG = "ZevClip"
        private const val LEGACY_NOTIFICATION_ID = 1046
        private const val LEGACY_CHANNEL_ID = "zevlink_find_phone"
        private const val LEGACY_ALARM_CHANNEL_ID = "zevlink_find_phone_alarm_v2"
    }
}

data class FindPhoneActionResult(
    val success: Boolean,
    val message: String
)
