package com.zevclip.sender.airplay

import android.util.Log
import java.io.Closeable
import java.util.UUID
import kotlin.concurrent.thread

class AirPlayMirrorSessionController(
    private val target: AirPlayTarget,
    private val identity: AirPlayIdentity,
    private val passcode: String
) : Closeable {
    data class PreparedMirror(
        val dataPort: Int,
        val streamConnectionId: Long,
        val dataStreamKey: ByteArray,
        val audio: PreparedAudio? = null
    )

    data class PreparedAudio(
        val dataPort: Int,
        val controlPort: Int,
        val streamConnectionId: Long,
        val audioSharedKey: ByteArray,
        val sampleRate: Int = AUDIO_SAMPLE_RATE,
        val framesPerPacket: Int = AUDIO_FRAMES_PER_PACKET,
        val channels: Int = AUDIO_CHANNELS
    )

    private var connected: AirPlayPairVerifier.ConnectedSession? = null
    private var eventChannel: AirPlayEventChannel? = null
    private var timingServer: AirPlayTimingServer? = null
    @Volatile
    private var keepAliveRunning = false
    private var keepAliveWorker: Thread? = null
    private val requestLock = Any()
    private var cseq = 1
    private val senderInfo = AirPlaySenderInfo.fromIdentity(identity)
    private val rtspSessionId = CryptoPrimitives.randomBytes(4).toUInt32Be()
    private val sessionUuid = UUID.randomUUID().toString().uppercase()

    fun prepare(audioControlPort: Int? = null): PreparedMirror {
        val session = connectPairVerified()
        val timing = AirPlayTimingServer()
        val timingPort = timing.start(0)
        timingServer = timing

        val setup1 = session.transport.requestRaw(
            method = "SETUP",
            uri = rtspUri(session.localRtspHost),
            protocol = "RTSP/1.0",
            headers = rtspHeaders(),
            body = BPlist.encode(
                BPlist.dict(
                    *senderInfo.setupEntries(),
                    "sessionUUID" to BPlist.string(sessionUuid),
                    "isMultiSelectAirPlay" to BPlist.bool(true),
                    "timingProtocol" to BPlist.string("NTP"),
                    "timingPort" to BPlist.int(timingPort.toLong()),
                    "senderSupportsRelay" to BPlist.bool(true),
                    "statsCollectionEnabled" to BPlist.bool(false),
                    "groupContainsGroupLeader" to BPlist.bool(false)
                )
            ),
            contentType = BPLIST_CONTENT_TYPE
        )
        if (!setup1.isSuccessful()) {
            error("AirPlay mirror SETUP #1 failed: ${setup1.statusCode} ${setup1.reasonPhrase}")
        }
        val eventPort = setup1.body.intAt("eventPort")
        if (eventPort > 0) {
            eventChannel = startEventChannel(eventPort, session.session.sharedSecret)
        }

        val streamConnectionId = rtspSessionId
        val controlKeys = AirPlayEncryptedChannel.controllerKeys(session.session.sharedSecret)
        val advertisedStreamKey = controlKeys.writeKey.copyOfRange(0, STREAM_AES_KEY_SIZE)
        val advertisedStreamIv = controlKeys.readKey.copyOfRange(0, STREAM_AES_KEY_SIZE)
        val dataStreamKey = CryptoPrimitives.hkdfSha512(
            inputKeyMaterial = session.session.sharedSecret,
            salt = "DataStream-Salt$streamConnectionId".toByteArray(Charsets.US_ASCII),
            info = "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            outputSize = DATA_STREAM_KEY_SIZE
        )
        val setup2 = session.transport.requestRaw(
            method = "SETUP",
            uri = rtspUri(session.localRtspHost),
            protocol = "RTSP/1.0",
            headers = rtspHeaders(),
            body = BPlist.encode(
                BPlist.dict(
                    *senderInfo.setupEntries(),
                    "sessionUUID" to BPlist.string(sessionUuid),
                    "isScreenMirroringSession" to BPlist.bool(true),
                    "timingProtocol" to BPlist.string("NTP"),
                    "timingPort" to BPlist.int(timingPort.toLong()),
                    "streams" to BPlist.array(*streamSetupEntries(
                        videoStreamConnectionId = streamConnectionId,
                        videoStreamKey = advertisedStreamKey,
                        videoStreamIv = advertisedStreamIv,
                        audioControlPort = audioControlPort,
                        audioSharedKey = AirPlayAudioSetup.deriveAudioSharedKey(session.session.sharedSecret)
                    ))
                )
            ),
            contentType = BPLIST_CONTENT_TYPE
        )
        if (!setup2.isSuccessful()) {
            error("AirPlay mirror SETUP #2 failed: ${setup2.statusCode} ${setup2.reasonPhrase}")
        }
        val dataPort = setup2.body.streamInt(MIRROR_STREAM_TYPE, "dataPort")
        if (dataPort <= 0) {
            error("AirPlay mirror SETUP #2 did not return a dataPort.")
        }
        val preparedAudio = if (audioControlPort != null) {
            val audioDataPort = setup2.body.streamInt(AUDIO_STREAM_TYPE, "dataPort")
            val audioRemoteControlPort = setup2.body.streamInt(AUDIO_STREAM_TYPE, "controlPort")
            if (audioDataPort > 0) {
                PreparedAudio(
                    dataPort = audioDataPort,
                    controlPort = audioRemoteControlPort,
                    streamConnectionId = AUDIO_STREAM_CONNECTION_ID,
                    audioSharedKey = AirPlayAudioSetup.deriveAudioSharedKey(session.session.sharedSecret)
                )
            } else {
                null
            }
        } else {
            null
        }
        Log.i(
            TAG,
            "AirPlay mirror SETUP complete: dataPort=$dataPort streamConnectionID=$streamConnectionId audio=${preparedAudio != null}"
        )
        return PreparedMirror(dataPort, streamConnectionId, dataStreamKey, preparedAudio)
    }

    fun recordBestEffort() {
        val session = connected ?: return
        sendRecordBestEffort(session)
        startKeepAlive(session)
    }

    fun flushAudioBestEffort(sequenceNumber: Int, rtpTimestamp: Int) {
        val session = connected ?: return
        runCatching {
            synchronized(requestLock) {
                session.transport.requestRaw(
                    method = "FLUSH",
                    uri = rtspUri(session.localRtspHost),
                    protocol = "RTSP/1.0",
                    headers = rtspHeaders(
                        "Session" to sessionUuid,
                        "Range" to "npt=0-",
                        "RTP-Info" to "seq=${sequenceNumber and 0xFFFF};rtptime=${rtpTimestamp.toLong() and 0xFFFFFFFFL}"
                    ),
                    body = ByteArray(0),
                    contentType = null
                )
            }
        }.onFailure { error ->
            Log.d(TAG, "AirPlay mirror audio FLUSH failed; continuing: ${error.message}")
        }
    }

    fun setAudioVolumeBestEffort(volume: Double = 0.0) {
        val session = connected ?: return
        runCatching {
            synchronized(requestLock) {
                session.transport.requestRaw(
                    method = "SET_PARAMETER",
                    uri = rtspUri(session.localRtspHost),
                    protocol = "RTSP/1.0",
                    headers = rtspHeaders("Session" to sessionUuid),
                    body = "volume: $volume\r\n".toByteArray(Charsets.US_ASCII),
                    contentType = "text/parameters"
                )
            }
        }.onFailure { error ->
            Log.d(TAG, "AirPlay mirror audio volume set failed; continuing: ${error.message}")
        }
    }

    private fun connectPairVerified(): AirPlayPairVerifier.ConnectedSession {
        when (val verified = AirPlayPairVerifier(target, identity).verifyConnected()) {
            is AirPlayPairVerifier.ConnectedResult.Success -> {
                connected = verified.connectedSession
                return verified.connectedSession
            }
            is AirPlayPairVerifier.ConnectedResult.Failure -> {
                if (passcode.isBlank()) error("Enter the screen AirPlay code shown on the Mac.")
                val setup = AirPlayPairSetupClient(target, identity)
                try {
                    when (val result = setup.pairSetup(
                        passcode = passcode,
                        transient = false,
                        oneTime = false,
                        preferredHkpHeader = "3"
                    )) {
                        is AirPlayPairSetupClient.Result.PersistentSuccess -> Unit
                        is AirPlayPairSetupClient.Result.TransientSuccess -> result.session.close()
                        is AirPlayPairSetupClient.Result.Failure -> {
                            error("AirPlay pair-setup failed: ${result.message}. The screen AirPlay code was rejected or expired. Tap AirPlay Screen to Mac again and enter the newest code shown on the Mac.")
                        }
                    }
                } finally {
                    setup.close()
                }
                return when (val retry = AirPlayPairVerifier(target, identity).verifyConnected()) {
                    is AirPlayPairVerifier.ConnectedResult.Success -> {
                        connected = retry.connectedSession
                        retry.connectedSession
                    }
                    is AirPlayPairVerifier.ConnectedResult.Failure -> error(retry.message)
                }
            }
        }
    }

    private fun rtspHeaders(vararg extraHeaders: Pair<String, String>): Map<String, String> {
        val instanceId = identity.deviceId.filter { it.isLetterOrDigit() }.take(16).uppercase()
        return linkedMapOf(
            "CSeq" to (cseq++).toString(),
            "User-Agent" to USER_AGENT,
            "DACP-ID" to instanceId,
            "Active-Remote" to "1",
            "Client-Instance" to instanceId
        ).apply { putAll(extraHeaders) }
    }

    private fun rtspUri(localHost: String): String {
        return AirPlayRtspClient.rtspUrl(localHost, target.port, "/$rtspSessionId")
    }

    private fun startEventChannel(eventPort: Int, sharedSecret: ByteArray): AirPlayEventChannel {
        var lastError: Throwable? = null
        repeat(EVENT_CHANNEL_ATTEMPTS) { attempt ->
            val channel = AirPlayEventChannel(target.host, eventPort, sharedSecret)
            val started = runCatching { channel.start() }
                .onFailure { lastError = it }
                .getOrDefault(false)
            if (started) {
                Log.i(TAG, "AirPlay mirror event channel connected on attempt ${attempt + 1}.")
                return channel
            }
            runCatching { channel.stop() }
            Thread.sleep(EVENT_CHANNEL_RETRY_MS)
        }
        error("AirPlay mirror event channel did not connect to port $eventPort.${lastError?.message?.let { " $it" } ?: ""}")
    }

    private fun namedInfoArray(vararg names: String): BPlist.Value.ArrayValue {
        return BPlist.Value.ArrayValue(names.map { name ->
            BPlist.dict("name" to BPlist.string(name))
        })
    }

    private fun streamSetupEntries(
        videoStreamConnectionId: Long,
        videoStreamKey: ByteArray,
        videoStreamIv: ByteArray,
        audioControlPort: Int?,
        audioSharedKey: ByteArray
    ): Array<BPlist.Value> {
        val streams = mutableListOf<BPlist.Value>(
            BPlist.dict(
                "type" to BPlist.int(MIRROR_STREAM_TYPE),
                "streamConnectionID" to BPlist.int(videoStreamConnectionId),
                "shk" to BPlist.data(videoStreamKey),
                "shiv" to BPlist.data(videoStreamIv),
                "timestampInfo" to namedInfoArray("SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc")
            )
        )
        if (audioControlPort != null) {
            streams += BPlist.dict(
                "type" to BPlist.int(AUDIO_STREAM_TYPE),
                "ct" to BPlist.int(AUDIO_CONTENT_TYPE_ALAC),
                "audioFormat" to BPlist.int(AUDIO_FORMAT_ALAC_44100_16_2),
                "audioMode" to BPlist.string("default"),
                "isMedia" to BPlist.bool(true),
                "usingScreen" to BPlist.bool(true),
                "sr" to BPlist.int(AUDIO_SAMPLE_RATE.toLong()),
                "spf" to BPlist.int(AUDIO_FRAMES_PER_PACKET.toLong()),
                "shk" to BPlist.data(audioSharedKey),
                "latencyMin" to BPlist.int(AUDIO_LATENCY_MIN),
                "latencyMax" to BPlist.int(AUDIO_LATENCY_MAX),
                "controlPort" to BPlist.int(audioControlPort.toLong()),
                "supportsDynamicStreamID" to BPlist.bool(false),
                "streamConnectionID" to BPlist.int(AUDIO_STREAM_CONNECTION_ID)
            )
        }
        return streams.toTypedArray()
    }

    private fun sendRecordBestEffort(session: AirPlayPairVerifier.ConnectedSession) {
        runCatching {
            val record = synchronized(requestLock) {
                session.transport.requestRaw(
                    method = "RECORD",
                    uri = rtspUri(session.localRtspHost),
                    protocol = "RTSP/1.0",
                    headers = rtspHeaders(
                        "Session" to sessionUuid,
                        "Range" to "npt=0-",
                        "RTP-Info" to "seq=0;rtptime=0"
                    ),
                    body = ByteArray(0),
                    contentType = null
                )
            }
            Log.i(TAG, "AirPlay mirror RECORD response: ${record.statusCode} ${record.reasonPhrase}")
        }.onFailure { error ->
            Log.i(TAG, "AirPlay mirror RECORD did not return a response; continuing: ${error.message}")
        }
    }

    private fun startKeepAlive(session: AirPlayPairVerifier.ConnectedSession) {
        if (keepAliveRunning) return
        keepAliveRunning = true
        keepAliveWorker = thread(name = "zevclip-airplay-mirror-rtsp-keepalive", isDaemon = true) {
            var lastParameterAt = 0L
            sendFeedbackBestEffort(session)
            while (keepAliveRunning) {
                try {
                    Thread.sleep(FEEDBACK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                sendFeedbackBestEffort(session)
                val now = System.currentTimeMillis()
                if (now - lastParameterAt >= GET_PARAMETER_INTERVAL_MS) {
                    sendGetParameterBestEffort(session)
                    lastParameterAt = now
                }
            }
        }
    }

    private fun sendFeedbackBestEffort(session: AirPlayPairVerifier.ConnectedSession) {
        runCatching {
            synchronized(requestLock) {
                session.transport.requestRaw(
                    method = "POST",
                    uri = "/feedback",
                    protocol = "RTSP/1.0",
                    headers = rtspHeaders("Session" to sessionUuid),
                    body = ByteArray(0),
                    contentType = null
                )
            }
        }.onFailure { error ->
            if (keepAliveRunning) Log.d(TAG, "AirPlay mirror feedback keepalive failed: ${error.message}")
        }
    }

    private fun sendGetParameterBestEffort(session: AirPlayPairVerifier.ConnectedSession) {
        runCatching {
            synchronized(requestLock) {
                session.transport.requestRaw(
                    method = "GET_PARAMETER",
                    uri = rtspUri(session.localRtspHost),
                    protocol = "RTSP/1.0",
                    headers = rtspHeaders("Session" to sessionUuid),
                    body = ByteArray(0),
                    contentType = null
                )
            }
        }.onFailure { error ->
            if (keepAliveRunning) Log.d(TAG, "AirPlay mirror GET_PARAMETER keepalive failed: ${error.message}")
        }
    }

    override fun close() {
        keepAliveRunning = false
        keepAliveWorker?.interrupt()
        keepAliveWorker = null
        runCatching { eventChannel?.stop() }
        eventChannel = null
        runCatching { timingServer?.stop() }
        timingServer = null
        runCatching { connected?.close() }
        connected = null
    }

    private companion object {
        const val TAG = "ZevClipAirPlayMirrorSetup"
        const val BPLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val USER_AGENT = "AirPlay/${AirPlaySenderInfo.SOURCE_VERSION} ZevLink"
        const val MIRROR_STREAM_TYPE = 110L
        const val AUDIO_STREAM_TYPE = 96L
        const val AUDIO_STREAM_CONNECTION_ID = 0x5A435641L
        const val AUDIO_SAMPLE_RATE = 44_100
        const val AUDIO_CHANNELS = 2
        const val AUDIO_FRAMES_PER_PACKET = 352
        const val AUDIO_CONTENT_TYPE_ALAC = 2L
        const val AUDIO_FORMAT_ALAC_44100_16_2 = 1L shl 18
        const val AUDIO_LATENCY_MIN = 6_615L
        const val AUDIO_LATENCY_MAX = 33_075L
        const val EVENT_CHANNEL_ATTEMPTS = 6
        const val EVENT_CHANNEL_RETRY_MS = 350L
        const val STREAM_AES_KEY_SIZE = 16
        const val DATA_STREAM_KEY_SIZE = 32
        const val FEEDBACK_INTERVAL_MS = 2_000L
        const val GET_PARAMETER_INTERVAL_MS = 15_000L
    }
}

private fun ByteArray.toUInt32Be(): Long {
    var value = 0L
    take(4).forEach { byte ->
        value = (value shl 8) or (byte.toLong() and 0xFFL)
    }
    return value
}

private fun ByteArray.intAt(key: String): Int {
    val dict = runCatching { BPlist.decode(this) }.getOrNull() as? BPlist.Value.DictValue
        ?: return 0
    return (dict.values[key] as? BPlist.Value.IntValue)?.value?.toInt() ?: 0
}

private fun ByteArray.streamInt(streamType: Long, key: String): Int {
    val dict = runCatching { BPlist.decode(this) }.getOrNull() as? BPlist.Value.DictValue
        ?: return 0
    val streams = dict.values["streams"] as? BPlist.Value.ArrayValue ?: return 0
    val matching = streams.values
        .mapNotNull { it as? BPlist.Value.DictValue }
        .firstOrNull { stream ->
            (stream.values["type"] as? BPlist.Value.IntValue)?.value == streamType
        }
        ?: return 0
    return (matching.values[key] as? BPlist.Value.IntValue)?.value?.toInt() ?: 0
}
