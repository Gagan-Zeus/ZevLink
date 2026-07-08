package com.zevclip.sender.airplay

import java.io.Closeable
import java.net.DatagramSocket

class AirPlayLiveAudioSession(
    private val target: AirPlayTarget,
    private val identity: AirPlayIdentity,
    private val passcode: String = "",
    private val controlSocketFactory: () -> DatagramSocket = { DatagramSocket() },
    private val preparedSessionFactory: (
        AudioControlSession,
        DatagramSocket
    ) -> AirPlayPreparedAudioSession = { connectedSession, controlSocket ->
        AirPlayPreparedAudioSession(
            target = target,
            pairVerifySession = connectedSession.pairSession,
            transport = connectedSession.transport,
            identity = identity,
            localRtspHost = connectedSession.localRtspHost,
            syncSenderFactory = { clock, port ->
                AirPlaySyncSender(
                    clock = clock,
                    host = target.host,
                    port = port,
                    socket = controlSocket
                )
            }
        )
    }
) : Closeable {
    data class Started(
        val format: AirPlayAudioSetup.AudioFormat,
        val streamer: AirPlayPcmStreamer
    )

    sealed class Result {
        data class Success(val session: Started) : Result()
        data class Failure(val message: String, val cause: Throwable? = null) : Result()
    }

    private var audioControlSession: AudioControlSession? = null
    private var preparedSession: AirPlayPreparedAudioSession? = null
    private var controlSocket: DatagramSocket? = null
    private var streamer: AirPlayPcmStreamer? = null

    fun start(
        format: AirPlayAudioSetup.AudioFormat = AirPlayAudioSetup.AudioFormat(
            sampleRate = 44_100,
            channels = 2,
            framesPerPacket = 352,
            useAlac = true
        ),
        status: (String) -> Unit = {}
    ): Result {
        return runCatching {
            status("Checking paired Mac AirPlay audio...")
            val connected = connectForAudio(status)
            audioControlSession = connected

            val nextControlSocket = controlSocketFactory()
            controlSocket = nextControlSocket
            val nextPreparedSession = preparedSessionFactory(connected, nextControlSocket)
            preparedSession = nextPreparedSession

            status("Starting high-quality AirPlay audio session...")
            val running = when (
                val preparedResult = nextPreparedSession.start(
                    controlPort = nextControlSocket.localPort,
                    format = format,
                    useAlacPayloads = true
                )
            ) {
                is AirPlayPreparedAudioSession.Result.Success -> preparedResult.session
                is AirPlayPreparedAudioSession.Result.Failure -> {
                    error(preparedResult.message)
                }
            }

            streamer = running.streamer
            Result.Success(Started(format = format, streamer = running.streamer))
        }.getOrElse { error ->
            close()
            Result.Failure("AirPlay audio setup failed: ${error.message ?: "unknown error"}", error)
        }
    }

    private fun connectForAudio(status: (String) -> Unit): AudioControlSession {
        verifyConnected()?.let { return it }

        val normalizedPasscode = passcode.trim()
        if (normalizedPasscode.isNotBlank()) {
            status("Pairing with saved Mac AirPlay password...")
            val setupClient = AirPlayPairSetupClient(target, identity)
            when (val setup = setupClient.pairSetup(passcode = normalizedPasscode, transient = false)) {
                is AirPlayPairSetupClient.Result.PersistentSuccess -> setupClient.close()
                is AirPlayPairSetupClient.Result.TransientSuccess -> {
                    return AudioControlSession(
                        pairSession = setup.session.session,
                        transport = setup.session.transport,
                        localRtspHost = setup.session.localRtspHost,
                        closeAction = { setup.session.close() }
                    )
                }
                is AirPlayPairSetupClient.Result.Failure -> {
                    setupClient.close()
                    error("Mac rejected AirPlay password: ${setup.message}")
                }
            }

            return verifyConnected()
                ?: error("AirPlay password was accepted, but pair-verify still failed.")
        }

        status("Preparing temporary AirPlay audio pairing...")
        val transientClient = AirPlayPairSetupClient(target, identity)
        return when (val transient = transientClient.pairSetup(passcode = TRANSIENT_PAIRING_PASSCODE, transient = true)) {
            is AirPlayPairSetupClient.Result.TransientSuccess -> AudioControlSession(
                pairSession = transient.session.session,
                transport = transient.session.transport,
                localRtspHost = transient.session.localRtspHost,
                closeAction = { transient.session.close() }
            )
            is AirPlayPairSetupClient.Result.PersistentSuccess -> {
                transientClient.close()
                error("Temporary AirPlay pairing returned an unexpected persistent session.")
            }
            is AirPlayPairSetupClient.Result.Failure -> {
                transientClient.close()
                error("Temporary AirPlay pairing failed: ${transient.message}")
            }
        }
    }

    private fun verifyConnected(): AudioControlSession? {
        return when (val result = AirPlayPairVerifier(target, identity).verifyConnected()) {
            is AirPlayPairVerifier.ConnectedResult.Success -> AudioControlSession(
                pairSession = result.connectedSession.session,
                transport = result.connectedSession.transport,
                localRtspHost = result.connectedSession.localRtspHost,
                closeAction = { result.connectedSession.close() }
            )
            is AirPlayPairVerifier.ConnectedResult.Failure -> null
        }
    }

    override fun close() {
        runCatching { streamer?.close() }
        streamer = null
        runCatching { preparedSession?.stop() }
        preparedSession = null
        runCatching { controlSocket?.close() }
        controlSocket = null
        runCatching { audioControlSession?.close() }
        audioControlSession = null
    }

    data class AudioControlSession(
        val pairSession: AirPlayPairVerifier.Session,
        val transport: AirPlayEncryptedChannel,
        val localRtspHost: String,
        private val closeAction: () -> Unit
    ) : Closeable {
        override fun close() {
            closeAction()
        }
    }

    private companion object {
        const val TRANSIENT_PAIRING_PASSCODE = "3939"
    }
}
