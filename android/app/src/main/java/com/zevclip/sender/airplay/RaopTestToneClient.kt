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

class RaopTestToneClient(
    private val target: AirPlayTarget,
    private val password: String,
    private val identity: AirPlayIdentity? = null,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val socketFactory: () -> Socket = { Socket() },
    private val dacpSession: AirPlayDacpSession? = null
) : Closeable {
    interface PcmPacketSource : Closeable {
        fun readPacket(buffer: ByteArray): Int
    }

    data class NowPlayingMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMillis: Long? = null,
        val positionMillis: Long? = null,
        val isPlaying: Boolean = true,
        val artworkBase64: String? = null
    ) {
        fun isEmpty(): Boolean = title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank()

        fun signature(): String {
            return listOf(
                title.orEmpty(),
                artist.orEmpty(),
                album.orEmpty(),
                durationMillis?.toString().orEmpty(),
                isPlaying.toString(),
                artworkBase64?.take(64).orEmpty()
            ).joinToString("\u0001")
        }
    }

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
    @Volatile
    private var keepAliveRunning = false
    private var keepAliveThread: Thread? = null
    private lateinit var sessionUri: String

    fun playTestTone(status: (String) -> Unit = {}): Result {
        return playPcmPackets(TestTonePacketSource(), status)
    }

    fun playPcmPackets(
        source: PcmPacketSource,
        status: (String) -> Unit = {},
        metadataProvider: (() -> NowPlayingMetadata?)? = null,
        publishMetadataToAirPlay: Boolean = true
    ): Result {
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

            val clock = AirPlayClock(
                sampleRate = AirPlayTestTone.DEFAULT_SAMPLE_RATE,
                latencyFrames = LOW_LATENCY_FRAMES
            )
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
            sendMetadataIfChanged(
                session = rtspSession,
                sequenceNumber = initialSequence,
                rtpTimestamp = clock.rtpTime32,
                metadataProvider = metadataProvider,
                lastSignature = null,
                publishMetadataToAirPlay = publishMetadataToAirPlay
            )
            startKeepAlive()

            audioSender = AirPlayUdpPacketSender(target.host, serverPort)
            status("Streaming AirPlay audio...")
            val packets = streamPcmPackets(
                source = source,
                sink = audioSender,
                clock = clock,
                initialSequence = initialSequence,
                ssrc = sessionId.toInt(),
                rtspSession = rtspSession,
                metadataProvider = metadataProvider,
                publishMetadataToAirPlay = publishMetadataToAirPlay
            )
            Result("AirPlay audio stream ended ($packets packets).", packets)
        } finally {
            runCatching { source.close() }
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
            body = "volume: 0.000000\r\n".toByteArray(Charsets.US_ASCII),
            useDigest = digestChallenge != null
        )
    }

    private fun setMetadata(
        session: String,
        sequenceNumber: Int,
        rtpTimestamp: Int,
        metadata: NowPlayingMetadata
    ) {
        if (session.isBlank() || metadata.isEmpty()) return
        request(
            method = "SET_PARAMETER",
            uri = sessionUri,
            headers = mapOf(
                "Content-Type" to "application/x-dmap-tagged",
                "Session" to session,
                "RTP-Info" to "seq=${sequenceNumber and 0xFFFF};rtptime=${rtpTimestamp.toUIntString()}"
            ),
            body = dmapContainer(
                "mlit",
                dmapString("minm", metadata.title) +
                    dmapString("asal", metadata.album) +
                    dmapString("asar", metadata.artist)
            ),
            useDigest = digestChallenge != null
        )
    }

    private fun startKeepAlive() {
        keepAliveRunning = true
        keepAliveThread = thread(name = "zevclip-raop-keepalive", isDaemon = true) {
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

    private fun streamPcmPackets(
        source: PcmPacketSource,
        sink: AirPlayPacketSink,
        clock: AirPlayClock,
        initialSequence: Int,
        ssrc: Int,
        rtspSession: String,
        metadataProvider: (() -> NowPlayingMetadata?)?,
        publishMetadataToAirPlay: Boolean
    ): Int {
        val framesPerPacket = FRAMES_PER_PACKET
        val pcm = ByteArray(framesPerPacket * 2 * 2)
        var sequenceNumber = initialSequence
        var packetCount = 0
        var lastMetadataCheckAt = 0L
        var lastMetadataSignature: String? = null

        while (true) {
            val now = System.currentTimeMillis()
            if (metadataProvider != null && now - lastMetadataCheckAt >= METADATA_REFRESH_INTERVAL_MS) {
                lastMetadataCheckAt = now
                lastMetadataSignature = sendMetadataIfChanged(
                    session = rtspSession,
                    sequenceNumber = sequenceNumber,
                    rtpTimestamp = clock.rtpTime32,
                    metadataProvider = metadataProvider,
                    lastSignature = lastMetadataSignature,
                    publishMetadataToAirPlay = publishMetadataToAirPlay
                )
            }
            val frameCount = source.readPacket(pcm)
            if (frameCount < 0) break
            if (frameCount == 0) continue
            val payload = pcm.paddedBigEndian(frameCount, framesPerPacket)
            val packet = AirPlayRtpPacket.header(
                sequenceNumber = sequenceNumber,
                timestamp = clock.rtpTime32,
                ssrc = ssrc,
                marker = packetCount == 0
            ) + payload
            sink.send(packet)
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF
            clock.advance(framesPerPacket)
            packetCount++
        }

        return packetCount
    }

    private fun sendMetadataIfChanged(
        session: String,
        sequenceNumber: Int,
        rtpTimestamp: Int,
        metadataProvider: (() -> NowPlayingMetadata?)?,
        lastSignature: String?,
        publishMetadataToAirPlay: Boolean
    ): String? {
        val metadata = metadataProvider?.invoke() ?: return lastSignature
        if (metadata.isEmpty()) return lastSignature
        val signature = metadata.signature()
        if (signature == lastSignature) return lastSignature
        if (publishMetadataToAirPlay) {
            runCatching {
                setMetadata(session, sequenceNumber, rtpTimestamp, metadata)
            }
        }
        return signature
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
        runCatching { socket?.close() }
        socket = null
    }

    private fun buildSdp(localHost: String, sessionId: Long): String {
        val localFamily = addressFamily(localHost)
        val remoteFamily = addressFamily(target.host)
        return buildString {
            append("v=0\r\n")
            append("o=iTunes $sessionId 0 IN $localFamily $localHost\r\n")
            append("s=${identity?.senderName?.sdpLineValue() ?: "iTunes"}\r\n")
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

    private inner class TestTonePacketSource : PcmPacketSource {
        private val totalFrames = AirPlayTestTone.DEFAULT_SAMPLE_RATE * AirPlayTestTone.DEFAULT_DURATION_MS / 1_000
        private var sentFrames = 0

        override fun readPacket(buffer: ByteArray): Int {
            if (sentFrames >= totalFrames) return -1
            val frameCount = minOf(FRAMES_PER_PACKET, totalFrames - sentFrames)
            val pcm = AirPlayTestTone.pcm16StereoChunk(
                sampleRate = AirPlayTestTone.DEFAULT_SAMPLE_RATE,
                startFrame = sentFrames,
                frameCount = frameCount
            )
            buffer.fill(0)
            pcm.copyInto(buffer, endIndex = pcm.size)
            sentFrames += frameCount
            val sleepMs = frameCount * 1_000L / AirPlayTestTone.DEFAULT_SAMPLE_RATE
            if (sleepMs > 0) Thread.sleep(sleepMs)
            return frameCount
        }

        override fun close() = Unit
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

    private fun dmapString(name: String, value: String?): ByteArray {
        if (value.isNullOrBlank()) return ByteArray(0)
        return dmapTag(name, value.toByteArray(Charsets.UTF_8))
    }

    private fun dmapContainer(name: String, value: ByteArray): ByteArray = dmapTag(name, value)

    private fun dmapTag(name: String, value: ByteArray): ByteArray {
        require(name.length == 4) { "DMAP tag names must be four characters." }
        return name.toByteArray(Charsets.US_ASCII) + int32(value.size) + value
    }

    private fun int32(value: Int): ByteArray {
        return byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
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

    private companion object {
        const val FRAMES_PER_PACKET = 352
        const val DEFAULT_TIMEOUT_MS = 5_000
        const val KEEP_ALIVE_INTERVAL_MS = 20_000L
        const val METADATA_REFRESH_INTERVAL_MS = 1_000L
        const val LOW_LATENCY_FRAMES = 11_025L
        const val USER_AGENT = "AirTunes/366.0"
        const val DIGEST_USERNAME = "pyatv"
        val HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val STATUS_LINE_REGEX = Regex("RTSP/1\\.0\\s+(\\d{3})(?:\\s+(.*))?")
    }
}
