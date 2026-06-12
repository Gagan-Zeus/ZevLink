package com.zevclip.sender.airplay

import java.io.Closeable
import android.util.Log
import java.util.UUID

class AirPlayVideoController(
    private val target: AirPlayTarget,
    private val identity: AirPlayIdentity,
    private val passcode: String,
    private val startPinPrompt: Boolean = true,
    private val status: (String) -> Unit = {}
) : Closeable {
    private data class ControlSession(
        val transport: AirPlayEncryptedChannel,
        val sharedSecret: ByteArray,
        val localRtspHost: String,
        val closeAction: () -> Unit
    ) : Closeable {
        override fun close() {
            closeAction()
        }
    }

    private var connected: ControlSession? = null
    private var eventChannel: AirPlayEventChannel? = null
    private var timingServer: AirPlayTimingServer? = null
    private var cseq = 1
    private val senderInfo = AirPlaySenderInfo.fromIdentity(identity)
    private val sessionUuid = UUID.randomUUID().toString().uppercase()
    private val playSessionId = UUID.randomUUID().toString()
    private val streamId = "1"
    private val rtspSessionId = CryptoPrimitives.randomBytes(4).fold(0L) { acc, byte ->
        (acc shl 8) or (byte.toLong() and 0xFF)
    }

    fun connect(): Result<Unit> {
        return runCatching {
            connected = connectPairVerified()
            val active = connected ?: error("AirPlay video control session is not connected.")
            setupVideoSession(active)
            Result.success(Unit)
        }.getOrElse { error ->
            Result.failure(error)
        }
    }

    fun play(url: String): Boolean {
        Log.i(TAG, "Starting AirPlay HLS playback: $url")
        val body = BPlist.encode(
            BPlist.dict(
                "Content-Location" to BPlist.string(url),
                "Start-Position-Seconds" to BPlist.real(0.0),
                "SenderMACAddress" to BPlist.string(senderInfo.deviceId),
                "model" to BPlist.string(senderInfo.model),
                "osBuildVersion" to BPlist.string(senderInfo.osBuildVersion),
                "clientBundleID" to BPlist.string("com.zevclip.sender"),
                "clientProcName" to BPlist.string("ZevLink"),
                "mediaType" to BPlist.string("file"),
                "streamType" to BPlist.int(1),
                "rate" to BPlist.real(1.0),
                "uuid" to BPlist.string(UUID.randomUUID().toString()),
                "volume" to BPlist.real(1.0),
                "mightSupportStorePastisKeyRequests" to BPlist.bool(true),
                "authMs" to BPlist.int(0),
                "bonjourMs" to BPlist.int(0),
                "connectMs" to BPlist.int(0),
                "infoMs" to BPlist.int(0),
                "postAuthMs" to BPlist.int(0),
                "secureConnectionMs" to BPlist.int(0),
                "playbackRestrictions" to BPlist.int(0),
                "referenceRestrictions" to BPlist.int(3)
            )
        )
        val play = http("POST", "/play", body, "application/x-apple-binary-plist")
        Log.i(TAG, "AirPlay /play response: ${play.statusCode} ${play.reasonPhrase}")
        if (!play.isSuccessful()) return false
        http(
            "PUT",
            "/setProperty?actionAtItemEnd",
            BPlist.encode(BPlist.dict("value" to BPlist.int(0))),
            "application/x-apple-binary-plist"
        )
        rate(1.0)
        return true
    }

    fun rate(value: Double): Boolean {
        return http("POST", "/rate?value=${"%.6f".format(java.util.Locale.US, value)}", ByteArray(0), null)
            .isSuccessful()
    }

    fun stop() {
        runCatching { http("POST", "/stop", ByteArray(0), null) }
    }

    private fun setupVideoSession(active: ControlSession) {
        val timing = AirPlayTimingServer()
        val timingPort = timing.start(0)
        timingServer = timing

        val setupBody = BPlist.encode(
            BPlist.dict(
                *senderInfo.setupEntries(),
                "sessionUUID" to BPlist.string(sessionUuid),
                "isMultiSelectAirPlay" to BPlist.bool(true),
                "timingProtocol" to BPlist.string("NTP"),
                "timingPort" to BPlist.int(timingPort.toLong()),
                "senderSupportsRelay" to BPlist.bool(true),
                "statsCollectionEnabled" to BPlist.bool(false)
            )
        )

        val setup = rtsp("SETUP", setupBody, "application/x-apple-binary-plist")
        if (!setup.isSuccessful()) {
            error("AirPlay video SETUP failed: ${setup.statusCode} ${setup.reasonPhrase}")
        }
        val eventPort = setup.body.eventPort()
        if (eventPort > 0) {
            eventChannel = AirPlayEventChannel(target.host, eventPort, active.sharedSecret)
                .also { it.start() }
        }

        rtsp("RECORD", ByteArray(0), null)
    }

    private fun connectPairVerified(): ControlSession {
        when (val verified = AirPlayPairVerifier(target, identity).verifyConnected()) {
            is AirPlayPairVerifier.ConnectedResult.Success -> {
                val session = verified.connectedSession
                return ControlSession(
                    transport = session.transport,
                    sharedSecret = session.session.sharedSecret,
                    localRtspHost = session.localRtspHost,
                    closeAction = { session.close() }
                )
            }
            is AirPlayPairVerifier.ConnectedResult.Failure -> {
                if (passcode.isBlank()) {
                    error(
                        if (startPinPrompt) {
                            "Enter the Mac AirPlay password first."
                        } else {
                            "Enter the screen AirPlay code shown on the Mac."
                        }
                    )
                }
                status(
                    if (startPinPrompt) {
                        "Starting Mac AirPlay PIN pairing for screen video..."
                    } else {
                        "Sending Mac screen AirPlay code..."
                    }
                )
                val setup = AirPlayPairSetupClient(target, identity)
                if (startPinPrompt) {
                    val pinStarted = setup.pairPinStart()
                    if (!pinStarted) {
                        status("Mac did not accept pair-pin-start. Trying saved AirPlay password directly...")
                    }
                }
                when (val result = setup.pairSetup(
                    passcode = passcode,
                    transient = false,
                    oneTime = startPinPrompt,
                    preferredHkpHeader = "3"
                )) {
                    is AirPlayPairSetupClient.Result.TransientSuccess -> {
                        return ControlSession(
                            transport = result.session.transport,
                            sharedSecret = result.session.session.sharedSecret,
                            localRtspHost = result.session.localRtspHost,
                            closeAction = { result.session.close() }
                        )
                    }
                    is AirPlayPairSetupClient.Result.PersistentSuccess -> {
                        setup.close()
                        return when (val retry = AirPlayPairVerifier(target, identity).verifyConnected()) {
                            is AirPlayPairVerifier.ConnectedResult.Success -> {
                                val session = retry.connectedSession
                                ControlSession(
                                    transport = session.transport,
                                    sharedSecret = session.session.sharedSecret,
                                    localRtspHost = session.localRtspHost,
                                    closeAction = { session.close() }
                                )
                            }
                            is AirPlayPairVerifier.ConnectedResult.Failure -> error(retry.message)
                        }
                    }
                    is AirPlayPairSetupClient.Result.Failure -> {
                        setup.close()
                        error(
                            if (startPinPrompt) {
                                "AirPlay pair-setup failed: ${result.message}. If the Mac shows a new code, enter that code and try again."
                            } else {
                                "AirPlay pair-setup failed: ${result.message}. The screen AirPlay code was rejected or expired. Tap AirPlay Screen to Mac again and enter the newest code shown on the Mac."
                            }
                        )
                    }
                }
            }
        }
    }

    private fun rtsp(method: String, body: ByteArray, contentType: String?): AirPlayRtspClient.Response {
        val active = connected ?: error("AirPlay video is not connected.")
        return active.transport.requestRaw(
            method = method,
            uri = "rtsp://${active.localRtspHost}/$rtspSessionId",
            protocol = "RTSP/1.0",
            headers = linkedMapOf(
                "CSeq" to (cseq++).toString(),
                "User-Agent" to USER_AGENT,
                "DACP-ID" to clientInstanceId(),
                "Active-Remote" to "1",
                "Client-Instance" to clientInstanceId()
            ),
            body = body,
            contentType = contentType
        )
    }

    private fun http(method: String, path: String, body: ByteArray, contentType: String?): AirPlayRtspClient.Response {
        val active = connected ?: error("AirPlay video is not connected.")
        return active.transport.requestRaw(
            method = method,
            uri = path,
            protocol = "HTTP/1.1",
            headers = linkedMapOf(
                "User-Agent" to USER_AGENT,
                "X-Apple-Session-ID" to playSessionId,
                "X-Apple-ProtocolVersion" to "1",
                "X-Apple-Stream-ID" to streamId
            ),
            body = body,
            contentType = contentType
        )
    }

    private fun clientInstanceId(): String {
        return identity.deviceId.filter { it.isLetterOrDigit() }.take(16).uppercase()
    }

    override fun close() {
        stop()
        runCatching { eventChannel?.stop() }
        eventChannel = null
        runCatching { timingServer?.stop() }
        timingServer = null
        runCatching { connected?.close() }
        connected = null
    }

    private fun ByteArray.eventPort(): Int {
        val decoded = runCatching { BPlist.decode(this) }.getOrNull() as? BPlist.Value.DictValue
            ?: return 0
        return (decoded.values["eventPort"] as? BPlist.Value.IntValue)?.value?.toInt() ?: 0
    }

    private companion object {
        private const val TAG = "ZevClipAirPlayVideo"
        private const val USER_AGENT = "AirPlay/${AirPlaySenderInfo.SOURCE_VERSION} ZevLink"
    }
}
