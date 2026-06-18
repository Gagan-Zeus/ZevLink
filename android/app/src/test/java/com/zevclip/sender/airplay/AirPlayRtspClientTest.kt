package com.zevclip.sender.airplay

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayRtspClientTest {
    @Test
    fun getInfoSendsAirPlayProbeHeadersAndReadsBody() {
        RtspTestServer(
            listOf(
                rtspResponse(
                    status = "200 OK",
                    headers = mapOf("Content-Type" to "application/x-apple-binary-plist"),
                    body = "hello".toByteArray(Charsets.US_ASCII)
                )
            )
        ).use { server ->
            val client = AirPlayRtspClient(AirPlayTarget("127.0.0.1", server.port))

            val response = client.getInfo()
            client.close()

            assertTrue(response.isSuccessful())
            assertEquals("application/x-apple-binary-plist", response.header("content-type"))
            assertArrayEquals("hello".toByteArray(Charsets.US_ASCII), response.body)

            val request = server.awaitRequests(1).first()
            assertEquals("GET /info RTSP/1.0", request.statusLine)
            assertEquals("1", request.headers["cseq"])
            assertTrue(request.headers["user-agent"]?.contains("ZevLink") == true)
            assertEquals("3", request.headers["x-apple-hkp"])
        }
    }

    @Test
    fun requestsIncrementCseqAndSendBodies() {
        RtspTestServer(
            listOf(
                rtspResponse(status = "200 OK"),
                rtspResponse(status = "204 No Content")
            )
        ).use { server ->
            val client = AirPlayRtspClient(AirPlayTarget("127.0.0.1", server.port))
            val body = "v=0\r\ns=ZevClip\r\n".toByteArray(Charsets.US_ASCII)

            val announce = client.request(
                method = "ANNOUNCE",
                uri = AirPlayRtspClient.rtspUrl("127.0.0.1", server.port, "/stream"),
                body = body,
                contentType = "application/sdp"
            )
            val options = client.options()
            client.close()

            assertEquals(200, announce.statusCode)
            assertEquals(204, options.statusCode)

            val requests = server.awaitRequests(2)
            assertEquals("ANNOUNCE rtsp://127.0.0.1:${server.port}/stream RTSP/1.0", requests[0].statusLine)
            assertEquals("1", requests[0].headers["cseq"])
            assertEquals("application/sdp", requests[0].headers["content-type"])
            assertEquals(body.size.toString(), requests[0].headers["content-length"])
            assertArrayEquals(body, requests[0].body)

            assertEquals("OPTIONS * RTSP/1.0", requests[1].statusLine)
            assertEquals("2", requests[1].headers["cseq"])
        }
    }

    @Test
    fun rtspUrlBracketsIpv6Hosts() {
        assertEquals(
            "rtsp://[2409:40f2::1]:7000/stream",
            AirPlayRtspClient.rtspUrl("2409:40f2::1", 7000, "/stream")
        )
    }

    private data class CapturedRequest(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private class RtspTestServer(
        private val responses: List<ByteArray>
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
        private val worker = thread(start = true, name = "rtsp-test-server") {
            runCatching {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    responses.forEach { response ->
                        requests += readRequest(input)
                        output.write(response)
                        output.flush()
                    }
                }
            }
        }

        val port: Int
            get() = serverSocket.localPort

        fun awaitRequests(count: Int): List<CapturedRequest> {
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) {
                synchronized(requests) {
                    if (requests.size >= count) return requests.take(count)
                }
                Thread.sleep(10)
            }
            error("Timed out waiting for $count RTSP requests. Saw ${requests.size}.")
        }

        override fun close() {
            runCatching { serverSocket.close() }
            worker.join(1_000)
        }

        private fun readRequest(input: InputStream): CapturedRequest {
            val headerBytes = ByteArrayOutputStream()
            var state = 0
            val delimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (state < delimiter.size) {
                val next = input.read()
                require(next >= 0) { "Request ended before headers completed." }
                headerBytes.write(next)
                state = if (next.toByte() == delimiter[state]) {
                    state + 1
                } else if (next.toByte() == delimiter[0]) {
                    1
                } else {
                    0
                }
            }

            val headerText = headerBytes.toString(Charsets.US_ASCII.name())
            val lines = headerText.split("\r\n")
            val headers = linkedMapOf<String, String>()
            lines.drop(1).forEach { line ->
                if (line.isEmpty()) return@forEach
                val separator = line.indexOf(':')
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(body, offset, contentLength - offset)
                require(read >= 0) { "Request body ended early." }
                offset += read
            }

            return CapturedRequest(lines.first(), headers, body)
        }
    }

    private fun rtspResponse(
        status: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0)
    ): ByteArray {
        return buildString {
            append("RTSP/1.0 ")
            append(status)
            append("\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if (body.isNotEmpty()) append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII) + body
    }
}
