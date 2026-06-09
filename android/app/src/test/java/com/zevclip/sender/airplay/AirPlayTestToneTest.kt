package com.zevclip.sender.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AirPlayTestToneTest {
    @Test
    fun generatesStereoPcm16Tone() {
        val pcm = AirPlayTestTone.pcm16Stereo(
            sampleRate = 44_100,
            durationMs = 1_000,
            frequencyHz = 440.0,
            amplitude = 0.5
        )

        assertEquals(44_100 * 4, pcm.size)
        assertEquals(pcm[0], pcm[2])
        assertEquals(pcm[1], pcm[3])
        assertNotEquals(0, pcm.drop(8).take(200).sumOf { it.toInt() and 0xFF })
    }

    @Test
    fun generatesChunksFromRequestedStartFrame() {
        val first = AirPlayTestTone.pcm16StereoChunk(
            sampleRate = 44_100,
            startFrame = 0,
            frameCount = 128
        )
        val second = AirPlayTestTone.pcm16StereoChunk(
            sampleRate = 44_100,
            startFrame = 128,
            frameCount = 128
        )

        assertEquals(128 * 4, first.size)
        assertEquals(128 * 4, second.size)
        assertNotEquals(first.toList(), second.toList())
    }
}
