package com.zevclip.sender.airplay

import android.content.Context
import com.zevclip.sender.ZevClipPreferences
import java.io.Closeable
import java.net.DatagramSocket
import kotlin.math.ceil

object AirPlayToneTester {
    sealed class Result {
        data class Success(val message: String) : Result()
        data class Failure(val message: String, val cause: Throwable? = null) : Result()
    }

    fun run(
        context: Context,
        status: (String) -> Unit = {}
    ): Result {
        val appContext = context.applicationContext
        val endpoint = ZevClipPreferences.endpoint(appContext)
            ?: return Result.Failure("Pair your Mac first, then try the AirPlay test tone.")

        val target = AirPlayTarget(
            host = endpoint.ipAddress,
            port = AirPlayTarget.DEFAULT_RTSP_PORT,
            name = "Paired Mac AirPlay"
        )
        var identity = AirPlayIdentityStore.getOrCreate(appContext)
        if (identity.pairingId.startsWith("ZevClip-")) {
            identity = AirPlayIdentityStore.reset(appContext)
        }
        val passcode = ZevClipPreferences.airPlayPasscode(appContext)
        status("Checking Mac AirPlay at ${target.host}:${target.port}...")

        if (passcode.isNotBlank()) {
            val raopResult = runRaopTone(target, passcode, identity, status)
            if (raopResult is Result.Success) return raopResult
            return raopResult
        }

        val connected = connectForAudio(
            appContext = appContext,
            target = target,
            identity = identity,
            passcode = passcode,
            status = status
        )
        if (connected is ConnectionAttempt.Failure) return Result.Failure(connected.message)

        (connected as ConnectionAttempt.Success).session.use { session ->
            var controlSocket: DatagramSocket? = null
            val prepared = AirPlayPreparedAudioSession(
                target = target,
                pairVerifySession = session.pairSession,
                transport = session.transport,
                identity = identity,
                localRtspHost = session.localRtspHost,
                syncSenderFactory = { clock, port ->
                    AirPlaySyncSender(
                        clock = clock,
                        host = target.host,
                        port = port,
                        socket = requireNotNull(controlSocket) { "AirPlay control socket is not ready." }
                    )
                }
            )

            return runCatching {
                controlSocket = DatagramSocket()
                val format = AirPlayAudioSetup.AudioFormat(
                    sampleRate = AirPlayTestTone.DEFAULT_SAMPLE_RATE,
                    channels = 2,
                    framesPerPacket = 352,
                    useAlac = true
                )

                status("Starting AirPlay audio session...")
                val running = when (val result = prepared.start(controlPort = controlSocket.localPort, format = format)) {
                    is AirPlayPreparedAudioSession.Result.Success -> result.session
                    is AirPlayPreparedAudioSession.Result.Failure -> {
                        return Result.Failure(result.message, result.cause)
                    }
                }

                status("Playing AirPlay test tone...")
                val packets = streamTone(running.streamer, format)
                running.streamer.finishWithPadding(format.framesPerPacket * 4)

                val message = "AirPlay test tone sent ($packets packets)."
                status(message)
                Result.Success(message)
            }.getOrElse { error ->
                Result.Failure("AirPlay test tone failed: ${error.message ?: "unknown error"}", error)
            }.also {
                prepared.stop()
                runCatching { controlSocket?.close() }
            }
        }
    }

    private fun runRaopTone(
        target: AirPlayTarget,
        passcode: String,
        identity: AirPlayIdentity,
        status: (String) -> Unit
    ): Result {
        return runCatching {
            RaopTestToneClient(target, passcode, identity = identity).use { client ->
                val result = client.playTestTone(status)
                Result.Success(result.message)
            }
        }.getOrElse { error ->
            Result.Failure("RAOP/AirTunes test failed: ${error.message ?: "unknown error"}", error)
        }
    }

