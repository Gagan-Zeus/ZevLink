package com.zevclip.sender.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread

class AirPlayTimingServer {
    private var socket: DatagramSocket? = null
    @Volatile
    private var running = false
    private var worker: Thread? = null

    val port: Int
        get() = socket?.localPort ?: 0

    fun start(bindPort: Int = 0): Int {
        stop()
        val nextSocket = DatagramSocket(bindPort)
        socket = nextSocket
        running = true
        worker = thread(name = "zevclip-airplay-timing", isDaemon = true) {
            val buffer = ByteArray(64)
            while (running) {
                runCatching {
                    val request = DatagramPacket(buffer, buffer.size)
                    nextSocket.receive(request)
                    if (request.length >= AirPlayTimingPacket.PACKET_SIZE) {
                        val response = AirPlayTimingPacket.buildResponse(request.data.copyOfRange(0, request.length))
                        nextSocket.send(DatagramPacket(response, response.size, request.address, request.port))
                    }
                }
            }
        }
        return nextSocket.localPort
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        worker?.interrupt()
        worker = null
    }
}
