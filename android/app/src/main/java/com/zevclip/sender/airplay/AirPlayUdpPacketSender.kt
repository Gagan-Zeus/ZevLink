package com.zevclip.sender.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

interface AirPlayPacketSink {
    fun send(packet: ByteArray)
}

class AirPlayUdpPacketSender(
    host: String,
    private val port: Int,
    private val socket: DatagramSocket = DatagramSocket()
) : AirPlayPacketSink {
    private val address = InetAddress.getByName(host)

    override fun send(packet: ByteArray) {
        socket.send(DatagramPacket(packet, packet.size, address, port))
    }

    fun close() {
        runCatching { socket.close() }
    }
}
