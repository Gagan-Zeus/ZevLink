package com.zevclip.sender.airplay

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil

class RaopTestToneClient(
    private val target: AirPlayTarget,
    private val password: String,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val socketFactory: () -> Socket = { Socket() }
) : Closeable {
    data class Result(
        val message: String,
        val packets: Int
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
    private var cseq = 1
    private var digestChallenge: DigestChallenge? = null
    private lateinit var sessionUri: String

    fun playTestTone(status: (String) -> Unit = {}): Result {
        require(password.isNotBlank()) { "AirPlay password is empty." }

        val timingServer = AirPlayTimingServer()
        var controlSocket: DatagramSocket? = null
        var audioSender: AirPlayUdpPacketSender? = null
        var syncSender: AirPlaySyncSender? = null

        return try {
            connect()
            val localHost = requireNotNull(socket?.localAddress?.hostAddress) {
                "RAOP local address is not available."
            }
            val sessionId = randomUnsignedInt().toLong() and 0xFFFFFFFFL
            sessionUri = rtspUrl(localHost, sessionId)

            status("Starting AirTunes password session...")
            announce(localHost, sessionId)

            controlSocket = DatagramSocket(0, InetAddress.getByName(localHost))
            val timingPort = timingServer.start(0)
            status("Setting up RAOP audio ports...")
            val setup = setup(
                controlPort = controlSocket.localPort,
                timingPort = timingPort
            )

            val remotePorts = parseTransport(setup.header("transport").orEmpty())
            val serverPort = remotePorts["server_port"]?.toIntOrNull()
                ?: error("RAOP SETUP did not return an audio server port.")
            val remoteControlPort = remotePorts["control_port"]?.toIntOrNull() ?: 0
            val rtspSession = setup.header("session")?.substringBefore(';')?.trim().orEmpty()

            val clock = AirPlayClock(AirPlayTestTone.DEFAULT_SAMPLE_RATE)
            clock.reset()
            if (remoteControlPort > 0) {
                syncSender = AirPlaySyncSender(
                    clock = clock,
                    host = target.host,
                    port = remoteControlPort,
                    socket = controlSocket
                ).also { it.start() }
            }

            val initialSequence = randomUnsignedShort()
            status("Starting RAOP playback...")
            record()
            flush(rtspSession, initialSequence, clock.rtpTime32)
            setVolume()

            audioSender = AirPlayUdpPacketSender(target.host, serverPort)
            status("Playing AirPlay test tone...")
            val packets = streamTone(
                sink = audioSender,
                clock = clock,
                initialSequence = initialSequence,
                ssrc = sessionId.toInt()
            )
            Result("AirPlay test tone sent through RAOP ($packets packets).", packets)
        } finally {
            runCatching { syncSender?.stop() }
            runCatching { audioSender?.close() }
            runCatching { controlSocket?.close() }
            runCatching { timingServer.stop() }
            close()
        }
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
                error("Mac rejected AirPlay password: ${retry.statusCode} ${retry.reasonPhrase}.")
            }
            return
        }

        if (!first.isSuccessful()) {
            error("RAOP ANNOUNCE failed: ${first.statusCode} ${first.reasonPhrase}.")
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
            error("RAOP SETUP failed: ${response.statusCode} ${response.reasonPhrase}.")
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
            error("RAOP RECORD failed: ${response.statusCode} ${response.reasonPhrase}.")
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
            body = "volume: -18.000000\r\n".toByteArray(Charsets.US_ASCII),
            useDigest = digestChallenge != null
        )
    }

    private fun streamTone(
        sink: AirPlayPacketSink,
        clock: AirPlayClock,
        initialSequence: Int,
        ssrc: Int,
        durationMs: Int = AirPlayTestTone.DEFAULT_DURATION_MS
    ): Int {
        val sampleRate = AirPlayTestTone.DEFAULT_SAMPLE_RATE
        val framesPerPacket = FRAMES_PER_PACKET
        val totalFrames = sampleRate * durationMs / 1_000
        val packetCount = ceil(totalFrames.toDouble() / framesPerPacket.toDouble()).toInt()
        var sequenceNumber = initialSequence

        for (packetIndex in 0 until packetCount) {
            val startFrame = packetIndex * framesPerPacket
            val frameCount = minOf(framesPerPacket, totalFrames - startFrame)
            val pcm = AirPlayTestTone.pcm16StereoChunk(
                sampleRate = sampleRate,
                startFrame = startFrame,
                frameCount = frameCount
            ).paddedBigEndian(frameCount, framesPerPacket)
            val packet = AirPlayRtpPacket.header(
                sequenceNumber = sequenceNumber,
                timestamp = clock.rtpTime32,
                ssrc = ssrc,
                marker = packetIndex == 0
            ) + pcm
            sink.send(packet)
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF
            clock.advance(framesPerPacket)

            val sleepMs = framesPerPacket * 1_000L / sampleRate
            if (sleepMs > 0) Thread.sleep(sleepMs)
        }

        return packetCount
    }

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
        runCatching { socket?.close() }
        socket = null
    }

    private fun buildSdp(localHost: String, sessionId: Long): String {
        val localFamily = addressFamily(localHost)
        val remoteFamily = addressFamily(target.host)
        return buildString {
            append("v=0\r\n")
            append("o=iTunes $sessionId 0 IN $localFamily $localHost\r\n")
            append("s=iTunes\r\n")
            append("c=IN $remoteFamily ${target.host}\r\n")
            append("t=0 0\r\n")
            append("m=audio 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 L16/44100/2\r\n")
            append("a=fmtp:96 $FRAMES_PER_PACKET 0 16 40 10 14 2 255 0 0 ${AirPlayTestTone.DEFAULT_SAMPLE_RATE}\r\n")
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

    private fun randomUnsignedShort(): Int {
        val bytes = CryptoPrimitives.randomBytes(2)
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    private fun randomUnsignedInt(): Int {
        val bytes = CryptoPrimitives.randomBytes(4)
        return bytes.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xFF) }
    }

    private companion object {
        const val FRAMES_PER_PACKET = 352
        const val DEFAULT_TIMEOUT_MS = 5_000
        const val USER_AGENT = "AirTunes/366.0"
        const val DIGEST_USERNAME = "pyatv"
        val HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val STATUS_LINE_REGEX = Regex("RTSP/1\\.0\\s+(\\d{3})(?:\\s+(.*))?")
    }
}
