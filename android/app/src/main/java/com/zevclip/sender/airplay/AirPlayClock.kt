package com.zevclip.sender.airplay

class AirPlayClock(
    val sampleRate: Int = 44_100
) {
    val latencyFrames: Long = 22_050L + sampleRate

    @Volatile
    var startTimestamp: Long = 0L
        private set

    @Volatile
    var headTimestamp: Long = 0L
        private set

    init {
        reset()
    }

    fun reset() {
        startTimestamp = ntpToRtpTimestamp(ntpNow(), sampleRate)
        headTimestamp = startTimestamp
    }

    val rtpTime: Long
        get() = headTimestamp - (startTimestamp - latencyFrames)

    val rtpTime32: Int
        get() = (rtpTime and 0xFFFFFFFFL).toInt()

    fun advance(frames: Int) {
        require(frames >= 0) { "AirPlay clock frames must be non-negative." }
        headTimestamp += frames
    }

    fun syncNtp(): Long {
        return rtpTimestampToNtp(headTimestamp, sampleRate)
    }

    companion object {
        const val NTP_EPOCH_OFFSET = 2_208_988_800L

        fun ntpNow(): Long {
            val millis = System.currentTimeMillis()
            val seconds = millis / 1000
            val fractionMicros = (millis % 1000) * 1000
            val ntpSeconds = (seconds + NTP_EPOCH_OFFSET) and 0xFFFFFFFFL
            val ntpFraction = (fractionMicros shl 32) / 1_000_000L
            return (ntpSeconds shl 32) or (ntpFraction and 0xFFFFFFFFL)
        }

        fun ntpParts(ntp: Long): Pair<Long, Long> {
            return (ntp ushr 32) and 0xFFFFFFFFL to (ntp and 0xFFFFFFFFL)
        }

        fun ntpToRtpTimestamp(ntp: Long, sampleRate: Int): Long {
            return (((ntp ushr 16) * sampleRate) ushr 16)
        }

        fun rtpTimestampToNtp(timestamp: Long, sampleRate: Int): Long {
            return java.lang.Long.divideUnsigned(timestamp shl 16, sampleRate.toLong()) shl 16
        }
    }
}
