package com.zevclip.sender.airplay

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class AirPlayEventChannel(
    private val host: String,
    private val port: Int,
    private val sharedSecret: ByteArray
) {
    private var socket: Socket? = null
    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start(): Boolean {
        return runCatching {
            val nextSocket = Socket()
            nextSocket.connect(InetSocketAddress(host, port), 4_000)
            socket = nextSocket
            running = true
            val channel = AirPlayEncryptedChannel(
                input = nextSocket.getInputStream(),
                output = nextSocket.getOutputStream(),
                keys = eventControllerKeys(sharedSecret)
            )
            worker = thread(name = "zevclip-airplay-events", isDaemon = true) {
                var buffered = ByteArray(0)
                while (running) {
                    runCatching {
                        buffered += channel.receiveBlock()
                        while (true) {
                            val request = buffered.nextRtspMessage() ?: break
                            buffered = buffered.copyOfRange(request.totalLength, buffered.size)
                            channel.send(eventResponse(request.cseq))
                        }
                    }.onFailure { error ->
                        if (running) Log.w(TAG, "AirPlay event channel stopped", error)
                        return@thread
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        worker?.interrupt()
        worker = null
    }

    companion object {
        private val EVENTS_SALT = "Events-Salt".toByteArray(Charsets.US_ASCII)

        fun deriveAudioSharedKey(sharedSecret: ByteArray): ByteArray {
            return CryptoPrimitives.hkdfSha512(
                inputKeyMaterial = sharedSecret,
                salt = EVENTS_SALT,
                info = "Events-Write-Encryption-Key".toByteArray(Charsets.US_ASCII),
                outputSize = 32
            )
        }

        fun eventControllerKeys(sharedSecret: ByteArray): AirPlayEncryptedChannel.Keys {
            return AirPlayEncryptedChannel.Keys(
                writeKey = CryptoPrimitives.hkdfSha512(
                    inputKeyMaterial = sharedSecret,
                    salt = EVENTS_SALT,
                    info = "Events-Read-Encryption-Key".toByteArray(Charsets.US_ASCII),
                    outputSize = 32
                ),
                readKey = CryptoPrimitives.hkdfSha512(
                    inputKeyMaterial = sharedSecret,
                    salt = EVENTS_SALT,
                    info = "Events-Write-Encryption-Key".toByteArray(Charsets.US_ASCII),
                    outputSize = 32
                )
            )
        }

        private const val TAG = "ZevClipAirPlayEvents"
    }
}

private data class EventRtspMessage(
    val totalLength: Int,
    val cseq: String?
)

private fun ByteArray.nextRtspMessage(): EventRtspMessage? {
    val headerEnd = indexOfHeaderEnd()
    if (headerEnd < 0) return null
    val headerText = copyOfRange(0, headerEnd).toString(Charsets.US_ASCII)
    val contentLength = Regex("(?i)content-length:\\s*(\\d+)")
        .find(headerText)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull() ?: 0
    val totalLength = headerEnd + 4 + contentLength
    if (size < totalLength) return null
    val cseq = Regex("(?i)cseq:\\s*(\\d+)").find(headerText)?.groupValues?.get(1)
    return EventRtspMessage(totalLength, cseq)
}

private fun ByteArray.indexOfHeaderEnd(): Int {
    for (index in 0..size - 4) {
        if (
            this[index] == '\r'.code.toByte() &&
            this[index + 1] == '\n'.code.toByte() &&
            this[index + 2] == '\r'.code.toByte() &&
            this[index + 3] == '\n'.code.toByte()
        ) {
            return index
        }
    }
    return -1
}

private fun eventResponse(cseq: String?): ByteArray {
    return ByteArrayOutputStream().apply {
        write("RTSP/1.0 200 OK\r\n".toByteArray(Charsets.US_ASCII))
        write("Content-Length: 0\r\n".toByteArray(Charsets.US_ASCII))
        write("Audio-Latency: 0\r\n".toByteArray(Charsets.US_ASCII))
        write("Server: AirTunes/950.7.1\r\n".toByteArray(Charsets.US_ASCII))
        if (cseq != null) write("CSeq: $cseq\r\n".toByteArray(Charsets.US_ASCII))
        write("\r\n".toByteArray(Charsets.US_ASCII))
    }.toByteArray()
}
