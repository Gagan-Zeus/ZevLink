package com.zevclip.sender.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayAudioSessionControllerTest {
    @Test
    fun sendsSetupOneSetupTwoAndRecordInOrder() {
        val transport = FakeTransport(
            responses = ArrayDeque(
                listOf(
                    response(200, BPlist.encode(BPlist.dict("eventPort" to BPlist.int(7101)))),
                    response(
                        200,
                        BPlist.encode(
                            BPlist.dict(
                                "streams" to BPlist.array(
                                    BPlist.dict(
                                        "dataPort" to BPlist.int(6200),
                                        "controlPort" to BPlist.int(6201)
                                    )
                                )
                            )
                        )
                    ),
                    response(200, ByteArray(0)),
                    response(200, ByteArray(0)),
                    response(200, ByteArray(0)),
                    response(200, ByteArray(0)),
                    response(200, ByteArray(0))
                )
            )
        )
        val sharedSecret = ByteArray(32) { index -> (index + 1).toByte() }
        val session = AirPlayPairVerifier.Session(
            target = AirPlayTarget("mac.local", 7000),
            accessoryId = "Mac",
            accessoryPublicKey = null,
            sharedSecret = sharedSecret,
            encryptionKey = ByteArray(32)
        )
        val ids = AirPlayAudioSetup.SessionIds(
            sessionUuid = "11111111-2222-3333-4444-555555555555",
            groupUuid = "66666666-7777-8888-9999-000000000000",
            streamConnectionId = 123456
        )
        val controller = AirPlayAudioSessionController(
            target = session.target,
            transport = transport,
            pairVerifySession = session,
            localRtspHost = "2409:40f2::1",
            deviceId = "AA:BB:CC:DD:EE:FF"
        )

        val setup = controller.setup(timingPort = 50000, controlPort = 50001, ids = ids)
        assertTrue(setup is AirPlayAudioSessionController.Result.Success)
        val setupResult = (setup as AirPlayAudioSessionController.Result.Success).setup
        assertEquals(7101, setupResult.ports.eventPort)
        assertEquals(6200, setupResult.ports.dataPort)
        assertEquals(6201, setupResult.ports.controlPort)
        assertArrayEquals(AirPlayAudioSetup.deriveAudioSharedKey(sharedSecret), setupResult.audioSharedKey)

        val record = controller.record(ids)
        assertEquals(200, record.statusCode)
        controller.flush(ids, sequenceNumber = 42, rtpTimestamp = 100)
        controller.setVolume(ids, volume = -12.5)
        controller.sendProgress(ids, startTimestamp = 1, currentTimestamp = 2, endTimestamp = 3)
        controller.feedback(ids)

        assertEquals("SETUP", transport.requests[0].method)
        assertEquals("1", transport.requests[0].headers["CSeq"])
        assertEquals("application/x-apple-binary-plist", transport.requests[0].contentType)
        assertEquals("rtsp://[2409:40f2::1]:7000/123456", transport.requests[0].uri)

        assertEquals("SETUP", transport.requests[1].method)
        assertEquals("2", transport.requests[1].headers["CSeq"])
        val streamPayload = BPlist.decode(transport.requests[1].body) as BPlist.Value.DictValue
        val stream = (streamPayload.values["streams"] as BPlist.Value.ArrayValue)
            .values
            .first() as BPlist.Value.DictValue
        assertEquals(BPlist.int(50001), stream.values["controlPort"])

        assertEquals("RECORD", transport.requests[2].method)
        assertEquals("3", transport.requests[2].headers["CSeq"])
        assertEquals(0, transport.requests[2].body.size)
        assertEquals("FLUSH", transport.requests[3].method)
        assertEquals("seq=42;rtptime=100", transport.requests[3].headers["RTP-Info"])
        assertEquals("SET_PARAMETER", transport.requests[4].method)
        assertEquals("volume: -12.5\r\n", transport.requests[4].body.toString(Charsets.US_ASCII))
        assertEquals("SET_PARAMETER", transport.requests[5].method)
        assertEquals("progress: 1/2/3\r\n", transport.requests[5].body.toString(Charsets.US_ASCII))
        assertEquals("POST", transport.requests[6].method)
        assertEquals("/feedback", transport.requests[6].uri)
    }

    private data class CapturedRequest(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val contentType: String?
    )

    private class FakeTransport(
        private val responses: ArrayDeque<AirPlayRtspClient.Response>
    ) : AirPlayRtspTransport {
        val requests = mutableListOf<CapturedRequest>()

        override fun request(
            method: String,
            uri: String,
            headers: Map<String, String>,
            body: ByteArray,
            contentType: String?
        ): AirPlayRtspClient.Response {
            requests += CapturedRequest(method, uri, headers, body, contentType)
            return responses.removeFirst()
        }
    }

    private fun response(statusCode: Int, body: ByteArray): AirPlayRtspClient.Response {
        return AirPlayRtspClient.Response(
            protocol = "RTSP/1.0",
            statusCode = statusCode,
            reasonPhrase = "OK",
            headers = if (body.isNotEmpty()) mapOf("content-length" to body.size.toString()) else emptyMap(),
            body = body
        )
    }
}
