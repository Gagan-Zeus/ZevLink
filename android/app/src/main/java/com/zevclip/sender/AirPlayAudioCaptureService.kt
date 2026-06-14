package com.zevclip.sender

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.zevclip.sender.airplay.AirPlayIdentityStore
import com.zevclip.sender.airplay.AirPlayTarget
import com.zevclip.sender.airplay.RaopTestToneClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AirPlayAudioCaptureService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var localPlaybackSilencer: LocalPlaybackSilencer? = null
    private var dacpControlServer: AirPlayDacpControlServer? = null
    private var nowPlayingSender: AndroidNowPlayingSender? = null

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
        cleanupLegacyNotification()
        ZevClipStatusNotification.ensureChannel(this)
        startForeground(
            ZevClipStatusNotification.NOTIFICATION_ID,
            ZevClipStatusNotification.build(this)
        )

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
        if (endpoint == null) {
            finishWithStatus(getString(R.string.airplay_capture_pairing_needed))
            return
        }

        Log.i(TAG, "Starting AirPlay audio capture; enabling local playback silencer.")
        localPlaybackSilencer = LocalPlaybackSilencer(this).also { it.start() }
        running.set(true)
        ZevClipPreferences.setAirPlayStreaming(this, true)
        ZevClipPreferences.setAirPlayTestStatus(this, getString(R.string.airplay_streaming_connecting))

        worker = thread(name = "zevclip-airplay-capture", isDaemon = true) {
            runCatching {
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: error(getString(R.string.airplay_capture_permission_missing))
                mediaProjection = projection
                val record = AirPlayPlaybackCapture.createAudioRecord(projection)
                audioRecord = record
                record.startRecording()
                val metadataSender = AndroidNowPlayingSender(this)
                nowPlayingSender = metadataSender
                updateStatus(getString(R.string.airplay_streaming_live))

                val target = AirPlayTarget(
                    host = endpoint.ipAddress,
                    port = AirPlayTarget.DEFAULT_RTSP_PORT,
                    name = "Paired Mac AirPlay"
                )
                val identity = AirPlayIdentityStore.getOrCreate(this)
                val dacpSession = AirPlayDacpSession.fromIdentity(identity)
                dacpControlServer = AirPlayDacpControlServer(this, dacpSession) { command ->
                    AirPlayMediaControlDispatcher.dispatch(this, command)
                }.also { it.start() }

                RaopTestToneClient(
                    target = target,
                    password = "",
                    identity = identity,
                    dacpSession = dacpSession
                ).use { client ->
                    client.playPcmPackets(
                        source = AudioRecordPacketSource(record, running),
                        status = { status -> updateStatus(status) },
                        metadataProvider = {
                            AndroidNowPlayingReader.current(this).also { metadata ->
                                metadataSender.sendIfChanged(metadata)
                            }
                        },
                        publishMetadataToAirPlay = false
                    )
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

    private fun stopCapture() {
        if (!running.getAndSet(false)) {
            runCatching { nowPlayingSender?.stop() }
            nowPlayingSender = null
            runCatching { dacpControlServer?.close() }
            dacpControlServer = null
            runCatching { localPlaybackSilencer?.close() }
            localPlaybackSilencer = null
            ZevClipPreferences.setAirPlayStreaming(this, false)
            return
        }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { localPlaybackSilencer?.close() }
        localPlaybackSilencer = null
        runCatching { nowPlayingSender?.stop() }
        nowPlayingSender = null
        runCatching { dacpControlServer?.close() }
        dacpControlServer = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        ZevClipPreferences.setAirPlayStreaming(this, false)
        stopForegroundKeepingStatus()
    }

    private fun finishWithStatus(status: String) {
        updateStatus(status)
        runCatching { localPlaybackSilencer?.close() }
        localPlaybackSilencer = null
        ZevClipPreferences.setAirPlayStreaming(this, false)
        stopForegroundKeepingStatus()
        stopSelf()
    }

    private fun updateStatus(status: String) {
        ZevClipPreferences.setAirPlayTestStatus(this, status)
        ZevClipStatusNotification.update(this)
    }

    private fun stopForegroundKeepingStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        ZevClipStatusNotification.update(this)
    }

    private fun cleanupLegacyNotification() {
        getSystemService(NotificationManager::class.java).cancel(LEGACY_AIRPLAY_NOTIFICATION_ID)
    }

    private class AudioRecordPacketSource(
        private val record: AudioRecord,
        private val running: AtomicBoolean
    ) : RaopTestToneClient.PcmPacketSource {
        private val delegate = AirPlayPlaybackCapture.PacketSource(record, running)

        override fun readPacket(buffer: ByteArray): Int {
            return delegate.readPacket(buffer)
        }

        override fun close() = delegate.close()
    }

    companion object {
        private const val ACTION_START = "com.zevclip.sender.airplay.START"
        private const val ACTION_STOP = "com.zevclip.sender.airplay.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val LEGACY_AIRPLAY_NOTIFICATION_ID = 2042
        private const val TAG = "ZevClipAirPlayAudio"

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
                stopIntent(context)
            )
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, AirPlayAudioCaptureService::class.java).setAction(ACTION_STOP)
        }
    }
}
