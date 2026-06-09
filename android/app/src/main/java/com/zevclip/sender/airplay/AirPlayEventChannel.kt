package com.zevclip.sender.airplay

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
                while (running) {
                    runCatching { channel.receiveRtspResponse() }
                        .onFailure { return@thread }
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
                writeKey = deriveAudioSharedKey(sharedSecret),
                readKey = CryptoPrimitives.hkdfSha512(
                    inputKeyMaterial = sharedSecret,
                    salt = EVENTS_SALT,
                    info = "Events-Read-Encryption-Key".toByteArray(Charsets.US_ASCII),
                    outputSize = 32
                )
            )
        }
    }
}
