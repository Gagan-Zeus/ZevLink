package com.zevclip.sender.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayAudioSetupTest {
    @Test
    fun buildsTimingSetupPlist() {
        val ids = AirPlayAudioSetup.SessionIds(
            sessionUuid = "11111111-2222-3333-4444-555555555555",
            groupUuid = "66666666-7777-8888-9999-000000000000",
            streamConnectionId = 1234
        )
        val payload = AirPlayAudioSetup.setupTimingPayload(
            deviceId = "AA:BB:CC:DD:EE:FF",
            timingPort = 49152,
            ids = ids,
            senderName = "ZevClip Test"
        )
        val decoded = BPlist.decode(payload) as BPlist.Value.DictValue

        assertEquals(BPlist.string("AA:BB:CC:DD:EE:FF"), decoded.values["deviceID"])
        assertEquals(BPlist.string(ids.sessionUuid), decoded.values["sessionUUID"])
        assertEquals(BPlist.string("NTP"), decoded.values["timingProtocol"])
        assertEquals(BPlist.int(49152), decoded.values["timingPort"])
        assertEquals(BPlist.string("ZevClip Test"), decoded.values["name"])
    }

    @Test
    fun buildsRealtimeAudioStreamSetupPlist() {
        val audioKey = ByteArray(32) { index -> index.toByte() }
        val ids = AirPlayAudioSetup.SessionIds(
            sessionUuid = "session",
            groupUuid = "group",
            streamConnectionId = 0x11223344
        )
        val payload = AirPlayAudioSetup.setupStreamPayload(
            audioSharedKey = audioKey,
            controlPort = 53000,
            ids = ids,
            format = AirPlayAudioSetup.AudioFormat(useAlac = true)
        )
        val decoded = BPlist.decode(payload) as BPlist.Value.DictValue
        val streams = decoded.values["streams"] as BPlist.Value.ArrayValue
        val stream = streams.values.first() as BPlist.Value.DictValue

        assertEquals(BPlist.int(96), stream.values["type"])
        assertEquals(BPlist.int(2), stream.values["ct"])
        assertEquals(BPlist.int(1L shl 18), stream.values["audioFormat"])
        assertEquals(BPlist.int(53000), stream.values["controlPort"])
        assertEquals(BPlist.int(ids.streamConnectionId), stream.values["streamConnectionID"])
        assertArrayEquals(audioKey, (stream.values["shk"] as BPlist.Value.DataValue).value)
    }

    @Test
    fun parsesSetupResponsePorts() {
        val timingResponse = BPlist.encode(BPlist.dict("eventPort" to BPlist.int(7001)))
        val streamResponse = BPlist.encode(
            BPlist.dict(
                "streams" to BPlist.array(
                    BPlist.dict(
                        "dataPort" to BPlist.int(6000),
                        "controlPort" to BPlist.int(6001)
                    )
                )
            )
        )

        val ports = AirPlayAudioSetup.parseSetupPorts(timingResponse, streamResponse)

        assertEquals(7001, ports.eventPort)
        assertEquals(6000, ports.dataPort)
        assertEquals(6001, ports.controlPort)
    }
}
