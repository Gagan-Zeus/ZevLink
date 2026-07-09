package com.zevclip.sender.airplay

import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ZevLinkMirrorStreamClient(
    private val host: String,
    width: Int,
    height: Int,
    private val running: AtomicBoolean,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val onControlCommand: (String) -> Unit = {}
) : AirPlayScreenSampleSink, Closeable {
    private val outputLock = Any()
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    @Volatile
    private var contentWidth = width
    @Volatile
    private var contentHeight = height
    private var codecConfig: ByteArray? = null
    private var writerWorker: Thread? = null
    private var readerWorker: Thread? = null
    private val packetQueue = ArrayBlockingQueue<PendingMirrorPacket>(MAX_PENDING_PACKETS)
    private var packetsDropped = 0

    fun connect() {
        val nextSocket = Socket()
        nextSocket.tcpNoDelay = true
        nextSocket.sendBufferSize = SEND_BUFFER_SIZE
        nextSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = nextSocket
        input = DataInputStream(nextSocket.getInputStream())
        output = DataOutputStream(nextSocket.getOutputStream())
        writerWorker = thread(name = "zevlink-screen-window-writer", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            while (running.get()) {
                val packet = runCatching { packetQueue.take() }.getOrNull() ?: break
                runCatching { writePacket(packet) }
                    .onFailure { error ->
                        if (running.get()) {
                            Log.w(TAG, "ZevLink screen mirror writer failed", error)
                            running.set(false)
                        }
                        return@thread
                }
            }
        }
        readerWorker = thread(name = "zevlink-screen-window-control", isDaemon = true) {
            readControlPackets()
        }
        sendSize()
        Log.i(TAG, "Connected ZevLink screen mirror stream to $host:$port")
    }

    fun updateVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (contentWidth == width && contentHeight == height) return
        packetQueue.clear()
        contentWidth = width
        contentHeight = height
        codecConfig?.let { enqueue(PendingMirrorPacket(TYPE_CONFIG, FLAG_KEY_FRAME, width, height, 0L, it)) }
        sendSize()
        Log.i(TAG, "Updated ZevLink screen mirror size to ${width}x$height")
    }

    fun setAudioFormat(sampleRate: Int, channels: Int) {
        if (sampleRate <= 0 || channels <= 0) return
        enqueue(PendingMirrorPacket(TYPE_AUDIO_CONFIG, 0, sampleRate, channels, 0L, ByteArray(0)))
    }

    fun writeAudioPcm(pcm16LittleEndian: ByteArray, sampleRate: Int, channels: Int, presentationTimeUs: Long) {
        if (pcm16LittleEndian.isEmpty() || sampleRate <= 0 || channels <= 0) return
        enqueue(PendingMirrorPacket(TYPE_AUDIO_PCM, 0, sampleRate, channels, presentationTimeUs, pcm16LittleEndian))
    }

    override fun setCodecConfig(config: ByteArray) {
        val avcc = config.toAvcDecoderConfigurationRecord()
        if (avcc.isEmpty()) {
            Log.w(TAG, "Ignoring empty ZevLink screen mirror codec config")
            return
        }
        codecConfig = avcc
        enqueue(PendingMirrorPacket(TYPE_CONFIG, FLAG_KEY_FRAME, contentWidth, contentHeight, 0L, avcc))
    }

    override fun writeSample(sample: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        val avccSample = sample.toAvccSample()
        if (avccSample.isEmpty()) return
        if (keyFrame) {
            packetQueue.clear()
            codecConfig?.let {
                enqueue(PendingMirrorPacket(TYPE_CONFIG, FLAG_KEY_FRAME, contentWidth, contentHeight, 0L, it))
            }
        }
        enqueue(
            PendingMirrorPacket(
                type = TYPE_FRAME,
                flags = if (keyFrame) FLAG_KEY_FRAME else 0,
                width = contentWidth,
                height = contentHeight,
                ptsUs = presentationTimeUs,
                payload = avccSample
            )
        )
    }

    private fun sendSize() {
        enqueue(PendingMirrorPacket(TYPE_SIZE, 0, contentWidth, contentHeight, 0L, ByteArray(0)))
    }

    private fun enqueue(packet: PendingMirrorPacket) {
        while (!packetQueue.offer(packet)) {
            packetQueue.poll()
            packetsDropped++
            if (packetsDropped == 1 || packetsDropped % 30 == 0) {
                Log.i(TAG, "Dropped $packetsDropped stale ZevLink mirror packet(s)")
            }
        }
    }

    private fun writePacket(packet: PendingMirrorPacket) {
        val activeOutput = output ?: error("ZevLink screen mirror stream is not connected.")
        synchronized(outputLock) {
            activeOutput.writeInt(MAGIC)
            activeOutput.writeByte(packet.type.toInt())
            activeOutput.writeByte(packet.flags.toInt())
            activeOutput.writeShort(0)
            activeOutput.writeInt(packet.width)
            activeOutput.writeInt(packet.height)
            activeOutput.writeLong(packet.ptsUs)
            activeOutput.writeInt(packet.payload.size)
            if (packet.payload.isNotEmpty()) activeOutput.write(packet.payload)
            activeOutput.flush()
        }
    }

    override fun close() {
        readerWorker?.interrupt()
        readerWorker = null
        writerWorker?.interrupt()
        writerWorker = null
        packetQueue.clear()
        runCatching { input?.close() }
        input = null
        runCatching { output?.close() }
        output = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun readControlPackets() {
        val activeInput = input ?: return
        while (running.get()) {
            try {
                val magic = activeInput.readInt()
                if (magic != MAGIC) {
                    Log.w(TAG, "ZevLink screen control stream had invalid magic")
                    running.set(false)
                    return
                }
                val type = activeInput.readUnsignedByte()
                activeInput.readUnsignedByte()
                activeInput.readUnsignedShort()
                activeInput.readInt()
                activeInput.readInt()
                activeInput.readLong()
                val payloadLength = activeInput.readInt()
                if (payloadLength < 0 || payloadLength > MAX_CONTROL_PAYLOAD_BYTES) {
                    Log.w(TAG, "ZevLink screen control payload is invalid: $payloadLength")
                    running.set(false)
                    return
                }
                val payload = ByteArray(payloadLength)
                activeInput.readFully(payload)
                if (type.toByte() == TYPE_CONTROL && payload.isNotEmpty()) {
                    onControlCommand(String(payload, Charsets.UTF_8))
                }
            } catch (_: EOFException) {
                return
            } catch (_: InterruptedException) {
                return
            } catch (error: Exception) {
                if (running.get()) {
                    Log.w(TAG, "ZevLink screen control reader failed", error)
                    running.set(false)
                }
                return
            }
        }
    }

    private companion object {
        const val DEFAULT_PORT = 9877
        const val DEFAULT_TIMEOUT_MS = 4_000
        const val SEND_BUFFER_SIZE = 8 * 1024 * 1024
        const val MAX_PENDING_PACKETS = 96
        const val MAGIC = 0x5A56534D
        const val TYPE_CONFIG: Byte = 1
        const val TYPE_FRAME: Byte = 2
        const val TYPE_SIZE: Byte = 3
        const val TYPE_AUDIO_CONFIG: Byte = 4
        const val TYPE_AUDIO_PCM: Byte = 5
        const val TYPE_CONTROL: Byte = 6
        const val FLAG_KEY_FRAME: Byte = 1
        const val MAX_CONTROL_PAYLOAD_BYTES = 16 * 1024
        const val TAG = "ZevLinkMirrorStream"
    }
}

private data class PendingMirrorPacket(
    val type: Byte,
    val flags: Byte,
    val width: Int,
    val height: Int,
    val ptsUs: Long,
    val payload: ByteArray
)

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
