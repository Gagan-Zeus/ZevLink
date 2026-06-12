package com.zevclip.sender.airplay

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AirPlayMirrorStreamClient(
    private val target: AirPlayTarget,
    private val identity: AirPlayIdentity,
    width: Int,
    height: Int,
    private val running: AtomicBoolean,
    private val streamPort: Int,
    private val dataStreamKey: ByteArray,
    private val legacyHttpStream: Boolean = false,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val socketFactory: () -> Socket = { Socket() },
    private val onFatalError: (Throwable) -> Unit = {}
) : AirPlayScreenSampleSink, Closeable {
    private val outputLock = Any()
    private val sessionId = CryptoPrimitives.randomBytes(4).toUIntLe()
    @Volatile
    private var contentWidth = width
    @Volatile
    private var contentHeight = height
    private var socket: Socket? = null
    private var codecConfig: ByteArray? = null
    private var codecConfigSent = false
    private var heartbeatWorker: Thread? = null
    private var videoWriterWorker: Thread? = null
    private val videoQueue = ArrayBlockingQueue<PendingVideoFrame>(MAX_PENDING_VIDEO_FRAMES)
    private var videoPacketsSent = 0
    private var videoPacketsDropped = 0
    private var codecPacketsSent = 0
    private var videoNonce = 0L

    fun connect() {
        val nextSocket = socketFactory()
        nextSocket.connect(InetSocketAddress(target.host, streamPort), connectTimeoutMs)
        nextSocket.soTimeout = readTimeoutMs
        nextSocket.tcpNoDelay = true
        nextSocket.sendBufferSize = STREAM_SEND_BUFFER_SIZE
        socket = nextSocket

        if (legacyHttpStream) {
            val body = BPlist.encode(streamSetupPlist())
            val request = buildString {
                append("POST /stream HTTP/1.1\r\n")
                append("Host: ")
                append(target.host)
                append("\r\n")
                append("User-Agent: AirPlay/690.7.1 ZevLink\r\n")
                append("X-Apple-Device-ID: ")
                append(identity.deviceId)
                append("\r\n")
                append("Content-Type: application/x-apple-binary-plist\r\n")
                append("Content-Length: ")
                append(body.size)
                append("\r\n")
                append("Connection: keep-alive\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)

            val output = nextSocket.getOutputStream()
            output.write(request)
            output.write(body)
            output.flush()
            readOptionalHttpResponse(nextSocket.getInputStream())
        }

        heartbeatWorker = thread(name = "zevclip-airplay-mirror-heartbeat", isDaemon = true) {
            while (running.get()) {
                runCatching {
                    sendPacket(
                        payloadType = TYPE_HEARTBEAT,
                        payloadSubtype = 0,
                        optionLow = OPTION_HEARTBEAT,
                        optionHigh = 0,
                        payload = ByteArray(0),
                        timestamp = 0L
                    )
                }
                    .onFailure { error ->
                        if (running.get()) Log.w(TAG, "AirPlay mirror heartbeat failed", error)
                    }
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        videoWriterWorker = thread(name = "zevclip-airplay-mirror-video-writer", isDaemon = true) {
            while (running.get()) {
                runCatching {
                    val frame = videoQueue.take()
                    sendVideoFrame(frame)
                }.onFailure { error ->
                    if (running.get()) {
                        Log.w(TAG, "AirPlay mirror video writer failed", error)
                        onFatalError(error)
                        running.set(false)
                    }
                    return@thread
                }
            }
        }
        Log.i(TAG, "Connected AirPlay mirror stream to ${target.host}:$streamPort")
    }

    fun updateVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (contentWidth == width && contentHeight == height) return
        videoQueue.clear()
        contentWidth = width
        contentHeight = height
        codecConfig = null
        codecConfigSent = false
        Log.i(TAG, "Updated AirPlay mirror video size to ${width}x$height")
    }

    override fun setCodecConfig(config: ByteArray) {
        val avcc = config.toAvcDecoderConfigurationRecord()
        if (avcc.isEmpty()) {
            Log.w(TAG, "Ignoring empty AirPlay mirror codec config")
            return
        }
        if (codecConfigSent && codecConfig?.contentEquals(avcc) == true) {
            Log.i(TAG, "Ignoring duplicate AirPlay mirror codec config (${avcc.size} bytes)")
            return
        }
        codecConfig = avcc
        codecConfigSent = false
        sendCodecConfigIfReady(force = true)
    }

    override fun writeSample(sample: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        if (keyFrame) sendCodecConfigIfReady(force = false)
        val avccSample = sample.toAvccSample()
        if (avccSample.isEmpty()) return
        enqueueVideoFrame(PendingVideoFrame(avccSample, presentationTimeUs, keyFrame))
    }

    private fun enqueueVideoFrame(frame: PendingVideoFrame) {
        if (frame.keyFrame) {
            videoQueue.clear()
        }
        while (!videoQueue.offer(frame)) {
            videoQueue.poll()
            videoPacketsDropped++
            if (videoPacketsDropped == 1 || videoPacketsDropped % 30 == 0) {
                Log.i(TAG, "Dropped $videoPacketsDropped stale AirPlay mirror video packet(s)")
            }
        }
    }

    private fun sendVideoFrame(frame: PendingVideoFrame) {
        try {
            sendPacket(
                payloadType = TYPE_VIDEO,
                payloadSubtype = if (frame.keyFrame) VIDEO_SUBTYPE_KEYFRAME else 0,
                optionLow = 0,
                optionHigh = 0,
                payload = frame.payload,
                timestamp = frame.presentationTimeUs.toNtpTimestamp(),
                encryptVideoPayload = true
            )
        } catch (error: IOException) {
            throw IOException(
                "Mac rejected the AirPlay mirror video stream after $videoPacketsSent video packet(s): ${error.message}. " +
                    "This usually means the receiver rejected the encrypted AirPlay mirror payload.",
                error
            )
        }
    }

    private fun streamSetupPlist(): BPlist.Value {
        return BPlist.dict(
            "deviceID" to BPlist.int(identity.deviceId.deviceIdLong()),
            "sessionID" to BPlist.int(sessionId),
            "version" to BPlist.string("130.16"),
            "latencyMs" to BPlist.int(90),
            "fpsInfo" to namedInfoArray("SubS", "B4En", "EnDp", "IdEn", "IdDp", "EQDp", "QueF", "Sent"),
            "timestampInfo" to namedInfoArray("SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc", "QueFr", "SndFr")
        )
    }

    private fun namedInfoArray(vararg names: String): BPlist.Value.ArrayValue {
        return BPlist.Value.ArrayValue(names.map { name ->
            BPlist.dict("name" to BPlist.string(name))
        })
    }

    private fun sendCodecConfigIfReady(force: Boolean) {
        val config = codecConfig ?: return
        if (codecConfigSent && !force) return
        sendPacket(
            payloadType = TYPE_CODEC,
            payloadSubtype = 0,
            optionLow = CODEC_OPTION_LOW,
            optionHigh = CODEC_OPTION_HIGH,
            payload = config,
            timestamp = 0L
        ) { header ->
            val currentWidth = contentWidth.toFloat()
            val currentHeight = contentHeight.toFloat()
            header.putFloat(16, currentWidth)
            header.putFloat(20, currentHeight)
            header.putFloat(40, currentWidth)
            header.putFloat(44, currentHeight)
            header.putFloat(56, currentWidth)
            header.putFloat(60, currentHeight)
        }
        codecPacketsSent++
        Log.i(TAG, "Sent AirPlay mirror codec config packet #$codecPacketsSent (${config.size} bytes)")
        codecConfigSent = true
    }

    private fun sendPacket(
        payloadType: Byte,
        payloadSubtype: Byte,
        optionLow: Byte,
        optionHigh: Byte,
        payload: ByteArray,
        timestamp: Long,
        encryptVideoPayload: Boolean = false,
        decorateHeader: (ByteBuffer) -> Unit = {}
    ) {
        val activeSocket = socket ?: error("AirPlay mirror stream is not connected.")
        val payloadSize = payload.size + if (encryptVideoPayload) POLY1305_TAG_SIZE else 0
        val header = ByteBuffer.allocate(PACKET_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(payloadSize)
        header.put(payloadType)
        header.put(payloadSubtype)
        header.put(optionLow)
        header.put(optionHigh)
        header.putLong(timestamp)
        decorateHeader(header)
        val headerBytes = header.array()
        val wirePayload = if (encryptVideoPayload) {
            CryptoPrimitives.chacha20Poly1305Encrypt(
                key = dataStreamKey,
                nonce = videoNonceNonce(videoNonce++),
                plaintext = payload,
                aad = headerBytes
            )
        } else {
            payload
        }
        synchronized(outputLock) {
            val output = activeSocket.getOutputStream()
            output.write(headerBytes)
            if (wirePayload.isNotEmpty()) output.write(wirePayload)
            output.flush()
        }
        if (payloadType == TYPE_VIDEO) {
            videoPacketsSent++
            if (videoPacketsSent == 1 || videoPacketsSent % 30 == 0) {
                Log.i(TAG, "Sent AirPlay mirror video packet #$videoPacketsSent (${wirePayload.size} encrypted bytes)")
            }
        }
    }

    private fun readOptionalHttpResponse(input: InputStream) {
        runCatching {
            val headerBytes = ByteArrayOutputStream()
            var state = 0
            while (state < RESPONSE_HEADER_END.size) {
                val nextByte = input.read()
                if (nextByte < 0) return
                headerBytes.write(nextByte)
                state = if (nextByte.toByte() == RESPONSE_HEADER_END[state]) {
                    state + 1
                } else if (nextByte.toByte() == RESPONSE_HEADER_END[0]) {
                    1
                } else {
                    0
                }
            }
            val text = headerBytes.toString(Charsets.US_ASCII.name()).trim()
            if (text.isNotEmpty()) Log.i(TAG, "AirPlay mirror stream response: ${text.lines().firstOrNull().orEmpty()}")
        }.onFailure {
            Log.i(TAG, "AirPlay mirror stream did not return an HTTP response before video packets")
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        videoWriterWorker?.interrupt()
        videoWriterWorker = null
        heartbeatWorker?.interrupt()
        heartbeatWorker = null
        videoQueue.clear()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 4_000
        const val PACKET_HEADER_SIZE = 128
        const val STREAM_SEND_BUFFER_SIZE = 2 * 1024 * 1024
        const val MAX_PENDING_VIDEO_FRAMES = 5
        const val TYPE_VIDEO: Byte = 0
        const val TYPE_CODEC: Byte = 1
        const val TYPE_HEARTBEAT: Byte = 2
        const val VIDEO_SUBTYPE_KEYFRAME: Byte = 0x10
        const val CODEC_OPTION_LOW: Byte = 0x16
        const val CODEC_OPTION_HIGH: Byte = 0x01
        const val OPTION_HEARTBEAT: Byte = 0x1e
        const val POLY1305_TAG_SIZE = 16
        const val TAG = "ZevClipAirPlayMirror"
        val RESPONSE_HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    }
}

private data class PendingVideoFrame(
    val payload: ByteArray,
    val presentationTimeUs: Long,
    val keyFrame: Boolean
)

private fun videoNonceNonce(counter: Long): ByteArray {
    require(counter >= 0) { "AirPlay mirror video nonce counter must be non-negative." }
    return ByteArray(12).also { nonce ->
        for (index in 0 until 8) {
            nonce[4 + index] = ((counter ushr (index * 8)) and 0xFF).toByte()
        }
    }
}

private fun ByteArray.toUIntLe(): Long {
    var value = 0L
    take(4).forEachIndexed { index, byte ->
        value = value or ((byte.toLong() and 0xFFL) shl (index * 8))
    }
    return value
}

private fun String.deviceIdLong(): Long {
    return filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        .takeLast(16)
        .ifBlank { "0" }
        .toULong(16)
        .toLong()
}

private fun Long.toNtpTimestamp(): Long {
    val seconds = this / 1_000_000L
    val fractionalUs = this % 1_000_000L
    val fraction = (fractionalUs * 0x1_0000_0000L) / 1_000_000L
    return (seconds shl 32) or (fraction and 0xFFFF_FFFFL)
}

private fun ByteArray.toAvcDecoderConfigurationRecord(): ByteArray {
    if (isNotEmpty() && this[0].toInt() == 1) return this
    val nals = splitAnnexBNals()
    val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 } ?: return ByteArray(0)
    val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 } ?: return ByteArray(0)
    if (sps.size < 4) return ByteArray(0)
    return ByteArrayOutputStream().apply {
        write(1)
        write(sps[1].toInt() and 0xFF)
        write(sps[2].toInt() and 0xFF)
        write(sps[3].toInt() and 0xFF)
        write(0xFF)
        write(0xE1)
        writeShortBe(sps.size)
        write(sps)
        write(1)
        writeShortBe(pps.size)
        write(pps)
    }.toByteArray()
}

private fun ByteArray.toAvccSample(): ByteArray {
    if (isEmpty()) return this
    if (!startsWithAnnexBStartCode()) return this
    val nals = splitAnnexBNals()
        .filterNot { nal ->
            val type = nal.firstOrNull()?.toInt()?.and(0x1F) ?: return@filterNot true
            type == 6 || type == 7 || type == 8 || type == 9
        }
    return ByteArrayOutputStream().apply {
        nals.forEach { nal ->
            writeIntBe(nal.size)
            write(nal)
        }
    }.toByteArray()
}

private fun ByteArray.splitAnnexBNals(): List<ByteArray> {
    val output = mutableListOf<ByteArray>()
    var start = findStartCode(0)
    while (start >= 0) {
        val nalStart = start + startCodeLength(start)
        val nextStart = findStartCode(nalStart)
        val nalEnd = if (nextStart >= 0) nextStart else size
        if (nalEnd > nalStart) output += copyOfRange(nalStart, nalEnd)
        start = nextStart
    }
    return output
}

private fun ByteArray.findStartCode(fromIndex: Int): Int {
    var index = fromIndex.coerceAtLeast(0)
    while (index + 3 < size) {
        if (this[index] == 0.toByte() && this[index + 1] == 0.toByte()) {
            if (this[index + 2] == 1.toByte()) return index
            if (index + 4 < size && this[index + 2] == 0.toByte() && this[index + 3] == 1.toByte()) return index
        }
        index++
    }
    return -1
}

private fun ByteArray.startCodeLength(index: Int): Int {
    return if (index + 3 < size && this[index + 2] == 1.toByte()) 3 else 4
}

private fun ByteArray.startsWithAnnexBStartCode(): Boolean {
    return size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        (this[2] == 1.toByte() || (this[2] == 0.toByte() && this[3] == 1.toByte()))
}

private fun ByteArrayOutputStream.writeShortBe(value: Int) {
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

private fun ByteArrayOutputStream.writeIntBe(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}
