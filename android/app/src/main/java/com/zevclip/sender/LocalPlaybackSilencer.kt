package com.zevclip.sender

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.io.Closeable

class LocalPlaybackSilencer(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    private val originallyMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
    private val mediaVolumeGroupId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        audioManager.getVolumeGroupIdForAttributes(mediaAttributes)
    } else {
        null
    }
    private var active = false
    private var closed = false
    private var receiverRegistered = false
    private var observerRegistered = false
    private var lastToastAtMs = 0L

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active || intent?.action != VOLUME_CHANGED_ACTION) return
            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (streamType == -1 || streamType == AudioManager.STREAM_MUSIC) {
                val volume = intent.getIntExtra(
                    EXTRA_VOLUME_STREAM_VALUE,
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                )
                val previousVolume = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, volume)
                if (volume > previousVolume && volume > 0) {
                    showBlockedVolumeToast()
                }
                enforceSilenceBurst()
            }
        }
    }

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (active) enforceSilenceBurst()
        }
    }

    private val periodicEnforce = object : Runnable {
        override fun run() {
            if (!active) return
            forceSilent()
            handler.postDelayed(this, ENFORCE_INTERVAL_MS)
        }
    }

    fun start() {
        if (closed || active) return
        active = true
        Log.i(TAG, "Starting local playback silencer. originalVolume=$originalVolume originallyMuted=$originallyMuted mediaVolumeGroupId=$mediaVolumeGroupId")
        registerVolumeReceiver()
        registerVolumeObserver()
        forceSilent()
        handler.postDelayed(periodicEnforce, ENFORCE_INTERVAL_MS)
    }

    private fun enforceSilenceBurst() {
        handler.removeCallbacks(periodicEnforce)
        ENFORCE_BURST_DELAYS_MS.forEach { delayMs ->
            handler.postDelayed({
                if (active) forceSilent()
            }, delayMs)
        }
        handler.postDelayed({
            if (!active) return@postDelayed
            forceSilent()
            handler.postDelayed(periodicEnforce, ENFORCE_INTERVAL_MS)
        }, ENFORCE_BURST_RESUME_MS)
    }

    private fun showBlockedVolumeToast() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToastAtMs < TOAST_THROTTLE_MS) return
        lastToastAtMs = now
        Toast.makeText(
            appContext,
            appContext.getString(R.string.airplay_local_volume_blocked),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun forceSilent() {
        if (appContext.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot silence local playback; MODIFY_AUDIO_SETTINGS is not granted.")
            return
        }

        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }.onFailure { error ->
            Log.w(TAG, "adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE) failed", error)
        }

        val groupId = mediaVolumeGroupId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && groupId != null) {
            runCatching {
                audioManager.adjustVolumeGroupVolume(groupId, AudioManager.ADJUST_MUTE, 0)
            }.onFailure { error ->
                Log.w(TAG, "adjustVolumeGroupVolume(media, ADJUST_MUTE) failed", error)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        active = false
        Log.i(TAG, "Closing local playback silencer. restoringVolume=$originalVolume restoringMuted=$originallyMuted")
        handler.removeCallbacks(periodicEnforce)
        unregisterVolumeObserver()
        unregisterVolumeReceiver()

        RESTORE_DELAYS_MS.forEach { delayMs ->
            handler.postDelayed({
                restoreOriginalVolume()
            }, delayMs)
        }
    }

    private fun restoreOriginalVolume() {
        runCatching {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (originallyMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
        }.onFailure { error ->
            Log.w(TAG, "Restoring media mute state failed", error)
        }
        val groupId = mediaVolumeGroupId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && groupId != null && !originallyMuted) {
            runCatching {
                audioManager.adjustVolumeGroupVolume(groupId, AudioManager.ADJUST_UNMUTE, 0)
            }
        }

        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }.onFailure { error ->
            Log.w(TAG, "Restoring media volume failed", error)
        }
        restoreVolumeGroupToOriginal()
        Log.i(
            TAG,
            "Restored local media volume. target=$originalVolume actual=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}"
        )
    }

    private fun restoreVolumeGroupToOriginal() {
        val groupId = mediaVolumeGroupId
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || groupId == null) {
            return
        }

        repeat(MAX_VOLUME_RESTORE_ATTEMPTS) {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            when {
                current < originalVolume -> runCatching {
                    audioManager.adjustVolumeGroupVolume(groupId, AudioManager.ADJUST_RAISE, 0)
                }
                current > originalVolume -> runCatching {
                    audioManager.adjustVolumeGroupVolume(groupId, AudioManager.ADJUST_LOWER, 0)
                }
                else -> return
            }
        }
    }

    private fun registerVolumeReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(volumeChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(volumeChangeReceiver, filter)
            }
            receiverRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Registering volume receiver failed", error)
        }
    }

    private fun unregisterVolumeReceiver() {
        if (!receiverRegistered) return
        runCatching {
            appContext.unregisterReceiver(volumeChangeReceiver)
        }
        receiverRegistered = false
    }

    private fun registerVolumeObserver() {
        if (observerRegistered) return
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
            observerRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Registering volume observer failed", error)
        }
    }

    private fun unregisterVolumeObserver() {
        if (!observerRegistered) return
        runCatching {
            appContext.contentResolver.unregisterContentObserver(volumeObserver)
        }
        observerRegistered = false
    }

    private companion object {
        private const val TAG = "ZevClipLocalSilencer"
        private const val ENFORCE_INTERVAL_MS = 250L
        private const val ENFORCE_BURST_RESUME_MS = 350L
        private const val TOAST_THROTTLE_MS = 2_000L
        private const val MAX_VOLUME_RESTORE_ATTEMPTS = 30
        private val ENFORCE_BURST_DELAYS_MS = longArrayOf(0L, 25L, 75L, 150L, 250L)
        private val RESTORE_DELAYS_MS = longArrayOf(0L, 250L, 750L, 1_500L, 3_000L)
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
