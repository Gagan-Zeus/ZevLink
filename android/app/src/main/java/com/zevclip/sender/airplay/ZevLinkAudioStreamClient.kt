package com.zevclip.sender.airplay

import android.os.Process
import android.util.Log
import java.io.Closeable
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ZevLinkAudioStreamClient(
    private val host: String,
    private val running: AtomicBoolean,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS
) : Closeable {
    private val outputLock = Any()
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var writerWorker: Thread? = null
    private val packetQueue = ArrayBlockingQueue<PendingAudioPacket>(MAX_PENDING_PACKETS)
    private var packetsDropped = 0

    fun connect() {
        val nextSocket = Socket()
        nextSocket.tcpNoDelay = true
        nextSocket.sendBufferSize = SEND_BUFFER_SIZE
        nextSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = nextSocket
        output = DataOutputStream(nextSocket.getOutputStream())
        writerWorker = thread(name = "zevlink-audio-writer", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (running.get()) {
                val packet = runCatching { packetQueue.take() }.getOrNull() ?: break
                runCatching { writePacket(packet) }
                    .onFailure { error ->
                        if (running.get()) {
                            Log.w(TAG, "ZevLink audio writer failed", error)
                            running.set(false)
                        }
                        return@thread
                    }
            }
        }
        Log.i(TAG, "Connected ZevLink audio stream to $host:$port")
    }

    fun setAudioFormat(sampleRate: Int, channels: Int) {
        if (sampleRate <= 0 || channels <= 0) return
        enqueue(PendingAudioPacket(TYPE_AUDIO_CONFIG, sampleRate, channels, 0L, ByteArray(0)))
    }

    fun writeAudioPcm(
        pcm16LittleEndian: ByteArray,
        sampleRate: Int,
        channels: Int,
        presentationTimeUs: Long
    ) {
        if (pcm16LittleEndian.isEmpty() || sampleRate <= 0 || channels <= 0) return
        enqueue(PendingAudioPacket(TYPE_AUDIO_PCM, sampleRate, channels, presentationTimeUs, pcm16LittleEndian))
    }

    private fun enqueue(packet: PendingAudioPacket) {
        while (!packetQueue.offer(packet)) {
            packetQueue.poll()
            packetsDropped++
            if (packetsDropped == 1 || packetsDropped % 30 == 0) {
                Log.i(TAG, "Dropped $packetsDropped stale ZevLink audio packet(s)")
            }
        }
    }

    private fun writePacket(packet: PendingAudioPacket) {
        val activeOutput = output ?: error("ZevLink audio stream is not connected.")
        synchronized(outputLock) {
            activeOutput.writeInt(MAGIC)
            activeOutput.writeByte(packet.type.toInt())
            activeOutput.writeByte(0)
            activeOutput.writeShort(0)
            activeOutput.writeInt(packet.sampleRate)
            activeOutput.writeInt(packet.channels)
            activeOutput.writeLong(packet.ptsUs)
            activeOutput.writeInt(packet.payload.size)
            if (packet.payload.isNotEmpty()) activeOutput.write(packet.payload)
            activeOutput.flush()
        }
    }

    override fun close() {
        writerWorker?.interrupt()
        writerWorker = null
        packetQueue.clear()
        runCatching { output?.close() }
        output = null
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val DEFAULT_PORT = 9877
        const val DEFAULT_TIMEOUT_MS = 4_000
        const val SEND_BUFFER_SIZE = 1024 * 1024
        const val MAX_PENDING_PACKETS = 192
        const val MAGIC = 0x5A56534D
        const val TYPE_AUDIO_CONFIG: Byte = 4
        const val TYPE_AUDIO_PCM: Byte = 5
        const val TAG = "ZevLinkAudioStream"
    }
}

private data class PendingAudioPacket(
    val type: Byte,
    val sampleRate: Int,
    val channels: Int,
    val ptsUs: Long,
    val payload: ByteArray
)
