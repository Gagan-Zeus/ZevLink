package com.zevclip.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.zevclip.sender.airplay.AirPlayTarget
import com.zevclip.sender.airplay.RaopTestToneClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AirPlayAudioCaptureService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var mediaVolumeRestorer: MediaVolumeRestorer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        if (running.get()) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.airplay_streaming_starting)))

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            finishWithStatus(getString(R.string.airplay_capture_permission_missing))
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finishWithStatus(getString(R.string.airplay_capture_android_q_required))
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishWithStatus(getString(R.string.airplay_capture_record_audio_needed))
            return
        }

        val endpoint = ZevClipPreferences.endpoint(this)
        val passcode = ZevClipPreferences.airPlayPasscode(this)
        if (endpoint == null || passcode.isBlank()) {
            finishWithStatus(getString(R.string.airplay_capture_pairing_needed))
            return
        }

        running.set(true)
        ZevClipPreferences.setAirPlayStreaming(this, true)
        ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_streaming_connecting))

        worker = thread(name = "zevclip-airplay-capture", isDaemon = true) {
            runCatching {
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: error(getString(R.string.airplay_capture_permission_missing))
                mediaProjection = projection
                val record = createPlaybackAudioRecord(projection)
                audioRecord = record
                record.startRecording()
                mediaVolumeRestorer = MediaVolumeRestorer(getSystemService(AudioManager::class.java))
                    .also { it.muteMusicStream() }
                updateStatus(getString(R.string.airplay_streaming_live))

                val target = AirPlayTarget(
                    host = endpoint.ipAddress,
                    port = AirPlayTarget.DEFAULT_RTSP_PORT,
                    name = "Paired Mac AirPlay"
                )
                RaopTestToneClient(target, passcode).use { client ->
                    client.playPcmPackets(AudioRecordPacketSource(record, running)) { status ->
                        updateStatus(status)
                    }
                }
            }.onSuccess {
                updateStatus(getString(R.string.airplay_streaming_stopped))
            }.onFailure { error ->
                updateStatus(getString(R.string.airplay_streaming_failed, error.message ?: "unknown error"))
            }

            stopCapture()
            stopSelf()
        }
    }

    private fun createPlaybackAudioRecord(projection: MediaProjection): AudioRecord {
        val captureConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, PACKET_BYTES * 4)
        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    private fun stopCapture() {
        if (!running.getAndSet(false)) {
            ZevClipPreferences.setAirPlayStreaming(this, false)
            return
        }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { mediaVolumeRestorer?.restore() }
        mediaVolumeRestorer = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        ZevClipPreferences.setAirPlayStreaming(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun finishWithStatus(status: String) {
        updateStatus(status)
        ZevClipPreferences.setAirPlayStreaming(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStatus(status: String) {
        ZevClipPreferences.setAirPlayTestStatus(this, status)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AirPlayAudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_clipboard)
            .setContentTitle(getString(R.string.airplay_notification_title))
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.stop_airplay_audio), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ZevClip AirPlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Streams Android audio to AirPlay receivers."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private class AudioRecordPacketSource(
        private val record: AudioRecord,
        private val running: AtomicBoolean
    ) : RaopTestToneClient.PcmPacketSource {
        override fun readPacket(buffer: ByteArray): Int {
            if (!running.get()) return -1
            buffer.fill(0)
            var offset = 0
            while (offset < PACKET_BYTES && running.get()) {
                val read = record.read(buffer, offset, PACKET_BYTES - offset, AudioRecord.READ_BLOCKING)
                if (read < 0) return -1
                if (read == 0) break
                offset += read
            }
            return if (offset <= 0) 0 else offset / BYTES_PER_FRAME
        }

        override fun close() = Unit
    }

    private class MediaVolumeRestorer(
        private val audioManager: AudioManager
    ) {
        private val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        private var restored = false

        fun muteMusicStream() {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }

        fun restore() {
            if (restored) return
            restored = true
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    companion object {
        private const val ACTION_START = "com.zevclip.sender.airplay.START"
        private const val ACTION_STOP = "com.zevclip.sender.airplay.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "zevclip_airplay"
        private const val NOTIFICATION_ID = 2042
        private const val SAMPLE_RATE = 44_100
        private const val FRAMES_PER_PACKET = 352
        private const val BYTES_PER_FRAME = 4
        private const val PACKET_BYTES = FRAMES_PER_PACKET * BYTES_PER_FRAME

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, AirPlayAudioCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AirPlayAudioCaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
