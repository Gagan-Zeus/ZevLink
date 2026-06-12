package com.zevclip.sender.airplay

import java.util.Locale
import java.util.UUID

object AirPlayAudioSetup {
    data class SessionIds(
        val sessionUuid: String = UUID.randomUUID().toString().uppercase(Locale.US),
        val groupUuid: String = UUID.randomUUID().toString().uppercase(Locale.US),
        val streamConnectionId: Long = CryptoPrimitives.randomBytes(4)
            .fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
    )

    data class AudioFormat(
        val sampleRate: Int = 44_100,
        val channels: Int = 2,
        val framesPerPacket: Int = 352,
        val useAlac: Boolean = true
    ) {
        init {
            require(sampleRate > 0) { "AirPlay audio sample rate must be positive." }
            require(channels in 1..2) { "AirPlay audio currently supports mono or stereo." }
            require(framesPerPacket > 0) { "AirPlay frames per packet must be positive." }
        }

        val contentType: Int
            get() = if (useAlac) CONTENT_TYPE_ALAC else CONTENT_TYPE_PCM

        val audioFormatFlag: Int
            get() = if (useAlac) AUDIO_FORMAT_ALAC_44100_16_2 else AUDIO_FORMAT_PCM_44100_16_2
    }

    data class StreamPorts(
        val eventPort: Int,
        val dataPort: Int,
        val controlPort: Int
    )

    fun deriveAudioSharedKey(pairVerifySharedSecret: ByteArray): ByteArray {
        return CryptoPrimitives.hkdfSha512(
            inputKeyMaterial = pairVerifySharedSecret,
            salt = "Events-Salt".toByteArray(Charsets.US_ASCII),
            info = "Events-Write-Encryption-Key".toByteArray(Charsets.US_ASCII),
            outputSize = 32
        )
    }

    fun setupTimingPayload(
        deviceId: String,
        timingPort: Int,
        ids: SessionIds = SessionIds(),
        senderName: String = "ZevLink",
        senderInfo: AirPlaySenderInfo = AirPlaySenderInfo.fromDevice(deviceId, senderName)
    ): ByteArray {
        require(deviceId.isNotBlank()) { "AirPlay device id must not be blank." }
        require(timingPort in 0..65535) { "AirPlay timing port must fit in UDP port range." }
        return BPlist.encode(
            BPlist.dict(
                *senderInfo.setupEntries(),
                "sessionUUID" to BPlist.string(ids.sessionUuid),
                "groupUUID" to BPlist.string(ids.groupUuid),
                "isMultiSelectAirPlay" to BPlist.bool(true),
                "timingProtocol" to BPlist.string("NTP"),
                "timingPort" to BPlist.int(timingPort.toLong())
            )
        )
    }

    fun setupStreamPayload(
        audioSharedKey: ByteArray,
        controlPort: Int,
        ids: SessionIds,
        format: AudioFormat = AudioFormat()
    ): ByteArray {
        require(audioSharedKey.size == 32) { "AirPlay audio shared key must be 32 bytes." }
        require(controlPort in 1..65535) { "AirPlay control port must fit in UDP port range." }
        return BPlist.encode(
            BPlist.dict(
                "streams" to BPlist.array(
                    BPlist.dict(
                        "type" to BPlist.int(STREAM_TYPE_REALTIME.toLong()),
                        "ct" to BPlist.int(format.contentType.toLong()),
                        "audioFormat" to BPlist.int(format.audioFormatFlag.toLong()),
                        "audioMode" to BPlist.string("default"),
                        "isMedia" to BPlist.bool(true),
                        "sr" to BPlist.int(format.sampleRate.toLong()),
                        "spf" to BPlist.int(format.framesPerPacket.toLong()),
                        "shk" to BPlist.data(audioSharedKey),
                        "latencyMin" to BPlist.int(11_025),
                        "latencyMax" to BPlist.int(88_200),
                        "controlPort" to BPlist.int(controlPort.toLong()),
                        "supportsDynamicStreamID" to BPlist.bool(false),
                        "streamConnectionID" to BPlist.int(ids.streamConnectionId)
                    )
                )
            )
        )
    }

    fun parseSetupPorts(timingResponseBody: ByteArray, streamResponseBody: ByteArray): StreamPorts {
        val eventPort = (BPlist.decode(timingResponseBody) as BPlist.Value.DictValue)
            .values["eventPort"]
            .asInt()
            .toInt()
        val stream = ((BPlist.decode(streamResponseBody) as BPlist.Value.DictValue)
            .values["streams"] as BPlist.Value.ArrayValue)
            .values
            .first() as BPlist.Value.DictValue
        return StreamPorts(
            eventPort = eventPort,
            dataPort = stream.values["dataPort"].asInt().toInt(),
            controlPort = stream.values["controlPort"].asInt().toInt()
        )
    }

    private fun BPlist.Value?.asInt(): Long {
        return (this as? BPlist.Value.IntValue)?.value
            ?: error("Expected integer plist value.")
    }

    private const val STREAM_TYPE_REALTIME = 96
    private const val CONTENT_TYPE_PCM = 1
    private const val CONTENT_TYPE_ALAC = 2
    private const val AUDIO_FORMAT_PCM_44100_16_2 = 1 shl 11
    private const val AUDIO_FORMAT_ALAC_44100_16_2 = 1 shl 18
}
