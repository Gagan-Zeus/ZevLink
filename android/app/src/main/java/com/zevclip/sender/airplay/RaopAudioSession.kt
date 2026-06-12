package com.zevclip.sender.airplay

import com.zevclip.sender.AirPlayDacpSession
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

class RaopAudioSession(
    private val target: AirPlayTarget,
    private val password: String,
    private val identity: AirPlayIdentity? = null,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val socketFactory: () -> Socket = { Socket() },
    private val dacpSession: AirPlayDacpSession? = null
) : Closeable {
    data class Started(
        val target: AirPlayTarget,
        val session: RaopAudioSession
    )

    private data class DigestChallenge(
        val realm: String,
        val nonce: String
    )

    private data class RtspResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]
        fun isSuccessful(): Boolean = statusCode in 200..299
    }

    private var socket: Socket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingServer: AirPlayTimingServer? = null
    private var syncSender: AirPlaySyncSender? = null
    private var audioSender: AirPlayUdpPacketSender? = null
    private var clock: AirPlayClock? = null
    private var cseq = 1
    private var digestChallenge: DigestChallenge? = null
    private var sessionUri: String = ""
    private var rtspSession: String = ""
    private var sequenceNumber = 0
    private var ssrc = 0
    private var packetCount = 0

    @Volatile
    private var keepAliveRunning = false
    private var keepAliveThread: Thread? = null

    fun start(playbackClock: AirPlayClock = AirPlayClock(sampleRate = SAMPLE_RATE, latencyFrames = DEFAULT_LATENCY_FRAMES)) {
        connect()
        val localHost = requireNotNull(socket?.localAddress?.hostAddress) {
            "RAOP local address is not available."
        }
        val sessionId = randomUnsignedInt().toLong() and 0xFFFFFFFFL
        sessionUri = rtspUrl(localHost, sessionId)
        ssrc = sessionId.toInt()

        announce(localHost, sessionId)

        val nextControlSocket = DatagramSocket(0, InetAddress.getByName(localHost))
        controlSocket = nextControlSocket
        val nextTimingServer = AirPlayTimingServer()
        timingServer = nextTimingServer
        val timingPort = nextTimingServer.start(0)
        val setup = setup(
            controlPort = nextControlSocket.localPort,
            timingPort = timingPort
        )

        val remotePorts = parseTransport(setup.header("transport").orEmpty())
        val serverPort = remotePorts["server_port"]?.toIntOrNull()
            ?: error("RAOP SETUP did not return an audio server port.")
        val remoteControlPort = remotePorts["control_port"]?.toIntOrNull() ?: 0
        rtspSession = setup.header("session")?.substringBefore(';')?.trim().orEmpty()

        clock = playbackClock

        if (remoteControlPort > 0) {
            syncSender = AirPlaySyncSender(
                clock = playbackClock,
                host = target.host,
                port = remoteControlPort,
                socket = nextControlSocket
            ).also { it.start() }
        }

        sequenceNumber = randomUnsignedShort()
        record()
        flush(rtspSession, sequenceNumber, playbackClock.rtpTime32)
        setVolume()
        audioSender = AirPlayUdpPacketSender(target.host, serverPort)
        startKeepAlive()
    }

    fun sendPcm(pcmLittleEndian: ByteArray, frameCount: Int) {
        if (frameCount <= 0) return
        val activeClock = clock ?: error("RAOP clock is not ready.")
        val sink = audioSender ?: error("RAOP audio sender is not ready.")
        val payload = pcmLittleEndian.paddedBigEndian(frameCount, FRAMES_PER_PACKET)
        val packet = AirPlayRtpPacket.header(
            sequenceNumber = sequenceNumber,
            timestamp = activeClock.rtpTime32,
            ssrc = ssrc,
            marker = packetCount == 0
        ) + payload
        sink.send(packet)
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        packetCount++
    }

    fun advanceClock(frames: Int) {
        val activeClock = clock ?: error("RAOP clock is not ready.")
        activeClock.advance(frames)
    }

    private fun connect() {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        val nextSocket = socketFactory()
        nextSocket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
        nextSocket.soTimeout = readTimeoutMs
        nextSocket.tcpNoDelay = true
        socket = nextSocket
    }

    private fun announce(localHost: String, sessionId: Long) {
        val sdp = buildSdp(localHost, sessionId).toByteArray(Charsets.US_ASCII)
        val first = request(
            method = "ANNOUNCE",
            uri = sessionUri,
            headers = mapOf("Content-Type" to "application/sdp"),
            body = sdp,
            useDigest = false
        )
        if (first.statusCode == 401) {
            val normalizedPassword = password.trim()
            if (normalizedPassword.isBlank()) {
                error("${target.name ?: target.host} requires an AirPlay password.")
            }
            digestChallenge = parseDigest(first.header("www-authenticate"))
                ?: error("RAOP password challenge did not include Digest details.")
            val retry = request(
                method = "ANNOUNCE",
                uri = sessionUri,
                headers = mapOf("Content-Type" to "application/sdp"),
                body = sdp,
                useDigest = true
            )
            if (!retry.isSuccessful()) {
                error("${target.name ?: target.host} rejected AirPlay password: ${retry.statusCode} ${retry.reasonPhrase}.")
            }
            return
        }

        if (!first.isSuccessful()) {
            error("RAOP ANNOUNCE failed for ${target.name ?: target.host}: ${first.statusCode} ${first.reasonPhrase}.")
        }
    }

    private fun setup(controlPort: Int, timingPort: Int): RtspResponse {
        val response = request(
            method = "SETUP",
            uri = sessionUri,
            headers = mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=$controlPort;timing_port=$timingPort"
            ),
            body = ByteArray(0),
            useDigest = digestChallenge != null
        )
        if (!response.isSuccessful()) {
            error("RAOP SETUP failed for ${target.name ?: target.host}: ${response.statusCode} ${response.reasonPhrase}.")
        }
        return response
    }

    private fun record() {
        val response = request(
            method = "RECORD",
            uri = sessionUri,
            headers = emptyMap(),
            body = ByteArray(0),
            useDigest = digestChallenge != null
        )
        if (!response.isSuccessful()) {
            error("RAOP RECORD failed for ${target.name ?: target.host}: ${response.statusCode} ${response.reasonPhrase}.")
        }
    }

    private fun flush(session: String, sequenceNumber: Int, rtpTimestamp: Int) {
        val headers = buildMap {
            put("Range", "npt=0-")
            if (session.isNotBlank()) put("Session", session)
            put("RTP-Info", "seq=${sequenceNumber and 0xFFFF};rtptime=${rtpTimestamp.toUIntString()}")
        }
        request(
            method = "FLUSH",
            uri = sessionUri,
            headers = headers,
            body = ByteArray(0),
            useDigest = digestChallenge != null
        )
    }

    private fun setVolume() {
        request(
            method = "SET_PARAMETER",
            uri = sessionUri,
            headers = mapOf("Content-Type" to "text/parameters"),
            body = "volume: 0.000000\r\n".toByteArray(Charsets.US_ASCII),
            useDigest = digestChallenge != null
        )
    }

    private fun startKeepAlive() {
        keepAliveRunning = true
        keepAliveThread = thread(name = "zevclip-raop-broadcast-keepalive", isDaemon = true) {
            while (keepAliveRunning) {
                try {
                    Thread.sleep(KEEP_ALIVE_INTERVAL_MS)
                    if (!keepAliveRunning) break
                    request(
                        method = "OPTIONS",
                        uri = "*",
                        headers = emptyMap(),
                        body = ByteArray(0),
                        useDigest = digestChallenge != null
                    )
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    break
                }
            }
        }
    }

    @Synchronized
    private fun request(
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: ByteArray,
        useDigest: Boolean
    ): RtspResponse {
        connect()
        val activeSocket = socket ?: error("RAOP socket is not connected.")
        val output = activeSocket.getOutputStream()
        val requestHeaders = LinkedHashMap<String, String>().apply {
            put("CSeq", (cseq++).toString())
            put("User-Agent", USER_AGENT)
            identity?.let { airPlayIdentity ->
                put("X-Apple-Device-ID", airPlayIdentity.deviceId)
                put("X-Apple-Client-Name", airPlayIdentity.senderName.asciiHeaderValue())
                if (dacpSession == null) {
                    put("Client-Instance", airPlayIdentity.clientInstanceId())
                }
            }
            dacpSession?.let { session ->
                put("DACP-ID", session.dacpId)
                put("Active-Remote", session.activeRemote)
                put("Client-Instance", session.dacpId)
            }
            if (useDigest) {
                put("Authorization", digestAuthorization(method, uri))
            }
            putAll(headers)
            if (body.isNotEmpty()) put("Content-Length", body.size.toString())
        }
        val requestText = buildString {
            append(method)
            append(' ')
            append(uri)
            append(" RTSP/1.0\r\n")
            requestHeaders.forEach { (name, value) ->
                append(name)
                append(": ")
                append(value)
                append("\r\n")
            }
            append("\r\n")
        }

        output.write(requestText.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
        return readResponse(activeSocket.getInputStream())
    }

    private fun digestAuthorization(method: String, uri: String): String {
        val challenge = digestChallenge ?: error("RAOP Digest challenge is not available.")
        val ha1 = md5("$DIGEST_USERNAME:${challenge.realm}:$password")
        val ha2 = md5("$method:$uri")
        val response = md5("$ha1:${challenge.nonce}:$ha2")
        return "Digest username=\"$DIGEST_USERNAME\", realm=\"${challenge.realm}\", nonce=\"${challenge.nonce}\", uri=\"$uri\", response=\"$response\""
    }

    private fun readResponse(input: InputStream): RtspResponse {
        val headerBytes = ByteArrayOutputStream()
        var state = 0
        while (state < HEADER_END.size) {
            val nextByte = input.read()
            require(nextByte >= 0) { "RAOP response ended before headers completed." }
            headerBytes.write(nextByte)
            state = if (nextByte.toByte() == HEADER_END[state]) {
                state + 1
            } else if (nextByte.toByte() == HEADER_END[0]) {
                1
            } else {
                0
            }
        }

        val headerText = headerBytes.toString(Charsets.US_ASCII.name())
        val lines = headerText.split("\r\n")
        val statusMatch = STATUS_LINE_REGEX.matchEntire(lines.firstOrNull().orEmpty())
            ?: error("RAOP response status line is invalid.")
        val headers = LinkedHashMap<String, String>()
        lines.drop(1).forEach { line ->
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                    line.substring(separator + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            require(read >= 0) { "RAOP response body ended early." }
            offset += read
        }
        return RtspResponse(
            statusCode = statusMatch.groupValues[1].toInt(),
            reasonPhrase = statusMatch.groupValues[2],
            headers = headers,
            body = body
        )
    }

    override fun close() {
        keepAliveRunning = false
        keepAliveThread?.interrupt()
        keepAliveThread = null
        runCatching { syncSender?.stop() }
        syncSender = null
        runCatching { audioSender?.close() }
        audioSender = null
        runCatching { controlSocket?.close() }
        controlSocket = null
        runCatching { timingServer?.stop() }
        timingServer = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun buildSdp(localHost: String, sessionId: Long): String {
        val localFamily = addressFamily(localHost)
        val remoteFamily = addressFamily(target.host)
        return buildString {
            append("v=0\r\n")
            append("o=iTunes $sessionId 0 IN $localFamily $localHost\r\n")
            append("s=${identity?.senderName?.sdpLineValue() ?: "ZevLink"}\r\n")
            append("c=IN $remoteFamily ${target.host}\r\n")
            append("t=0 0\r\n")
            append("m=audio 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 L16/$SAMPLE_RATE/2\r\n")
            append("a=fmtp:96 $FRAMES_PER_PACKET 0 16 40 10 14 2 255 0 0 $SAMPLE_RATE\r\n")
        }
    }

    private fun parseTransport(value: String): Map<String, String> {
        return value.split(';')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else part.substring(0, separator).trim()
                    .lowercase(Locale.US) to part.substring(separator + 1).trim()
            }
            .toMap()
    }

    private fun parseDigest(value: String?): DigestChallenge? {
        if (value.isNullOrBlank() || !value.startsWith("Digest", ignoreCase = true)) return null
        fun parameter(name: String): String? {
            val match = Regex("""$name="?([^",]+)"?""", RegexOption.IGNORE_CASE).find(value)
            return match?.groupValues?.get(1)
        }
        val realm = parameter("realm") ?: return null
        val nonce = parameter("nonce") ?: return null
        return DigestChallenge(realm, nonce)
    }

    private fun ByteArray.paddedBigEndian(frameCount: Int, framesPerPacket: Int): ByteArray {
        val output = ByteArray(framesPerPacket * 2 * 2)
        var index = 0
        val copyBytes = frameCount * 2 * 2
        while (index + 1 < copyBytes && index + 1 < size) {
            output[index] = this[index + 1]
            output[index + 1] = this[index]
            index += 2
        }
        return output
    }

    private fun Int.toUIntString(): String {
        return (toLong() and 0xFFFFFFFFL).toString()
    }

    private fun addressFamily(host: String): String = if (host.contains(':')) "IP6" else "IP4"

    private fun rtspUrl(localHost: String, sessionId: Long): String {
        return "rtsp://${formatHost(localHost)}/$sessionId"
    }

    private fun formatHost(host: String): String {
        return if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    }

    private fun md5(value: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun AirPlayIdentity.clientInstanceId(): String {
        return deviceId.filter { it.isLetterOrDigit() }.take(16).uppercase(Locale.US)
    }

    private fun String.asciiHeaderValue(): String {
        return map { char -> if (char.code in 0x20..0x7E) char else '?' }
            .joinToString("")
            .trim()
            .ifBlank { "Android" }
    }

    private fun String.sdpLineValue(): String {
        return asciiHeaderValue().replace('\r', ' ').replace('\n', ' ')
    }

    private fun randomUnsignedShort(): Int {
        val bytes = CryptoPrimitives.randomBytes(2)
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    private fun randomUnsignedInt(): Int {
        val bytes = CryptoPrimitives.randomBytes(4)
        return bytes.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xFF) }
    }

    companion object {
        const val FRAMES_PER_PACKET = 352
        const val SAMPLE_RATE = 44_100
        const val DEFAULT_LATENCY_FRAMES = 88_200L
        private const val DEFAULT_TIMEOUT_MS = 5_000
        private const val KEEP_ALIVE_INTERVAL_MS = 20_000L
        private const val USER_AGENT = "AirTunes/366.0"
        private const val DIGEST_USERNAME = "pyatv"
        private val HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        private val STATUS_LINE_REGEX = Regex("RTSP/1\\.0\\s+(\\d{3})(?:\\s+(.*))?")
    }
}
