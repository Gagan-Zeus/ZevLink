package com.zevclip.sender

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.zevclip.sender.airplay.AirPlayClock
import com.zevclip.sender.airplay.AirPlayAlacEncoder
import com.zevclip.sender.airplay.AirPlayMirrorSessionController
import com.zevclip.sender.airplay.AirPlayPcmStreamer
import com.zevclip.sender.airplay.AirPlayRtpAudioPacketizer
import com.zevclip.sender.airplay.AirPlayScreenEncoder
import com.zevclip.sender.airplay.AirPlaySyncSender
import com.zevclip.sender.airplay.AirPlayTarget
import com.zevclip.sender.airplay.AirPlayUdpPacketSender
import com.zevclip.sender.airplay.CryptoPrimitives
import com.zevclip.sender.airplay.ZevLinkMirrorStreamClient
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AirPlayScreenMirrorService : Service() {
    private val running = AtomicBoolean(false)
    private val encoderLock = Any()
    private var worker: Thread? = null
    private var encoderWorker: Thread? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var encoder: AirPlayScreenEncoder? = null
    private var mirrorClient: ZevLinkMirrorStreamClient? = null
    private var mirrorSession: AirPlayMirrorSessionController? = null
    private var mirrorAudioRecord: AudioRecord? = null
    private var mirrorAudioSetupWorker: Thread? = null
    private var mirrorAudioWorker: Thread? = null
    private var mirrorAudioControlSocket: DatagramSocket? = null
    private var mirrorAudioSyncSender: AirPlaySyncSender? = null
    private var mirrorAudioPacketSink: AirPlayUdpPacketSender? = null
    private var mirrorAudioStreamer: AirPlayPcmStreamer? = null
    private var localPlaybackSilencer: LocalPlaybackSilencer? = null
    private var dacpControlServer: AirPlayDacpControlServer? = null
    private var encoderGeneration = 0
    @Volatile
    private var restartingEncoder = false
    @Volatile
    private var activeCaptureSize: MirrorCaptureSize? = null
    @Volatile
    private var mirrorFailure: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMirror()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startMirror(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMirror()
        super.onDestroy()
    }

    private fun startMirror(intent: Intent) {
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
        val endpoint = ZevClipPreferences.endpoint(this)
        if (endpoint == null) {
            finishWithStatus(getString(R.string.airplay_capture_pairing_needed))
            return
        }

        Log.i(TAG, "Starting ZevLink windowed screen mirror.")
        running.set(true)
        mirrorFailure = null
        ZevClipPreferences.setAirPlayScreenMirroring(this, true)
        updateStatus(getString(R.string.airplay_screen_mirror_connecting))

        worker = thread(name = "zevclip-airplay-screen", isDaemon = true) {
            runCatching {
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: error(getString(R.string.airplay_capture_permission_missing))
                mediaProjection = projection
                projectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopMirror()
                    }
                }.also { callback ->
                    projection.registerCallback(callback, Handler(Looper.getMainLooper()))
                }

                updateStatus(getString(R.string.airplay_screen_mirror_preparing))
                startZevLinkWindowPipeline(projection, endpoint.ipAddress, captureSize())

                updateStatus(getString(R.string.airplay_screen_mirror_live))
                while (running.get()) {
                    Thread.sleep(CAPTURE_SIZE_POLL_MS)
                    resizeAirPlayCaptureIfNeeded(captureSize())
                }
            }.onSuccess {
                mirrorFailure?.let { failure ->
                    updateStatus(getString(R.string.airplay_screen_mirror_failed, failure))
                } ?: updateStatus(getString(R.string.airplay_screen_mirror_stopped))
            }.onFailure { error ->
                val message = error.message ?: error.javaClass.simpleName
                mirrorFailure = message
                Log.w(TAG, "AirPlay screen mirror failed", error)
                updateStatus(getString(R.string.airplay_screen_mirror_failed, message))
            }

            stopMirror()
            stopSelf()
        }
    }

    private fun startZevLinkWindowPipeline(
        projection: MediaProjection,
        host: String,
        captureSize: MirrorCaptureSize
    ) {
        val nextMirrorClient = ZevLinkMirrorStreamClient(
            host = host,
            width = captureSize.width,
            height = captureSize.height,
            running = running,
            onControlCommand = { command ->
                ZevPlayInputController.dispatch(this, command)
            }
        ).also { it.connect() }
        mirrorClient = nextMirrorClient
        startEncoder(projection, nextMirrorClient, captureSize)
        startZevLinkWindowAudio(projection, nextMirrorClient)
    }

    private fun closeAirPlayPipeline() {
        stopEncoderOnly()
        stopMirrorAudio()
        runCatching { mirrorClient?.close() }
        mirrorClient = null
        runCatching { mirrorSession?.close() }
        mirrorSession = null
    }

    private fun startZevLinkWindowAudio(
        projection: MediaProjection,
        streamClient: ZevLinkMirrorStreamClient
    ) {
        runCatching {
            val record = AirPlayPlaybackCapture.createAudioRecord(projection)
            mirrorAudioRecord = record
            if (localPlaybackSilencer == null) {
                localPlaybackSilencer = LocalPlaybackSilencer(this).also { it.start() }
            }
            streamClient.setAudioFormat(
                sampleRate = AirPlayPlaybackCapture.SAMPLE_RATE,
                channels = ZEVLINK_AUDIO_CHANNELS
            )
            record.startRecording()
            mirrorAudioWorker = thread(name = "zevlink-screen-window-audio", isDaemon = true) {
                runCatching {
                    streamZevLinkWindowAudio(record, streamClient)
                }.onFailure { error ->
                    if (running.get()) {
                        val message = error.message ?: error.javaClass.simpleName
                        Log.w(TAG, "ZevLink screen mirror audio failed; keeping video mirror alive", error)
                        stopMirrorAudio()
                        updateStatus("Screen mirroring live. Audio failed: $message")
                    }
                }
            }
        }.onFailure { error ->
            if (running.get()) {
                val message = error.message ?: error.javaClass.simpleName
                Log.w(TAG, "ZevLink screen mirror audio setup failed; keeping video mirror alive", error)
                stopMirrorAudio()
                updateStatus("Screen mirroring live. Audio failed: $message")
            }
        }
    }

    private fun streamZevLinkWindowAudio(record: AudioRecord, streamClient: ZevLinkMirrorStreamClient) {
        val source = AirPlayPlaybackCapture.PacketSource(record, running)
        val buffer = ByteArray(AirPlayPlaybackCapture.PACKET_BYTES)
        var framesSent = 0L
        source.use {
            while (running.get()) {
                val frameCount = it.readPacket(buffer)
                if (frameCount < 0) break
                if (frameCount == 0) continue
                val bytes = frameCount * AirPlayPlaybackCapture.BYTES_PER_FRAME
                val presentationTimeUs = framesSent * 1_000_000L / AirPlayPlaybackCapture.SAMPLE_RATE
                streamClient.writeAudioPcm(
                    pcm16LittleEndian = buffer.copyOf(bytes),
                    sampleRate = AirPlayPlaybackCapture.SAMPLE_RATE,
                    channels = ZEVLINK_AUDIO_CHANNELS,
                    presentationTimeUs = presentationTimeUs
                )
                framesSent += frameCount
            }
        }
    }

    private fun startMirrorAudioAsync(
        projection: MediaProjection,
        target: AirPlayTarget,
        identity: com.zevclip.sender.airplay.AirPlayIdentity,
        preparedAudio: AirPlayMirrorSessionController.PreparedAudio,
        controlSocket: DatagramSocket
    ) {
        mirrorAudioSetupWorker = thread(name = "zevclip-airplay-screen-audio-setup", isDaemon = true) {
            runCatching {
                if (!running.get()) return@thread
                startMirrorAudio(projection, target, identity, preparedAudio, controlSocket)
            }.onFailure { error ->
                if (running.get()) {
                    val message = error.message ?: error.javaClass.simpleName
                    Log.w(TAG, "AirPlay screen mirror audio setup failed; keeping video mirror alive", error)
                    stopMirrorAudio()
                    updateStatus("AirPlay screen mirroring live. Audio failed: $message")
                }
            }
        }
    }

    private fun startMirrorAudio(
        projection: MediaProjection,
        target: AirPlayTarget,
        identity: com.zevclip.sender.airplay.AirPlayIdentity,
        preparedAudio: AirPlayMirrorSessionController.PreparedAudio,
        controlSocket: DatagramSocket
    ) {
        if (!running.get()) return
        val record = AirPlayPlaybackCapture.createAudioRecord(projection)
        mirrorAudioRecord = record
        if (localPlaybackSilencer == null) {
            localPlaybackSilencer = LocalPlaybackSilencer(this).also { it.start() }
        }

        val dacpSession = AirPlayDacpSession.fromIdentity(identity)
        dacpControlServer = AirPlayDacpControlServer(this, dacpSession) { command ->
            AirPlayMediaControlDispatcher.dispatch(this, command)
        }.also { it.start() }

        val clock = AirPlayClock(
            sampleRate = preparedAudio.sampleRate,
            latencyFrames = MIRROR_AUDIO_LATENCY_FRAMES
        )
        val packetizer = AirPlayRtpAudioPacketizer(
            audioKey = preparedAudio.audioSharedKey,
            ssrc = preparedAudio.streamConnectionId.toInt(),
            clock = clock,
            channels = preparedAudio.channels,
            framesPerPacket = preparedAudio.framesPerPacket,
            alacEncoder = AirPlayAlacEncoder(
                sampleRate = preparedAudio.sampleRate,
                channels = preparedAudio.channels,
                framesPerPacket = preparedAudio.framesPerPacket
            ),
            initialSequenceNumber = CryptoPrimitives.randomBytes(2)
                .fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xFF) }
        )
        mirrorSession?.flushAudioBestEffort(packetizer.startSequenceNumber, packetizer.startTimestamp)
        mirrorSession?.setAudioVolumeBestEffort()

        mirrorAudioSyncSender = if (preparedAudio.controlPort > 0) {
            AirPlaySyncSender(
                clock = clock,
                host = target.host,
                port = preparedAudio.controlPort,
                socket = controlSocket
            ).also { it.start() }
        } else {
            null
        }
        val packetSink = AirPlayUdpPacketSender(target.host, preparedAudio.dataPort)
        mirrorAudioPacketSink = packetSink
        val streamer = AirPlayPcmStreamer(packetizer, packetSink)
        mirrorAudioStreamer = streamer

        if (!running.get()) {
            stopMirrorAudio()
            return
        }

        record.startRecording()
        mirrorAudioWorker = thread(name = "zevclip-airplay-screen-audio", isDaemon = true) {
            runCatching {
                streamMirrorAudio(record, streamer)
            }.onFailure { error ->
                if (running.get()) {
                    val message = error.message ?: error.javaClass.simpleName
                    Log.w(TAG, "AirPlay screen mirror audio failed; keeping video mirror alive", error)
                    stopMirrorAudio()
                    updateStatus("AirPlay screen mirroring live. Audio failed: $message")
                }
            }
        }
    }

    private fun streamMirrorAudio(record: AudioRecord, streamer: AirPlayPcmStreamer) {
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

    private fun stopMirrorAudio() {
        val oldSetupWorker = mirrorAudioSetupWorker
        val oldWorker = mirrorAudioWorker
        mirrorAudioSetupWorker = null
        mirrorAudioWorker = null
        runCatching { mirrorAudioRecord?.stop() }
        runCatching { mirrorAudioRecord?.release() }
        mirrorAudioRecord = null
        runCatching {
            if (oldSetupWorker != null && oldSetupWorker !== Thread.currentThread()) {
                oldSetupWorker.join(AUDIO_STOP_JOIN_MS)
            }
        }
        runCatching {
            if (oldWorker != null && oldWorker !== Thread.currentThread()) {
                oldWorker.join(AUDIO_STOP_JOIN_MS)
            }
        }
        mirrorAudioStreamer = null
        runCatching { mirrorAudioPacketSink?.close() }
        mirrorAudioPacketSink = null
        runCatching { mirrorAudioSyncSender?.stop(closeSocket = false) }
        mirrorAudioSyncSender = null
        runCatching { mirrorAudioControlSocket?.close() }
        mirrorAudioControlSocket = null
        runCatching { dacpControlServer?.close() }
        dacpControlServer = null
        runCatching { localPlaybackSilencer?.close() }
        localPlaybackSilencer = null
    }

    private fun startEncoder(
        projection: MediaProjection,
        streamClient: ZevLinkMirrorStreamClient,
        captureSize: MirrorCaptureSize
    ) {
        synchronized(encoderLock) {
            stopEncoderOnlyLocked()

            streamClient.updateVideoSize(captureSize.width, captureSize.height)
            Log.i(TAG, "Using AirPlay capture size ${captureSize.width}x${captureSize.height}")
            activeCaptureSize = captureSize
            val screenEncoder = AirPlayScreenEncoder(
                captureSize.width,
                captureSize.height,
                captureSize.densityDpi,
                streamClient,
                running
            )
            val generation = ++encoderGeneration
            encoder = screenEncoder
            encoderWorker = thread(name = "zevclip-airplay-screen-encoder", isDaemon = true) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                runCatching { screenEncoder.start(projection) }
                    .onFailure { error ->
                        if (running.get() && generation == encoderGeneration && encoder === screenEncoder && !restartingEncoder) {
                            val message = error.message ?: error.javaClass.simpleName
                            mirrorFailure = message
                            Log.w(TAG, "AirPlay screen encoder failed", error)
                            running.set(false)
                            updateStatus(getString(R.string.airplay_screen_mirror_failed, message))
                        }
                    }
            }
            restartingEncoder = false
        }
    }

    private fun resizeAirPlayCaptureIfNeeded(captureSize: MirrorCaptureSize) {
        synchronized(encoderLock) {
            val currentSize = activeCaptureSize
            val screenEncoder = encoder
            val streamClient = mirrorClient
            if (
                currentSize == null ||
                screenEncoder == null ||
                streamClient == null ||
                !currentSize.needsRebuildFor(captureSize)
            ) {
                return
            }

            Log.i(TAG, "Resizing AirPlay capture from ${currentSize.width}x${currentSize.height} to ${captureSize.width}x${captureSize.height}")
            streamClient.updateVideoSize(captureSize.width, captureSize.height)
            activeCaptureSize = captureSize
            screenEncoder.requestResize(captureSize.width, captureSize.height, captureSize.densityDpi)
        }
    }

    private fun stopEncoderOnly() {
        synchronized(encoderLock) {
            stopEncoderOnlyLocked()
        }
    }

    private fun stopEncoderOnlyLocked() {
        restartingEncoder = true
        val oldEncoder = encoder
        val oldWorker = encoderWorker
        encoder = null
        encoderWorker = null
        encoderGeneration++
        runCatching { oldEncoder?.close() }
        runCatching {
            if (oldWorker != null && oldWorker !== Thread.currentThread()) {
                oldWorker.join(ENCODER_RESTART_JOIN_MS)
            }
        }
        activeCaptureSize = null
        restartingEncoder = false
    }

    private fun captureSize(): MirrorCaptureSize {
        val metrics = resources.displayMetrics
        val display = getSystemService(WindowManager::class.java).defaultDisplay
        val supportedMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            display.supportedModes
                .maxWithOrNull(compareBy({ it.physicalWidth.toLong() * it.physicalHeight.toLong() }, { it.refreshRate }))
        } else {
            null
        }
        val modeWidth = supportedMode?.physicalWidth ?: metrics.widthPixels
        val modeHeight = supportedMode?.physicalHeight ?: metrics.heightPixels
        val isCurrentLandscape = when (display.rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> true
            Surface.ROTATION_0, Surface.ROTATION_180 -> false
            else -> metrics.widthPixels > metrics.heightPixels
        }
        val longEdge = maxOf(modeWidth, modeHeight)
        val shortEdge = minOf(modeWidth, modeHeight)
        val sourceWidth = if (isCurrentLandscape) longEdge else shortEdge
        val sourceHeight = if (isCurrentLandscape) shortEdge else longEdge
        val (width, height) = AirPlayScreenEncoder.scaledSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            maxLongEdge = MAX_PHONE_CAPTURE_LONG_EDGE,
            maxPixels = MAX_PHONE_CAPTURE_PIXELS
        )
        return MirrorCaptureSize(
            width,
            height,
            metrics.densityDpi
        )
    }

    private fun stopMirror() {
        if (!running.getAndSet(false)) {
            cleanupMirrorState()
            return
        }
        cleanupMirrorState()
        ZevClipPreferences.setAirPlayScreenMirroring(this, false)
        stopForegroundKeepingStatus()
    }

    private fun cleanupMirrorState() {
        closeAirPlayPipeline()
        projectionCallback?.let { callback ->
            runCatching { mediaProjection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        ZevClipPreferences.setAirPlayScreenMirroring(this, false)
    }

    private fun finishWithStatus(status: String) {
        updateStatus(status)
        runCatching { localPlaybackSilencer?.close() }
        localPlaybackSilencer = null
        ZevClipPreferences.setAirPlayScreenMirroring(this, false)
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

    companion object {
        private const val ACTION_START = "com.zevclip.sender.airplay.screen.START"
        private const val ACTION_STOP = "com.zevclip.sender.airplay.screen.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val LEGACY_AIRPLAY_NOTIFICATION_ID = 2042
        private const val ENCODER_RESTART_JOIN_MS = 700L
        private const val AUDIO_STOP_JOIN_MS = 700L
        private const val CAPTURE_SIZE_POLL_MS = 300L
        private const val ZEVLINK_AUDIO_CHANNELS = 2
        private const val ENABLE_MIRROR_SESSION_AUDIO = true
        private const val MIRROR_AUDIO_LATENCY_FRAMES = 8_820L
        private const val MAX_PHONE_CAPTURE_LONG_EDGE = 3840
        private const val MAX_PHONE_CAPTURE_PIXELS = 3840 * 2160
        private const val TAG = "ZevClipAirPlayScreen"

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, AirPlayScreenMirrorService::class.java)
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
            context.startService(stopIntent(context))
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, AirPlayScreenMirrorService::class.java).setAction(ACTION_STOP)
        }
    }
}

private data class MirrorCaptureSize(
    val width: Int,
    val height: Int,
    val densityDpi: Int
) {
    fun needsRebuildFor(other: MirrorCaptureSize): Boolean {
        return width != other.width || height != other.height || densityDpi != other.densityDpi
    }
}
