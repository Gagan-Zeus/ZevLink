package com.zevclip.sender.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AirPlayRtpAudioPacketizer(
    private val audioKey: ByteArray,
    private val ssrc: Int,
    private val clock: AirPlayClock,
    private val channels: Int = 2,
    private val framesPerPacket: Int = 352,
    private val alacEncoder: AirPlayAlacEncoder? = null,
    initialSequenceNumber: Int = 0
) {
    private var sequenceNumber = initialSequenceNumber and 0xFFFF
    private var nonceCounter = 0L
    private var firstPacket = true
    private var residual = ByteArray(0)

    val startSequenceNumber: Int = sequenceNumber
    val startTimestamp: Int = clock.rtpTime32

    fun consumePcm(pcm16LittleEndian: ByteArray): List<ByteArray> {
        val packetBytes = framesPerPacket * channels * 2
        val merged = if (residual.isEmpty()) pcm16LittleEndian else residual + pcm16LittleEndian
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        while (merged.size - offset >= packetBytes) {
            packets += buildPacket(merged, offset, packetBytes)
            offset += packetBytes
        }
        residual = merged.copyOfRange(offset, merged.size)
        return packets
    }

    fun flushFinal(): ByteArray? {
        if (residual.isEmpty()) return null
        val packetBytes = framesPerPacket * channels * 2
        val padded = residual.copyOf(packetBytes)
        residual = ByteArray(0)
        return buildPacket(padded, 0, packetBytes)
    }

    fun silencePaddingPackets(frameCount: Int): List<ByteArray> {
        val packetBytes = framesPerPacket * channels * 2
        val silence = ByteArray(packetBytes)
        val packets = mutableListOf<ByteArray>()
        var frames = 0
        while (frames < frameCount) {
            packets += buildPacket(silence, 0, packetBytes)
            frames += framesPerPacket
        }
        return packets
    }

    private fun buildPacket(source: ByteArray, offset: Int, length: Int): ByteArray {
        val frames = length / (channels * 2)
        val payload = alacEncoder?.encodeFrame(source.copyOfRange(offset, offset + length), frames)
            ?: source.copyOfRange(offset, offset + length).littleEndianPcmToBigEndian()
        val header = AirPlayRtpPacket.header(
            sequenceNumber = sequenceNumber,
            timestamp = clock.rtpTime32,
            ssrc = ssrc,
            marker = firstPacket
        )
        val aad = header.copyOfRange(4, 12)
        val nonce8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(nonceCounter++)
            .array()
        val nonce12 = ByteArray(12).also { nonce8.copyInto(it, destinationOffset = 4) }
        val encrypted = CryptoPrimitives.chacha20Poly1305Encrypt(
            key = audioKey,
            nonce = nonce12,
            plaintext = payload,
            aad = aad
        )

        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        firstPacket = false
        clock.advance(frames)
        return header + encrypted + nonce8
    }

    private fun ByteArray.littleEndianPcmToBigEndian(): ByteArray {
        val output = copyOf()
        var index = 0
        while (index + 1 < output.size) {
            val first = output[index]
            output[index] = output[index + 1]
            output[index + 1] = first
            index += 2
        }
        return output
    }
}
