package com.zevclip.sender.airplay

class AirPlayPcmStreamer(
    private val packetizer: AirPlayRtpAudioPacketizer,
    private val sink: AirPlayPacketSink
) {
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
}
