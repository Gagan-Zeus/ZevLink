package com.zevclip.sender.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayRtpAudioTest {
    @Test
    fun buildsRtpHeaderWithMarkerTimestampAndSsrc() {
        val header = AirPlayRtpPacket.header(
            sequenceNumber = 0x1234,
            timestamp = 0x55667788,
            ssrc = 0x01020304,
            marker = true
        )
        val parsed = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)

        assertEquals(0x80.toByte(), parsed.get())
        assertEquals(0xE0.toByte(), parsed.get())
        assertEquals(0x1234, parsed.short.toInt() and 0xFFFF)
        assertEquals(0x55667788, parsed.int)
        assertEquals(0x01020304, parsed.int)
    }

    @Test
    fun alacEncoderProducesCookieAndFrame() {
        val encoder = AirPlayAlacEncoder(channels = 2)
        val pcm = ByteArray(352 * 2 * 2) { index -> index.toByte() }
        val frame = encoder.encodeFrame(pcm, 352)

        assertEquals(36, encoder.magicCookie().size)
        assertTrue(frame.isNotEmpty())
        assertEquals(0x20, frame.first().toInt() and 0xE0)
    }

    @Test
    fun packetizerBuffersResidualAndPadsFinalPacket() {
        val key = ByteArray(32) { index -> (index + 3).toByte() }
        val clock = AirPlayClock()
        val packetizer = AirPlayRtpAudioPacketizer(
            audioKey = key,
            ssrc = 0x11111111,
            clock = clock,
            channels = 2,
            framesPerPacket = 4,
            initialSequenceNumber = 10
        )
        val halfPacket = ByteArray(4 * 2)
        val onePacket = ByteArray(4 * 2 * 2)

        assertEquals(0, packetizer.consumePcm(halfPacket).size)
        val emitted = packetizer.consumePcm(onePacket)
        val tail = packetizer.flushFinal()

        assertEquals(1, emitted.size)
        assertTrue(tail != null)
        assertEquals(10, ByteBuffer.wrap(emitted.first(), 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF)
        assertEquals(11, ByteBuffer.wrap(tail!!, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF)
    }

    @Test
    fun streamerSendsPacketsToSink() {
        val sent = mutableListOf<ByteArray>()
        val streamer = AirPlayPcmStreamer(
            packetizer = AirPlayRtpAudioPacketizer(
                audioKey = ByteArray(32) { it.toByte() },
                ssrc = 1,
                clock = AirPlayClock(),
                framesPerPacket = 2
            ),
            sink = object : AirPlayPacketSink {
                override fun send(packet: ByteArray) {
                    sent += packet
                }
            }
        )

        streamer.sendPcm(ByteArray(2 * 2 * 2))
        streamer.finishWithPadding(paddingFrames = 2)

        assertEquals(2, sent.size)
        assertArrayEquals(sent.first().copyOfRange(0, 2), byteArrayOf(0x80.toByte(), 0xE0.toByte()))
    }
}
