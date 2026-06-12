package com.zevclip.sender.airplay

import java.io.ByteArrayOutputStream

class LiveHlsSegmenter(
    private val targetDurationSeconds: Int = 2,
    private val maxSegments: Int = 6
) : AirPlayScreenSampleSink {
    data class Segment(
        val index: Int,
        val durationSeconds: Double,
        val bytes: ByteArray
    )

    private val lock = Object()
    private val completed = ArrayDeque<Segment>()
    private var current = ByteArrayOutputStream()
    private var currentStartUs = -1L
    private var currentLastUs = -1L
    private var nextIndex = 0
    private var codecConfig = ByteArray(0)
    private var writer = MpegTsWriter()
    private var waitingForKeyFrame = true
    private var baseUrl: String? = null

    override fun setCodecConfig(config: ByteArray) {
        synchronized(lock) {
            codecConfig = config.toAnnexB()
        }
    }

    fun setBaseUrl(url: String) {
        synchronized(lock) {
            baseUrl = url.trimEnd('/')
        }
    }

    override fun writeSample(sample: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        val annexB = sample.toAnnexB()
        synchronized(lock) {
            if (waitingForKeyFrame && !keyFrame) return
            if (keyFrame) waitingForKeyFrame = false
            if (currentStartUs < 0L) {
                startSegment(presentationTimeUs)
            } else if (keyFrame && presentationTimeUs - currentStartUs >= targetDurationSeconds * 1_000_000L) {
                finishSegment()
                startSegment(presentationTimeUs)
            }

            val payload = if (keyFrame && codecConfig.isNotEmpty() && !annexB.containsSps()) {
                AUD_NAL + codecConfig + annexB
            } else {
                AUD_NAL + annexB
            }
            current.write(writer.writeVideoSample(payload, presentationTimeUs))
            currentLastUs = presentationTimeUs
            lock.notifyAll()
        }
    }

    fun playlist(): String {
        synchronized(lock) {
            val first = completed.firstOrNull()?.index ?: nextIndex
            val visible = completed.toList()
            return buildString {
                append("#EXTM3U\n")
                append("#EXT-X-VERSION:3\n")
                append("#EXT-X-TARGETDURATION:$targetDurationSeconds\n")
                append("#EXT-X-MEDIA-SEQUENCE:$first\n")
                append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
                append("#EXT-X-INDEPENDENT-SEGMENTS\n")
                visible.forEach { segment ->
                    append("#EXTINF:${"%.3f".format(java.util.Locale.US, segment.durationSeconds)},\n")
                    baseUrl?.let { append(it).append('/') }
                    append("seg${segment.index}.ts\n")
                }
            }
        }
    }

    fun segment(index: Int): ByteArray? {
        synchronized(lock) {
            return completed.firstOrNull { it.index == index }?.bytes
        }
    }

    fun waitForFirstSegment(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (completed.isEmpty() && System.currentTimeMillis() < deadline) {
                lock.wait(maxOf(1L, deadline - System.currentTimeMillis()))
            }
            return completed.isNotEmpty()
        }
    }

    private fun startSegment(startUs: Long) {
        writer = MpegTsWriter()
        current = ByteArrayOutputStream()
        current.write(writer.writeTables())
        currentStartUs = startUs
        currentLastUs = startUs
    }

    private fun finishSegment() {
        val durationUs = maxOf(1L, currentLastUs - currentStartUs)
        completed += Segment(
            index = nextIndex++,
            durationSeconds = durationUs / 1_000_000.0,
            bytes = current.toByteArray()
        )
        while (completed.size > maxSegments) {
            completed.removeFirst()
        }
        lock.notifyAll()
    }

    private class MpegTsWriter {
        private var patCounter = 0
        private var pmtCounter = 0
        private var videoCounter = 0

        fun writeTables(): ByteArray {
            return packetizeSection(PAT_PID, patSection(), true) { patCounter++ } +
                packetizeSection(PMT_PID, pmtSection(), true) { pmtCounter++ }
        }

        fun writeVideoSample(annexB: ByteArray, ptsUs: Long): ByteArray {
            val pts90k = ptsUs * 90L / 1_000L
            val pes = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x00, 0x00, 0x01, 0xE0.toByte()))
                write(byteArrayOf(0x00, 0x00))
                write(0x80)
                write(0x80)
                write(0x05)
                writePts(pts90k)
                write(annexB)
            }.toByteArray()
            return packetizePes(VIDEO_PID, pes, pts90k)
        }

        private fun packetizePes(pid: Int, payload: ByteArray, pts90k: Long): ByteArray {
            val output = ByteArrayOutputStream()
            var offset = 0
            var first = true
            while (offset < payload.size) {
                val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
                packet[0] = SYNC_BYTE
                packet[1] = (((if (first) 0x40 else 0x00) or ((pid ushr 8) and 0x1F))).toByte()
                packet[2] = (pid and 0xFF).toByte()
                val continuity = videoCounter++ and 0x0F
                if (first) {
                    val maxPayload = TS_PACKET_SIZE - 12
                    val count = minOf(maxPayload, payload.size - offset)
                    writePacketHeader(
                        packet = packet,
                        continuity = continuity,
                        payloadOffset = 12,
                        payloadSize = count,
                        pcr90k = pts90k
                    )
                    payload.copyInto(packet, TS_PACKET_SIZE - count, offset, offset + count)
                    offset += count
                } else {
                    val count = minOf(TS_PACKET_SIZE - 4, payload.size - offset)
                    writePacketHeader(
                        packet = packet,
                        continuity = continuity,
                        payloadOffset = 4,
                        payloadSize = count,
                        pcr90k = null
                    )
                    payload.copyInto(packet, TS_PACKET_SIZE - count, offset, offset + count)
                    offset += count
                }
                output.write(packet)
                first = false
            }
            return output.toByteArray()
        }

        private fun writePacketHeader(
            packet: ByteArray,
            continuity: Int,
            payloadOffset: Int,
            payloadSize: Int,
            pcr90k: Long?
        ) {
            val headerSize = 4
            val hasAdaptation = pcr90k != null || payloadSize < TS_PACKET_SIZE - headerSize
            if (!hasAdaptation) {
                packet[3] = (0x10 or continuity).toByte()
                return
            }

            packet[3] = (0x30 or continuity).toByte()
            val adaptationLength = TS_PACKET_SIZE - headerSize - 1 - payloadSize
            packet[4] = adaptationLength.toByte()
            if (adaptationLength <= 0) return
            packet[5] = if (pcr90k != null) 0x10 else 0x00
            if (pcr90k != null && adaptationLength >= 7) {
                writePcr(packet, 6, pcr90k)
            }
            val payloadStart = TS_PACKET_SIZE - payloadSize
            for (index in payloadOffset until payloadStart) {
                if (index > 4 && (pcr90k == null || index > 11)) {
                    packet[index] = 0xFF.toByte()
                }
            }
        }

        private fun packetizeSection(
            pid: Int,
            section: ByteArray,
            payloadStart: Boolean,
            nextCounter: () -> Int
        ): ByteArray {
            val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
            packet[0] = SYNC_BYTE
            packet[1] = (((if (payloadStart) 0x40 else 0x00) or ((pid ushr 8) and 0x1F))).toByte()
            packet[2] = (pid and 0xFF).toByte()
            packet[3] = (0x10 or (nextCounter() and 0x0F)).toByte()
            packet[4] = 0
            section.copyInto(packet, 5)
            return packet
        }

        private fun patSection(): ByteArray {
            val body = byteArrayOf(
                0x00,
                0xB0.toByte(), 0x0D,
                0x00, 0x01,
                0xC1.toByte(),
                0x00, 0x00,
                0x00, 0x01,
                (0xE0 or ((PMT_PID ushr 8) and 0x1F)).toByte(), (PMT_PID and 0xFF).toByte()
            )
            return body + crc(body)
        }

        private fun pmtSection(): ByteArray {
            val body = byteArrayOf(
                0x02,
                0xB0.toByte(), 0x12,
                0x00, 0x01,
                0xC1.toByte(),
                0x00, 0x00,
                (0xE0 or ((VIDEO_PID ushr 8) and 0x1F)).toByte(), (VIDEO_PID and 0xFF).toByte(),
                0xF0.toByte(), 0x00,
                0x1B,
                (0xE0 or ((VIDEO_PID ushr 8) and 0x1F)).toByte(), (VIDEO_PID and 0xFF).toByte(),
                0xF0.toByte(), 0x00
            )
            return body + crc(body)
        }

        private fun ByteArrayOutputStream.writePts(pts90k: Long) {
            write(((0x2 shl 4) or (((pts90k ushr 30) and 0x07).toInt() shl 1) or 1))
            write((((pts90k ushr 22) and 0xFF).toInt()))
            write(((((pts90k ushr 15) and 0x7F).toInt() shl 1) or 1))
            write((((pts90k ushr 7) and 0xFF).toInt()))
            write((((pts90k and 0x7F).toInt() shl 1) or 1))
        }

        private fun writePcr(packet: ByteArray, offset: Int, pts90k: Long) {
            val base = pts90k
            packet[offset] = ((base ushr 25) and 0xFF).toByte()
            packet[offset + 1] = ((base ushr 17) and 0xFF).toByte()
            packet[offset + 2] = ((base ushr 9) and 0xFF).toByte()
            packet[offset + 3] = ((base ushr 1) and 0xFF).toByte()
            packet[offset + 4] = (((base and 1L) shl 7) or 0x7E).toByte()
            packet[offset + 5] = 0
        }

        private fun crc(bytes: ByteArray): ByteArray {
            var value = 0xFFFFFFFF.toInt()
            bytes.forEach { byte ->
                value = value xor ((byte.toInt() and 0xFF) shl 24)
                repeat(8) {
                    value = if (value and 0x80000000.toInt() != 0) {
                        (value shl 1) xor 0x04C11DB7
                    } else {
                        value shl 1
                    }
                }
            }
            return byteArrayOf(
                ((value ushr 24) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
        }
    }

    private companion object {
        private const val TS_PACKET_SIZE = 188
        private const val PAT_PID = 0x0000
        private const val PMT_PID = 0x0100
        private const val VIDEO_PID = 0x0101
        private const val SYNC_BYTE = 0x47.toByte()
        private val AUD_NAL = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
    }
}

private fun ByteArray.toAnnexB(): ByteArray {
    if (startsWithStartCode()) return this
    if (size < 4) return this
    val output = ByteArrayOutputStream()
    var offset = 0
    while (offset + 4 <= size) {
        val length = ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
        if (length <= 0 || offset + 4 + length > size) return this
        output.write(byteArrayOf(0, 0, 0, 1))
        output.write(this, offset + 4, length)
        offset += 4 + length
    }
    return output.toByteArray()
}

private fun ByteArray.startsWithStartCode(): Boolean {
    return size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() &&
        ((this[2] == 1.toByte()) || (this[2] == 0.toByte() && this[3] == 1.toByte()))
}

private fun ByteArray.containsSps(): Boolean {
    for (index in 0 until size - 5) {
        val start = this[index] == 0.toByte() && this[index + 1] == 0.toByte() &&
            (this[index + 2] == 1.toByte() || (this[index + 2] == 0.toByte() && this[index + 3] == 1.toByte()))
        if (!start) continue
        val nalIndex = if (this[index + 2] == 1.toByte()) index + 3 else index + 4
        if (nalIndex < size && (this[nalIndex].toInt() and 0x1F) == 7) return true
    }
    return false
}
