package com.zevclip.sender.airplay

import kotlin.concurrent.thread

class AirPlayPreparedAudioSession(
    private val target: AirPlayTarget,
    private val pairVerifySession: AirPlayPairVerifier.Session,
    private val transport: AirPlayRtspTransport,
    private val identity: AirPlayIdentity,
    private val localRtspHost: String = target.host,
    private val timingServerFactory: () -> AirPlayTimingServer = { AirPlayTimingServer() },
    private val syncSenderFactory: (AirPlayClock, Int) -> AirPlaySyncSender = { clock, port ->
        AirPlaySyncSender(clock, target.host, port)
    },
    private val packetSinkFactory: (Int) -> AirPlayPacketSink = { dataPort ->
        AirPlayUdpPacketSender(target.host, dataPort)
    }
) {
    data class RunningSession(
        val setup: AirPlayAudioSessionController.SetupResult,
        val clock: AirPlayClock,
        val packetizer: AirPlayRtpAudioPacketizer,
        val streamer: AirPlayPcmStreamer
    )

    sealed class Result {
        data class Success(val session: RunningSession) : Result()
        data class Failure(val message: String, val cause: Throwable? = null) : Result()
    }

    private var timingServer: AirPlayTimingServer? = null
    private var syncSender: AirPlaySyncSender? = null
    private var eventChannel: AirPlayEventChannel? = null
    @Volatile
    private var keepAliveRunning = false
    private var keepAliveThread: Thread? = null

    fun start(
        controlPort: Int,
        format: AirPlayAudioSetup.AudioFormat = AirPlayAudioSetup.AudioFormat(),
        useAlacPayloads: Boolean = format.useAlac
    ): Result {
        return runCatching {
            val timingServer = timingServerFactory()
            this.timingServer = timingServer
            val timingPort = timingServer.start(0)
            val controller = AirPlayAudioSessionController(
                target = target,
                transport = transport,
                pairVerifySession = pairVerifySession,
                localRtspHost = localRtspHost,
                deviceId = identity.deviceId,
                senderName = identity.senderName
            )
            val setup = controller.setup(
                timingPort = timingPort,
                controlPort = controlPort,
                format = format
            )
            if (setup !is AirPlayAudioSessionController.Result.Success) {
                val message = (setup as? AirPlayAudioSessionController.Result.Failure)?.message
                    ?: "AirPlay audio setup failed."
                return Result.Failure(message)
            }

            eventChannel = if (setup.setup.ports.eventPort > 0) {
                AirPlayEventChannel(target.host, setup.setup.ports.eventPort, pairVerifySession.sharedSecret)
                    .also { it.start() }
            } else {
                null
            }

            val clock = AirPlayClock(format.sampleRate)
            clock.reset()
            syncSender = if (setup.setup.ports.controlPort > 0) {
                syncSenderFactory(clock, setup.setup.ports.controlPort).also { it.start() }
            } else {
                null
            }
            val packetizer = AirPlayRtpAudioPacketizer(
                audioKey = setup.setup.audioSharedKey,
                ssrc = setup.setup.sessionIds.streamConnectionId.toInt(),
                clock = clock,
                channels = format.channels,
                framesPerPacket = format.framesPerPacket,
                alacEncoder = if (useAlacPayloads) {
                    AirPlayAlacEncoder(format.sampleRate, format.channels, framesPerPacket = format.framesPerPacket)
                } else {
                    null
                },
                initialSequenceNumber = CryptoPrimitives.randomBytes(2)
                    .fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xFF) }
            )
            controller.record(setup.setup.sessionIds)
            controller.flush(
                ids = setup.setup.sessionIds,
                sequenceNumber = packetizer.startSequenceNumber,
                rtpTimestamp = packetizer.startTimestamp
            )
            controller.setVolume(setup.setup.sessionIds)
            controller.feedback(setup.setup.sessionIds)
            startKeepAlive(controller, setup.setup.sessionIds)

            Result.Success(
                RunningSession(
                    setup = setup.setup,
                    clock = clock,
                    packetizer = packetizer,
                    streamer = AirPlayPcmStreamer(packetizer, packetSinkFactory(setup.setup.ports.dataPort))
                )
            )
        }.getOrElse { error ->
            Result.Failure("AirPlay prepared audio session failed: ${error.message ?: "unknown error"}", error)
        }
    }

    private fun startKeepAlive(
        controller: AirPlayAudioSessionController,
        ids: AirPlayAudioSetup.SessionIds
    ) {
        if (keepAliveRunning) return
        keepAliveRunning = true
        keepAliveThread = thread(name = "zevclip-airplay-audio-rtsp-keepalive", isDaemon = true) {
            var lastParameterAt = 0L
            while (keepAliveRunning) {
                try {
                    Thread.sleep(FEEDBACK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                runCatching { controller.feedback(ids) }
                val now = System.currentTimeMillis()
                if (now - lastParameterAt >= GET_PARAMETER_INTERVAL_MS) {
                    runCatching { controller.getParameter(ids) }
                    lastParameterAt = now
                }
            }
        }
    }

    fun stop() {
        keepAliveRunning = false
        keepAliveThread?.interrupt()
        keepAliveThread = null
        runCatching { eventChannel?.stop() }
        eventChannel = null
        runCatching { syncSender?.stop() }
        syncSender = null
        runCatching { timingServer?.stop() }
        timingServer = null
    }

    private companion object {
        const val FEEDBACK_INTERVAL_MS = 2_000L
        const val GET_PARAMETER_INTERVAL_MS = 15_000L
    }
}
