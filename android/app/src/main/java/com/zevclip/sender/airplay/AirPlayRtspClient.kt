package com.zevclip.sender.airplay

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

class AirPlayRtspClient(
    private val target: AirPlayTarget,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val socketFactory: () -> Socket = { Socket() }
) : Closeable, AirPlayRtspTransport {
    private var socket: Socket? = null
    private var cseq = 1

    data class Response(
        val protocol: String,
        val statusCode: Int,
        val reasonPhrase: String,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        fun header(name: String): String? {
            return headers[name.lowercase(Locale.US)]
        }

        fun isSuccessful(): Boolean {
            return statusCode in 200..299
        }
    }

    fun connect() {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        val nextSocket = socketFactory()
        nextSocket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
        nextSocket.soTimeout = readTimeoutMs
        socket = nextSocket
    }

    fun localAddressHost(): String {
        return socket?.localAddress?.hostAddress ?: target.host
    }

    fun encryptedTransport(sharedSecret: ByteArray): AirPlayEncryptedChannel {
        val activeSocket = socket ?: error("RTSP socket is not connected.")
        return AirPlayEncryptedChannel(
            input = activeSocket.getInputStream(),
            output = activeSocket.getOutputStream(),
            keys = AirPlayEncryptedChannel.controllerKeys(sharedSecret)
        )
    }

    fun getInfo(): Response {
        return request(
            method = "GET",
            uri = "/info",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "X-Apple-HKP" to "3"
            ),
            body = ByteArray(0),
            contentType = null
        )
    }

    fun options(): Response {
        return request(
            method = "OPTIONS",
            uri = "*",
            headers = mapOf("User-Agent" to USER_AGENT),
            body = ByteArray(0),
            contentType = null
        )
    }

    override fun request(
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: ByteArray,
        contentType: String?
    ): Response {
        require(method.isNotBlank()) { "RTSP method must not be blank." }
        require(uri.isNotBlank()) { "RTSP URI must not be blank." }
        connect()

        val activeSocket = socket ?: error("RTSP socket is not connected.")
        val output = activeSocket.getOutputStream()
        val requestHeaders = LinkedHashMap<String, String>().apply {
            put("CSeq", (cseq++).toString())
            putAll(headers)
            if (contentType != null) put("Content-Type", contentType)
            if (body.isNotEmpty()) put("Content-Length", body.size.toString())
        }

        val requestText = buildString {
            append(method.uppercase(Locale.US))
            append(' ')
            append(uri)
            append(" RTSP/1.0\r\n")
            requestHeaders.forEach { (name, value) ->
                append(name)
                append(": ")
                append(value)
                append("\r\n")
            }
            append("\r\n")
        }

        output.write(requestText.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) output.write(body)
        output.flush()

        return readResponse(activeSocket.getInputStream())
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun readResponse(input: InputStream): Response {
        val headerBytes = ByteArrayOutputStream()
        var state = 0

        while (state < RESPONSE_HEADER_END.size) {
            val nextByte = input.read()
            require(nextByte >= 0) { "RTSP response ended before headers completed." }
            headerBytes.write(nextByte)
            state = if (nextByte.toByte() == RESPONSE_HEADER_END[state]) {
                state + 1
            } else if (nextByte.toByte() == RESPONSE_HEADER_END[0]) {
                1
            } else {
                0
            }
        }

        val headerText = headerBytes.toString(Charsets.US_ASCII.name())
        val lines = headerText.split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        val statusMatch = STATUS_LINE_REGEX.matchEntire(statusLine)
        require(statusMatch != null) { "RTSP response status line is invalid." }

        val headers = LinkedHashMap<String, String>()
        lines.drop(1).forEach { line ->
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            require(separator > 0) { "RTSP response header is invalid." }
            val name = line.substring(0, separator).trim().lowercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            headers[name] = value
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        require(contentLength >= 0) { "RTSP response Content-Length is invalid." }
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            require(read >= 0) { "RTSP response body ended early." }
            offset += read
        }

        return Response(
            protocol = statusMatch.groupValues[1],
            statusCode = statusMatch.groupValues[2].toInt(),
            reasonPhrase = statusMatch.groupValues[3],
            headers = headers,
            body = body
        )
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 4_000
        private const val USER_AGENT = "AirPlay/950.7.1 ZevLink"
        private val RESPONSE_HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        private val STATUS_LINE_REGEX = Regex("(RTSP/1\\.0)\\s+(\\d{3})(?:\\s+(.*))?")

        fun rtspUrl(host: String, port: Int, path: String): String {
            require(path.startsWith("/")) { "RTSP path must start with /." }
            val formattedHost = if (host.contains(':') && !host.startsWith("[")) {
                "[$host]"
            } else {
                host
            }
            return "rtsp://$formattedHost:$port$path"
        }
    }
}
