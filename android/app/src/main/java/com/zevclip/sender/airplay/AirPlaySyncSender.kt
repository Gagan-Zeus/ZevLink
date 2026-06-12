package com.zevclip.sender.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class AirPlaySyncSender(
    private val clock: AirPlayClock,
    private val host: String,
    private val port: Int,
    private val intervalMs: Long = 1_000L,
    private val socket: DatagramSocket = DatagramSocket()
) {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    val localPort: Int
        get() = socket.localPort

    fun start() {
        if (running) return
        running = true
        val target = InetAddress.getByName(host)
        worker = thread(name = "zevclip-airplay-sync", isDaemon = true) {
            var first = true
            while (running) {
                runCatching {
                    val packet = AirPlaySyncPacket.build(clock, first)
                    socket.send(DatagramPacket(packet, packet.size, target, port))
                    first = false
                    Thread.sleep(intervalMs)
                }.onFailure { error ->
                    if (error is InterruptedException) return@thread
                }
            }
        }
    }

    fun stop(closeSocket: Boolean = true) {
        running = false
        worker?.interrupt()
        worker = null
        if (closeSocket) runCatching { socket.close() }
    }
}
