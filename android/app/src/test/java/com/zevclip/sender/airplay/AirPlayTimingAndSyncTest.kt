package com.zevclip.sender.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayTimingAndSyncTest {
    @Test
    fun timingResponseEchoesRequestSendTime() {
        val request = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
            .put(0x80.toByte())
            .put(0x53.toByte())
            .putShort(9)
            .putInt(0)
            .putInt(1)
            .putInt(2)
            .putInt(3)
            .putInt(4)
            .putInt(0x11223344)
            .putInt(0x55667788)
            .array()

        val response = ByteBuffer.wrap(
            AirPlayTimingPacket.buildResponse(
                request,
                receivedNtp = 0x0102030405060708L,
                sentNtp = 0x1112131415161718L
            )
        ).order(ByteOrder.BIG_ENDIAN)

        assertEquals(0x80.toByte(), response.get())
        assertEquals(0xD3.toByte(), response.get())
        assertEquals(7, response.short.toInt())
        assertEquals(0, response.int)
        assertEquals(0x11223344, response.int)
        assertEquals(0x55667788, response.int)
    }

    @Test
    fun syncPacketUsesClockFields() {
        val clock = AirPlayClock()
        clock.reset()
        clock.advance(352)

        val packet = AirPlaySyncPacket.build(clock, firstPacket = true)
        val parsed = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

        assertEquals(AirPlaySyncPacket.PACKET_SIZE, packet.size)
        assertEquals(0x90.toByte(), parsed.get())
        assertEquals(0xD4.toByte(), parsed.get())
        assertEquals(7, parsed.short.toInt())
    }
}
