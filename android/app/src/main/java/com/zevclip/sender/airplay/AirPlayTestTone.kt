package com.zevclip.sender.airplay

import kotlin.math.PI
import kotlin.math.sin

object AirPlayTestTone {
    fun pcm16Stereo(
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        durationMs: Int = DEFAULT_DURATION_MS,
        frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
        amplitude: Double = DEFAULT_AMPLITUDE
    ): ByteArray {
        require(sampleRate > 0) { "Sample rate must be positive." }
        require(durationMs > 0) { "Duration must be positive." }
        require(frequencyHz > 0.0) { "Frequency must be positive." }
        require(amplitude in 0.0..1.0) { "Amplitude must be between 0 and 1." }

        val frames = sampleRate * durationMs / 1_000
        return pcm16StereoChunk(
            sampleRate = sampleRate,
            startFrame = 0,
            frameCount = frames,
            frequencyHz = frequencyHz,
            amplitude = amplitude
        )
    }

    fun pcm16StereoChunk(
        sampleRate: Int,
        startFrame: Int,
        frameCount: Int,
        frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
        amplitude: Double = DEFAULT_AMPLITUDE
    ): ByteArray {
        require(sampleRate > 0) { "Sample rate must be positive." }
        require(startFrame >= 0) { "Start frame must not be negative." }
        require(frameCount >= 0) { "Frame count must not be negative." }
        require(frequencyHz > 0.0) { "Frequency must be positive." }
        require(amplitude in 0.0..1.0) { "Amplitude must be between 0 and 1." }

        val pcm = ByteArray(frameCount * BYTES_PER_STEREO_FRAME)
        var offset = 0
        for (index in 0 until frameCount) {
            val phase = 2.0 * PI * frequencyHz * (startFrame + index).toDouble() / sampleRate.toDouble()
            val sample = (sin(phase) * Short.MAX_VALUE * amplitude).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            writeSample(pcm, offset, sample)
            offset += BYTES_PER_SAMPLE
            writeSample(pcm, offset, sample)
            offset += BYTES_PER_SAMPLE
        }
        return pcm
    }

    private fun writeSample(output: ByteArray, offset: Int, sample: Short) {
        val value = sample.toInt()
        output[offset] = (value and 0xFF).toByte()
        output[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    const val DEFAULT_SAMPLE_RATE = 44_100
    const val DEFAULT_DURATION_MS = 2_000
    const val DEFAULT_FREQUENCY_HZ = 440.0
    const val DEFAULT_AMPLITUDE = 0.22
    private const val BYTES_PER_SAMPLE = 2
    private const val BYTES_PER_STEREO_FRAME = BYTES_PER_SAMPLE * 2
}
