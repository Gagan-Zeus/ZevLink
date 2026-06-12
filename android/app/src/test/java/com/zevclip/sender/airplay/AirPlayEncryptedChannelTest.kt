package com.zevclip.sender.airplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayEncryptedChannelTest {
    @Test
    fun encryptsAndDecryptsChunkedFrames() {
        val sharedSecret = ByteArray(32) { index -> (index * 3).toByte() }
        val clientOutput = ByteArrayOutputStream()
        val client = AirPlayEncryptedChannel(
            input = ByteArrayInputStream(ByteArray(0)),
            output = clientOutput,
            keys = AirPlayEncryptedChannel.controllerKeys(sharedSecret)
        )
        val plaintext = ByteArray(1_500) { index -> (index % 251).toByte() }

        client.send(plaintext)

        val server = AirPlayEncryptedChannel(
            input = ByteArrayInputStream(clientOutput.toByteArray()),
            output = ByteArrayOutputStream(),
            keys = AirPlayEncryptedChannel.accessoryKeys(sharedSecret)
        )
        val first = server.receiveBlock()
        val second = server.receiveBlock()

        assertEquals(1_024, first.size)
        assertEquals(476, second.size)
        assertArrayEquals(plaintext, first + second)
    }

    @Test
    fun parsesEncryptedRtspResponseWithBody() {
        val sharedSecret = ByteArray(32) { index -> (index + 7).toByte() }
        val serverOutput = ByteArrayOutputStream()
        val server = AirPlayEncryptedChannel(
            input = ByteArrayInputStream(ByteArray(0)),
            output = serverOutput,
            keys = AirPlayEncryptedChannel.accessoryKeys(sharedSecret)
        )
        val body = "ok".toByteArray(Charsets.US_ASCII)
        server.send(
            "RTSP/1.0 200 OK\r\nContent-Length: ${body.size}\r\nSession: abc\r\n\r\n"
                .toByteArray(Charsets.US_ASCII) + body
        )

        val client = AirPlayEncryptedChannel(
            input = ByteArrayInputStream(serverOutput.toByteArray()),
            output = ByteArrayOutputStream(),
            keys = AirPlayEncryptedChannel.controllerKeys(sharedSecret)
        )
        val response = AirPlayEncryptedChannel.parseRtspResponse(client.receiveRtspResponse())

        assertEquals(200, response.statusCode)
        assertEquals("abc", response.header("Session"))
        assertArrayEquals(body, response.body)
    }
}
