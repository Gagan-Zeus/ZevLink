package com.zevclip.sender

import android.Manifest
import android.app.NotificationManager
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
import com.zevclip.sender.airplay.AirPlayIdentityStore
import com.zevclip.sender.airplay.AirPlayTarget
import com.zevclip.sender.airplay.AirPlayClock
import com.zevclip.sender.airplay.RaopAudioSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AirPlayBroadcastAudioService : Service() {
    data class TargetSpec(
        val target: AirPlayTarget,
        val password: String,
        val delayMs: Int = 0
    )

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var mediaVolumeRestorer: MediaVolumeRestorer? = null
    private var dacpControlServer: AirPlayDacpControlServer? = null
    private val sessions = mutableListOf<RaopAudioSession.Started>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBroadcast()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startBroadcast(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopBroadcast()
        super.onDestroy()
    }

    private fun startBroadcast(intent: Intent) {
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
        val targets = targetSpecsFromIntent(intent)

        if (resultCode == 0 || resultData == null) {
            finishWithStatus(getString(R.string.airplay_capture_permission_missing))
            return
        }
        if (targets.isEmpty()) {
            finishWithStatus(getString(R.string.airplay_broadcast_select_devices))
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

        running.set(true)
        ZevClipPreferences.setAirPlayBroadcastStreaming(this, true)
        updateStatus(getString(R.string.airplay_broadcast_connecting, targets.size))

        worker = thread(name = "zevclip-airplay-broadcast", isDaemon = true) {
            runCatching {
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: error(getString(R.string.airplay_capture_permission_missing))
                mediaProjection = projection

                val record = createPlaybackAudioRecord(projection)
                audioRecord = record
                mediaVolumeRestorer = MediaVolumeRestorer(getSystemService(AudioManager::class.java))
                    .also { it.muteMusicStream() }

                val identity = AirPlayIdentityStore.getOrCreate(this)
                val dacpSession = AirPlayDacpSession.fromIdentity(identity)
                dacpControlServer = AirPlayDacpControlServer(this, dacpSession) { command ->
                    AirPlayMediaControlDispatcher.dispatch(this, command)
                }.also { it.start() }

                val broadcastStartNtp = AirPlayClock.ntpNow()
                targets.forEachIndexed { index, spec ->
                    updateStatus(getString(R.string.airplay_broadcast_preparing_device, index + 1, targets.size, spec.target.name ?: spec.target.host))
                    runCatching {
                        val clock = AirPlayClock(
                            sampleRate = SAMPLE_RATE,
                            latencyFrames = latencyFramesForDelay(spec.delayMs)
                        ).also { it.resetAt(broadcastStartNtp) }
                        RaopAudioSession(
                            target = spec.target,
                            password = spec.password,
                            identity = identity,
                            dacpSession = dacpSession
                        ).also { session ->
                            session.start(clock)
                            sessions.add(RaopAudioSession.Started(spec.target, session))
                        }
                    }.onFailure { error ->
                        updateStatus(
                            getString(
                                R.string.airplay_broadcast_device_failed,
                                spec.target.name ?: spec.target.host,
                                error.message ?: "unknown error"
                            )
                        )
                    }
                }

                if (sessions.isEmpty()) {
                    error(getString(R.string.airplay_broadcast_no_sessions))
                }

                updateStatus(getString(R.string.airplay_broadcast_aligning, sessions.size))
                sendSilencePreRoll()
                record.startRecording()
                updateStatus(getString(R.string.airplay_broadcast_live, sessions.size))
                streamBroadcast(record)
            }.onSuccess {
                updateStatus(getString(R.string.airplay_broadcast_stopped))
            }.onFailure { error ->
                updateStatus(getString(R.string.airplay_broadcast_failed, error.message ?: "unknown error"))
            }

            stopBroadcast()
            stopSelf()
        }
    }

    private fun streamBroadcast(record: AudioRecord) {
        val buffer = ByteArray(PACKET_BYTES)
        while (running.get()) {
            buffer.fill(0)
            var offset = 0
            while (offset < PACKET_BYTES && running.get()) {
                val read = record.read(buffer, offset, PACKET_BYTES - offset, AudioRecord.READ_BLOCKING)
                if (read < 0) return
                if (read == 0) break
                offset += read
            }
            if (offset <= 0) continue
            val frameCount = offset / BYTES_PER_FRAME
            sendBroadcastPacket(buffer, frameCount)
        }
    }

    private fun sendSilencePreRoll() {
        val silence = ByteArray(PACKET_BYTES)
        repeat(PRE_ROLL_PACKET_COUNT) {
            if (!running.get() || sessions.isEmpty()) return
            sendBroadcastPacket(silence, FRAMES_PER_PACKET)
        }
    }

    private fun sendBroadcastPacket(buffer: ByteArray, frameCount: Int) {
        val iterator = sessions.iterator()
        while (iterator.hasNext()) {
            val started = iterator.next()
            runCatching {
                started.session.sendPcm(buffer, frameCount)
            }.onFailure { error ->
                runCatching { started.session.close() }
                iterator.remove()
                updateStatus(
                    getString(
                        R.string.airplay_broadcast_device_failed,
                        started.target.name ?: started.target.host,
                        error.message ?: "unknown error"
                    )
                )
            }
        }
        if (sessions.isEmpty()) {
            error(getString(R.string.airplay_broadcast_all_devices_failed))
        }
        sessions.forEach { started -> started.session.advanceClock(FRAMES_PER_PACKET) }
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

    private fun stopBroadcast() {
        if (!running.getAndSet(false)) {
            cleanupStreamingState()
            return
        }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { mediaVolumeRestorer?.restore() }
        mediaVolumeRestorer = null
        cleanupStreamingState()
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        ZevClipPreferences.setAirPlayBroadcastStreaming(this, false)
        stopForegroundKeepingStatus()
    }

    private fun cleanupStreamingState() {
        sessions.forEach { started -> runCatching { started.session.close() } }
        sessions.clear()
        runCatching { dacpControlServer?.close() }
        dacpControlServer = null
        ZevClipPreferences.setAirPlayBroadcastStreaming(this, false)
    }

    private fun finishWithStatus(status: String) {
        updateStatus(status)
        ZevClipPreferences.setAirPlayBroadcastStreaming(this, false)
        stopForegroundKeepingStatus()
        stopSelf()
    }

    private fun updateStatus(status: String) {
        ZevClipPreferences.setAirPlayBroadcastStatus(this, status)
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
        private const val ACTION_START = "com.zevclip.sender.airplay.broadcast.START"
        private const val ACTION_STOP = "com.zevclip.sender.airplay.broadcast.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_HOSTS = "hosts"
        private const val EXTRA_PORTS = "ports"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_DEVICE_IDS = "device_ids"
        private const val EXTRA_MODELS = "models"
        private const val EXTRA_FEATURES = "features"
        private const val EXTRA_REQUIRES_PASSWORD = "requires_password"
        private const val EXTRA_PASSWORDS = "passwords"
        private const val EXTRA_DELAYS_MS = "delays_ms"
        private const val LEGACY_AIRPLAY_NOTIFICATION_ID = 2042
        private const val SAMPLE_RATE = RaopAudioSession.SAMPLE_RATE
        private const val FRAMES_PER_PACKET = RaopAudioSession.FRAMES_PER_PACKET
        private const val BYTES_PER_FRAME = 4
        private const val PACKET_BYTES = FRAMES_PER_PACKET * BYTES_PER_FRAME
        private const val PRE_ROLL_MS = 1_000
        private const val MIN_LATENCY_MS = 1_000
        private const val MAX_DEVICE_DELAY_MS = 1_000
        private val PRE_ROLL_PACKET_COUNT =
            (PRE_ROLL_MS * SAMPLE_RATE + (FRAMES_PER_PACKET * 1_000) - 1) / (FRAMES_PER_PACKET * 1_000)

        fun start(context: Context, resultCode: Int, resultData: Intent, targets: List<TargetSpec>) {
            val intent = Intent(context, AirPlayBroadcastAudioService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putStringArrayListExtra(EXTRA_HOSTS, ArrayList(targets.map { it.target.host }))
                .putIntegerArrayListExtra(EXTRA_PORTS, ArrayList(targets.map { it.target.port }))
                .putStringArrayListExtra(EXTRA_NAMES, ArrayList(targets.map { it.target.name.orEmpty() }))
                .putStringArrayListExtra(EXTRA_DEVICE_IDS, ArrayList(targets.map { it.target.deviceId.orEmpty() }))
                .putStringArrayListExtra(EXTRA_MODELS, ArrayList(targets.map { it.target.model.orEmpty() }))
                .putStringArrayListExtra(EXTRA_FEATURES, ArrayList(targets.map { it.target.features.orEmpty() }))
                .putExtra(EXTRA_REQUIRES_PASSWORD, targets.map { it.target.requiresPassword == true }.toBooleanArray())
                .putStringArrayListExtra(EXTRA_PASSWORDS, ArrayList(targets.map { it.password }))
                .putIntegerArrayListExtra(EXTRA_DELAYS_MS, ArrayList(targets.map { it.delayMs }))
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
            return Intent(context, AirPlayBroadcastAudioService::class.java).setAction(ACTION_STOP)
        }

        private fun targetSpecsFromIntent(intent: Intent): List<TargetSpec> {
            val hosts = intent.getStringArrayListExtra(EXTRA_HOSTS).orEmpty()
            val ports = intent.getIntegerArrayListExtra(EXTRA_PORTS).orEmpty()
            val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
            val deviceIds = intent.getStringArrayListExtra(EXTRA_DEVICE_IDS).orEmpty()
            val models = intent.getStringArrayListExtra(EXTRA_MODELS).orEmpty()
            val features = intent.getStringArrayListExtra(EXTRA_FEATURES).orEmpty()
            val passwords = intent.getStringArrayListExtra(EXTRA_PASSWORDS).orEmpty()
            val delaysMs = intent.getIntegerArrayListExtra(EXTRA_DELAYS_MS).orEmpty()
            val requiresPasswords = intent.getBooleanArrayExtra(EXTRA_REQUIRES_PASSWORD)
            return hosts.mapIndexedNotNull { index, host ->
                val port = ports.getOrNull(index) ?: return@mapIndexedNotNull null
                runCatching {
                    TargetSpec(
                        target = AirPlayTarget(
                            host = host,
                            port = port,
                            name = names.getOrNull(index)?.takeIf { it.isNotBlank() },
                            deviceId = deviceIds.getOrNull(index)?.takeIf { it.isNotBlank() },
                            model = models.getOrNull(index)?.takeIf { it.isNotBlank() },
                            features = features.getOrNull(index)?.takeIf { it.isNotBlank() },
                            requiresPassword = requiresPasswords?.getOrNull(index)
                        ),
                        password = passwords.getOrNull(index).orEmpty(),
                        delayMs = delaysMs.getOrNull(index) ?: 0
                    )
                }.getOrNull()
            }
        }

        private fun latencyFramesForDelay(delayMs: Int): Long {
            val clampedDelayMs = delayMs.coerceIn(-MAX_DEVICE_DELAY_MS, MAX_DEVICE_DELAY_MS)
            val baseFrames = RaopAudioSession.DEFAULT_LATENCY_FRAMES
            val minFrames = MIN_LATENCY_MS.toLong() * SAMPLE_RATE / 1_000L
            val delayFrames = clampedDelayMs.toLong() * SAMPLE_RATE / 1_000L
            return maxOf(minFrames, baseFrames + delayFrames)
        }
    }
}
