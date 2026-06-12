package com.zevclip.sender.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AirPlaySyncPacket {
    const val PACKET_SIZE = 20

    fun build(clock: AirPlayClock, firstPacket: Boolean): ByteArray {
        val rtpTime = clock.rtpTime and 0xFFFFFFFFL
        val rtpWithoutLatency = (clock.rtpTime - clock.latencyFrames) and 0xFFFFFFFFL
        val (syncSeconds, syncFraction) = AirPlayClock.ntpParts(clock.syncNtp())

        return ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(if (firstPacket) 0x90.toByte() else 0x80.toByte())
            .put(0xD4.toByte())
            .putShort(7)
            .putInt(rtpWithoutLatency.toInt())
            .putInt(syncSeconds.toInt())
            .putInt(syncFraction.toInt())
            .putInt(rtpTime.toInt())
            .array()
    }
}
