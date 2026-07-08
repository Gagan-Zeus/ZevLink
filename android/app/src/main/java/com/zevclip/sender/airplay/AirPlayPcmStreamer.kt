package com.zevclip.sender.airplay

import java.io.Closeable

class AirPlayPcmStreamer(
    private val packetizer: AirPlayRtpAudioPacketizer,
    private val sink: AirPlayPacketSink
) : Closeable {
    fun sendPcm(pcm16LittleEndian: ByteArray): Int {
        var count = 0
        packetizer.consumePcm(pcm16LittleEndian).forEach { packet ->
            sink.send(packet)
            count++
        }
        return count
    }

    fun finishWithPadding(paddingFrames: Int = 0): Int {
        var count = 0
        packetizer.flushFinal()?.let { packet ->
            sink.send(packet)
            count++
        }
        packetizer.silencePaddingPackets(paddingFrames).forEach { packet ->
            sink.send(packet)
            count++
        }
        return count
    }

    override fun close() {
        (sink as? Closeable)?.close()
    }
}