    private fun connectForAudio(
        appContext: Context,
        target: AirPlayTarget,
        identity: AirPlayIdentity,
        passcode: String,
        status: (String) -> Unit
    ): ConnectionAttempt {
        verifyConnected(target, identity)?.let { return ConnectionAttempt.Success(it) }

        if (passcode.isNotBlank()) {
            status("Pairing with saved Mac AirPlay password...")
            AirPlayPairSetupClient(target, identity).use { setupClient ->
                when (val setup = setupClient.pairSetup(passcode = passcode, transient = false)) {
                    is AirPlayPairSetupClient.Result.PersistentSuccess -> {
                        status("AirPlay pair-setup accepted. Verifying new session...")
                    }
                    is AirPlayPairSetupClient.Result.TransientSuccess -> {
                        return ConnectionAttempt.Success(
                            AudioControlSession(
                                pairSession = setup.session.session,
                                transport = setup.session.transport,
                                localRtspHost = setup.session.localRtspHost,
                                closeAction = { setup.session.close() }
                            )
                        )
                    }
                    is AirPlayPairSetupClient.Result.Failure -> {
                        val message = "Password pair-setup failed: ${setup.message}"
                        status(message)
                        return ConnectionAttempt.Failure(message)
                    }
                }
            }

            val verified = verifyConnected(target, identity)
            if (verified != null) {
                ZevClipPreferences.setAirPlayTestStatus(appContext, "AirPlay paired. Starting audio...")
                return ConnectionAttempt.Success(verified)
            }
            val message = "Password pair-setup succeeded, but pair-verify still failed."
            status(message)
            return ConnectionAttempt.Failure(message)
        }

        status("Mac rejected direct verify. Trying AirSonic transient pairing...")
        val transientClient = AirPlayPairSetupClient(target, identity)
        return when (val transient = transientClient.pairSetup(passcode = TRANSIENT_PAIRING_PASSCODE, transient = true)) {
            is AirPlayPairSetupClient.Result.TransientSuccess -> {
                status("Transient AirPlay pairing accepted.")
                ConnectionAttempt.Success(
                    AudioControlSession(
                        pairSession = transient.session.session,
                        transport = transient.session.transport,
                        localRtspHost = transient.session.localRtspHost,
                        closeAction = { transient.session.close() }
                    )
                )
            }
            is AirPlayPairSetupClient.Result.PersistentSuccess -> {
                transientClient.close()
                ConnectionAttempt.Failure("Transient pair-setup returned an unexpected persistent result.")
            }
            is AirPlayPairSetupClient.Result.Failure -> {
                transientClient.close()
                val message = "Transient pair-setup failed: ${transient.message}. Enter your Mac AirPlay password and test again."
                status(message)
                ConnectionAttempt.Failure(message)
            }
        }
    }

    private fun verifyConnected(
        target: AirPlayTarget,
        identity: AirPlayIdentity
    ): AudioControlSession? {
        return when (val verify = AirPlayPairVerifier(target, identity).verifyConnected()) {
            is AirPlayPairVerifier.ConnectedResult.Success -> AudioControlSession(
                pairSession = verify.connectedSession.session,
                transport = verify.connectedSession.transport,
                localRtspHost = verify.connectedSession.localRtspHost,
                closeAction = { verify.connectedSession.close() }
            )
            is AirPlayPairVerifier.ConnectedResult.Failure -> null
        }
    }

    private fun streamTone(
        streamer: AirPlayPcmStreamer,
        format: AirPlayAudioSetup.AudioFormat,
        durationMs: Int = AirPlayTestTone.DEFAULT_DURATION_MS
    ): Int {
        val chunkFrames = format.framesPerPacket * 6
        val totalFrames = format.sampleRate * durationMs / 1_000
        val chunkCount = ceil(totalFrames.toDouble() / chunkFrames.toDouble()).toInt()
        var packetCount = 0

        for (chunkIndex in 0 until chunkCount) {
            val startFrame = chunkIndex * chunkFrames
            val frameCount = minOf(chunkFrames, totalFrames - startFrame)
            val pcm = AirPlayTestTone.pcm16StereoChunk(
                sampleRate = format.sampleRate,
                startFrame = startFrame,
                frameCount = frameCount
            )
            packetCount += streamer.sendPcm(pcm)
            val sleepMs = frameCount * 1_000L / format.sampleRate
            if (sleepMs > 0) Thread.sleep(sleepMs)
        }

        return packetCount
    }

    private data class AudioControlSession(
        val pairSession: AirPlayPairVerifier.Session,
        val transport: AirPlayEncryptedChannel,
        val localRtspHost: String,
        val closeAction: () -> Unit
    ) : Closeable {
        override fun close() {
            closeAction()
        }
    }

    private sealed class ConnectionAttempt {
        data class Success(val session: AudioControlSession) : ConnectionAttempt()
        data class Failure(val message: String) : ConnectionAttempt()
    }

    private const val TRANSIENT_PAIRING_PASSCODE = "3939"
}
