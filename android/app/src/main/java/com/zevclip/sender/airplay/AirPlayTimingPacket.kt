package com.zevclip.sender.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AirPlayTimingPacket {
    const val PACKET_SIZE = 32

    fun buildResponse(request: ByteArray, receivedNtp: Long = AirPlayClock.ntpNow(), sentNtp: Long = AirPlayClock.ntpNow()): ByteArray {
        require(request.size >= PACKET_SIZE) { "AirPlay timing request must be at least 32 bytes." }
        val input = ByteBuffer.wrap(request, 0, PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        val protocol = input.get()
        input.get()
        input.short
        input.int
        input.int
        input.int
        input.int
        input.int
        val requestSendSeconds = input.int
        val requestSendFraction = input.int
        val (receivedSeconds, receivedFraction) = AirPlayClock.ntpParts(receivedNtp)
        val (sentSeconds, sentFraction) = AirPlayClock.ntpParts(sentNtp)

        return ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(protocol)
            .put(0xD3.toByte())
            .putShort(7)
            .putInt(0)
            .putInt(requestSendSeconds)
            .putInt(requestSendFraction)
            .putInt(receivedSeconds.toInt())
            .putInt(receivedFraction.toInt())
            .putInt(sentSeconds.toInt())
            .putInt(sentFraction.toInt())
            .array()
    }
}
