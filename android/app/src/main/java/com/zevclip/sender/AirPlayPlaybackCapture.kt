package com.zevclip.sender

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

object AirPlayPlaybackCapture {
    const val SAMPLE_RATE = 44_100
    const val FRAMES_PER_PACKET = 352
    const val BYTES_PER_FRAME = 4
    const val PACKET_BYTES = FRAMES_PER_PACKET * BYTES_PER_FRAME

    fun createAudioRecord(projection: MediaProjection): AudioRecord {
        val captureConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, PACKET_BYTES * 4)
        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    class PacketSource(
        private val record: AudioRecord,
        private val running: AtomicBoolean
    ) : Closeable {
        fun readPacket(buffer: ByteArray): Int {
            if (!running.get()) return -1
            buffer.fill(0)
            var offset = 0
            while (offset < PACKET_BYTES && running.get()) {
                val read = record.read(buffer, offset, PACKET_BYTES - offset, AudioRecord.READ_BLOCKING)
                if (read < 0) return -1
                if (read == 0) break
                offset += read
            }
            return if (offset <= 0) 0 else offset / BYTES_PER_FRAME
        }

        override fun close() = Unit
    }
}
