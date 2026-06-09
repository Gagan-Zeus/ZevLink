package com.zevclip.sender.airplay

import java.io.ByteArrayOutputStream

class AirPlayAlacEncoder(
    private val sampleRate: Int = 44_100,
    private val channels: Int = 2,
    private val sampleSize: Int = 16,
    private val framesPerPacket: Int = 352
) {
    init {
        require(channels in 1..2) { "ALAC helper supports mono or stereo." }
        require(sampleSize == 16) { "ALAC helper currently supports 16-bit PCM." }
    }

    fun magicCookie(): ByteArray {
        val output = ByteArrayOutputStream(36)
        fun be32(value: Int) {
            output.write((value ushr 24) and 0xFF)
            output.write((value ushr 16) and 0xFF)
            output.write((value ushr 8) and 0xFF)
            output.write(value and 0xFF)
        }
        fun be16(value: Int) {
            output.write((value ushr 8) and 0xFF)
            output.write(value and 0xFF)
        }
        be32(36)
        output.write("alac".toByteArray(Charsets.US_ASCII))
        be32(0)
        be32(framesPerPacket)
        output.write(0)
        output.write(sampleSize)
        output.write(40)
        output.write(10)
        output.write(14)
        output.write(channels)
        be16(255)
        be32(0)
        be32(0)
        be32(sampleRate)
        return output.toByteArray()
    }

    fun encodeFrame(pcm16LittleEndian: ByteArray, frames: Int): ByteArray {
        require(frames >= 0) { "ALAC frame count must be non-negative." }
        require(pcm16LittleEndian.size >= frames * channels * 2) { "PCM data is shorter than the ALAC frame count." }
        val writer = BitWriter()
        writer.write(if (channels == 2) TYPE_CHANNEL_PAIR else TYPE_SINGLE_CHANNEL, 3)
        writer.write(0, 4)
        writer.write(0, 12)
        writer.write(1, 1)
        writer.write(0, 2)
        writer.write(1, 1)
        writer.write(frames, 32)
        var offset = 0
        repeat(frames * channels) {
            val low = pcm16LittleEndian[offset].toInt() and 0xFF
            val high = pcm16LittleEndian[offset + 1].toInt()
            writer.write((high shl 8) or low, sampleSize)
            offset += 2
        }
        writer.write(TYPE_END, 3)
        return writer.finish()
    }

    private class BitWriter {
        private val output = ByteArrayOutputStream()
        private var accumulator = 0L
        private var bitCount = 0

        fun write(value: Int, bits: Int) {
            val mask = if (bits == 64) -1L else (1L shl bits) - 1L
            accumulator = (accumulator shl bits) or (value.toLong() and mask)
            bitCount += bits
            while (bitCount >= 8) {
                bitCount -= 8
                output.write(((accumulator ushr bitCount) and 0xFF).toInt())
            }
        }

        fun finish(): ByteArray {
            if (bitCount > 0) {
                output.write(((accumulator shl (8 - bitCount)) and 0xFF).toInt())
                bitCount = 0
            }
            return output.toByteArray()
        }
    }

    private companion object {
        const val TYPE_SINGLE_CHANNEL = 0
        const val TYPE_CHANNEL_PAIR = 1
        const val TYPE_END = 7
    }
}
