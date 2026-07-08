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
import com.zevclip.sender.airplay.AirPlayAudioSetup
import com.zevclip.sender.airplay.AirPlayIdentityStore
import com.zevclip.sender.airplay.AirPlayLiveAudioSession
import com.zevclip.sender.airplay.AirPlayPcmStreamer
import com.zevclip.sender.airplay.AirPlayTarget
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AirPlayAudioCaptureService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var localPlaybackSilencer: LocalPlaybackSilencer? = null
    private var dacpControlServer: AirPlayDacpControlServer? = null
    private var liveAudioSession: AirPlayLiveAudioSession? = null

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

                val target = AirPlayTarget(
                    host = endpoint.ipAddress,
                    port = AirPlayTarget.DEFAULT_RTSP_PORT,
                    name = "Paired Mac AirPlay"
                )
                val identity = AirPlayIdentityStore.getOrCreate(this).let { existing ->
                    if (existing.pairingId.startsWith(LEGACY_AIRPLAY_PAIRING_PREFIX)) {
                        AirPlayIdentityStore.reset(this)
                    } else {
                        existing
                    }
                }
                val dacpSession = AirPlayDacpSession.fromIdentity(identity)
                dacpControlServer = AirPlayDacpControlServer(this, dacpSession) { command ->
                    AirPlayMediaControlDispatcher.dispatch(this, command)
                }.also { it.start() }

                val format = AirPlayAudioSetup.AudioFormat(
                    sampleRate = AirPlayPlaybackCapture.SAMPLE_RATE,
                    channels = 2,
                    framesPerPacket = AirPlayPlaybackCapture.FRAMES_PER_PACKET,
                    useAlac = true
                )
                val session = AirPlayLiveAudioSession(
                    target = target,
                    identity = identity,
                    passcode = ZevClipPreferences.airPlayPasscode(this)
                )
                liveAudioSession = session
                val started = when (val result = session.start(format = format) { status -> updateStatus(status) }) {
                    is AirPlayLiveAudioSession.Result.Success -> result.session
                    is AirPlayLiveAudioSession.Result.Failure -> error(result.message)
                }

                updateStatus(getString(R.string.airplay_streaming_live))
                record.startRecording()
                streamCapturedAudio(record, started.streamer)
                started.streamer.finishWithPadding(format.framesPerPacket * 4)
                session.close()
                liveAudioSession = null
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
            runCatching { dacpControlServer?.close() }
            dacpControlServer = null
            runCatching { liveAudioSession?.close() }
            liveAudioSession = null
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
        runCatching { dacpControlServer?.close() }
        dacpControlServer = null
        runCatching { liveAudioSession?.close() }
        liveAudioSession = null
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

    private fun streamCapturedAudio(
        record: AudioRecord,
        streamer: AirPlayPcmStreamer
    ) {
        val source = AirPlayPlaybackCapture.PacketSource(record, running)
        val buffer = ByteArray(AirPlayPlaybackCapture.PACKET_BYTES)
        source.use {
            while (running.get()) {
                val frameCount = it.readPacket(buffer)
                if (frameCount < 0) break
                if (frameCount == 0) continue
                streamer.sendPcm(buffer.copyOf(frameCount * AirPlayPlaybackCapture.BYTES_PER_FRAME))
            }
        }
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

    companion object {
        private const val ACTION_START = "com.zevclip.sender.airplay.START"
        private const val ACTION_STOP = "com.zevclip.sender.airplay.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val LEGACY_AIRPLAY_NOTIFICATION_ID = 2042
        private const val LEGACY_AIRPLAY_PAIRING_PREFIX = "ZevClip-"
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
