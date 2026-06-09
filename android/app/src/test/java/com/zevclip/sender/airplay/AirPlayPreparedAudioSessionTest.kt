package com.zevclip.sender.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayPreparedAudioSessionTest {
    @Test
    fun startsPreparedSessionAndStreamsPcmToSink() {
        val responses = ArrayDeque(
            listOf(
                response(200, BPlist.encode(BPlist.dict("eventPort" to BPlist.int(0)))),
                response(
                    200,
                    BPlist.encode(
                        BPlist.dict(
                            "streams" to BPlist.array(
                                BPlist.dict(
                                    "dataPort" to BPlist.int(6200),
                                    "controlPort" to BPlist.int(0)
                                )
                            )
                        )
                    )
                ),
                response(200, ByteArray(0)),
                response(200, ByteArray(0)),
                response(200, ByteArray(0)),
                response(200, ByteArray(0))
            )
        )
        val transport = FakeTransport(responses)
        val sentPackets = mutableListOf<ByteArray>()
        val pairSession = AirPlayPairVerifier.Session(
            target = AirPlayTarget("127.0.0.1", 7000),
            accessoryId = null,
            accessoryPublicKey = null,
            sharedSecret = ByteArray(32) { (it + 1).toByte() },
            encryptionKey = ByteArray(32)
        )
        val prepared = AirPlayPreparedAudioSession(
            target = pairSession.target,
            pairVerifySession = pairSession,
            transport = transport,
            packetSinkFactory = {
                object : AirPlayPacketSink {
                    override fun send(packet: ByteArray) {
                        sentPackets += packet
                    }
                }
            }
        )

        val result = prepared.start(
            controlPort = 53000,
            format = AirPlayAudioSetup.AudioFormat(framesPerPacket = 2, useAlac = false),
            useAlacPayloads = false
        )
        assertTrue(result is AirPlayPreparedAudioSession.Result.Success)
        val running = (result as AirPlayPreparedAudioSession.Result.Success).session
        running.streamer.sendPcm(ByteArray(2 * 2 * 2))
        running.streamer.finishWithPadding(0)
        prepared.stop()

        assertEquals(6, transport.requests.size)
        assertEquals("RECORD", transport.requests[2].method)
        assertEquals("FLUSH", transport.requests[3].method)
        assertEquals("SET_PARAMETER", transport.requests[4].method)
        assertEquals("POST", transport.requests[5].method)
        assertEquals(1, sentPackets.size)
    }

    private data class CapturedRequest(val method: String)

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
            requests += CapturedRequest(method)
            return responses.removeFirst()
        }
    }

    private fun response(statusCode: Int, body: ByteArray): AirPlayRtspClient.Response {
        return AirPlayRtspClient.Response(
            protocol = "RTSP/1.0",
            statusCode = statusCode,
            reasonPhrase = "OK",
            headers = emptyMap(),
            body = body
        )
    }
}
