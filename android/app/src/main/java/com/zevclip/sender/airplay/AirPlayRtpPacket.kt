package com.zevclip.sender.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AirPlayRtpPacket {
    const val HEADER_SIZE = 12
    const val PAYLOAD_TYPE = 96

    fun header(
        sequenceNumber: Int,
        timestamp: Int,
        ssrc: Int,
        marker: Boolean
    ): ByteArray {
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(0x80.toByte())
            .put(((if (marker) 0x80 else 0x00) or PAYLOAD_TYPE).toByte())
            .putShort((sequenceNumber and 0xFFFF).toShort())
            .putInt(timestamp)
            .putInt(ssrc)
            .array()
    }
}
