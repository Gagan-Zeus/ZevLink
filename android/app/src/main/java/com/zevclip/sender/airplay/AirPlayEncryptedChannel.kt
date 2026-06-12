package com.zevclip.sender.airplay

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

class AirPlayEncryptedChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val keys: Keys
) : AirPlayRtspTransport {
    data class Keys(
        val writeKey: ByteArray,
        val readKey: ByteArray
    ) {
        init {
            require(writeKey.size == KEY_SIZE) { "AirPlay encrypted write key must be 32 bytes." }
            require(readKey.size == KEY_SIZE) { "AirPlay encrypted read key must be 32 bytes." }
        }
    }

    private var outgoingCounter = 0L
    private var incomingCounter = 0L

    override fun request(
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: ByteArray,
        contentType: String?
    ): AirPlayRtspClient.Response {
        val payload = buildRtspRequest(method, uri, headers, body, contentType)
        send(payload)
        return parseRtspResponse(receiveRtspResponse())
    }

    fun send(plaintext: ByteArray) {
        var offset = 0
        do {
            val chunkSize = minOf(MAX_FRAME_PAYLOAD_SIZE, plaintext.size - offset)
            val chunk = plaintext.copyOfRange(offset, offset + chunkSize)
            val length = littleEndianLength(chunk.size)
            val encrypted = CryptoPrimitives.chacha20Poly1305Encrypt(
                key = keys.writeKey,
                nonce = counterNonce(outgoingCounter),
                plaintext = chunk,
                aad = length
            )
            output.write(length)
            output.write(encrypted)
            outgoingCounter++
            offset += chunkSize
        } while (offset < plaintext.size)
        output.flush()
    }

    fun receiveBlock(): ByteArray {
        val length = readExact(FRAME_LENGTH_SIZE)
        val blockSize = (length[0].toInt() and 0xFF) or ((length[1].toInt() and 0xFF) shl 8)
        require(blockSize <= MAX_FRAME_PAYLOAD_SIZE) { "AirPlay encrypted frame is too large." }
        val encrypted = readExact(blockSize + POLY1305_TAG_SIZE)
        return CryptoPrimitives.chacha20Poly1305Decrypt(
            key = keys.readKey,
            nonce = counterNonce(incomingCounter++),
            ciphertextWithTag = encrypted,
            aad = length
        )
    }

    fun receiveRtspResponse(): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            output.write(receiveBlock())
            val bytes = output.toByteArray()
            val headerEnd = indexOfHeaderEnd(bytes)
            if (headerEnd >= 0) {
                val headerText = bytes.copyOfRange(0, headerEnd).toString(Charsets.US_ASCII)
                val contentLength = CONTENT_LENGTH_REGEX.find(headerText)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: 0
                if (bytes.size >= headerEnd + HEADER_DELIMITER_SIZE + contentLength) {
                    return bytes
                }
            }
        }
    }

    fun requestRaw(
        method: String,
        uri: String,
        protocol: String,
        headers: Map<String, String>,
        body: ByteArray,
        contentType: String?
    ): AirPlayRtspClient.Response {
        val requestText = buildString {
            append(method.uppercase(Locale.US))
            append(' ')
            append(uri)
            append(' ')
            append(protocol)
            append("\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if (contentType != null) append("Content-Type: $contentType\r\n")
            if (body.isNotEmpty()) append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }
        send(requestText.toByteArray(Charsets.US_ASCII) + body)
        return parseRtspResponse(receiveRtspResponse())
    }

    private fun readExact(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            require(read >= 0) { "AirPlay encrypted stream ended early." }
            offset += read
        }
        return buffer
    }

    companion object {
        private const val KEY_SIZE = 32
        private const val FRAME_LENGTH_SIZE = 2
        private const val HEADER_DELIMITER_SIZE = 4
        private const val MAX_FRAME_PAYLOAD_SIZE = 0x400
        private const val POLY1305_TAG_SIZE = 16
        private val CONTROL_SALT = "Control-Salt".toByteArray(Charsets.US_ASCII)
        private val CONTENT_LENGTH_REGEX = Regex("(?i)content-length:\\s*(\\d+)")
        private val STATUS_LINE_REGEX = Regex("(RTSP/1\\.0|HTTP/1\\.[01])\\s+(\\d{3})(?:\\s+(.*))?")

        fun controllerKeys(sharedSecret: ByteArray): Keys {
            return Keys(
                writeKey = CryptoPrimitives.hkdfSha512(
                    inputKeyMaterial = sharedSecret,
                    salt = CONTROL_SALT,
                    info = "Control-Write-Encryption-Key".toByteArray(Charsets.US_ASCII),
                    outputSize = KEY_SIZE
                ),
                readKey = CryptoPrimitives.hkdfSha512(
                    inputKeyMaterial = sharedSecret,
                    salt = CONTROL_SALT,
                    info = "Control-Read-Encryption-Key".toByteArray(Charsets.US_ASCII),
                    outputSize = KEY_SIZE
                )
            )
        }

        fun accessoryKeys(sharedSecret: ByteArray): Keys {
            val controllerKeys = controllerKeys(sharedSecret)
            return Keys(
                writeKey = controllerKeys.readKey,
                readKey = controllerKeys.writeKey
            )
        }

        fun counterNonce(counter: Long): ByteArray {
            require(counter >= 0) { "AirPlay encrypted frame counter must be non-negative." }
            return ByteArray(12).also { nonce ->
                for (index in 0 until 8) {
                    nonce[4 + index] = ((counter ushr (index * 8)) and 0xFF).toByte()
                }
            }
        }

        fun buildRtspRequest(
            method: String,
            uri: String,
            headers: Map<String, String>,
            body: ByteArray,
            contentType: String?
        ): ByteArray {
            val requestText = buildString {
                append(method.uppercase(Locale.US))
                append(' ')
                append(uri)
                append(" RTSP/1.0\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                if (contentType != null) append("Content-Type: $contentType\r\n")
                if (body.isNotEmpty()) append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }
            return requestText.toByteArray(Charsets.US_ASCII) + body
        }

        fun parseRtspResponse(bytes: ByteArray): AirPlayRtspClient.Response {
            val headerEnd = indexOfHeaderEnd(bytes)
            require(headerEnd >= 0) { "RTSP response is missing header terminator." }
            val headerText = bytes.copyOfRange(0, headerEnd).toString(Charsets.US_ASCII)
            val lines = headerText.split("\r\n")
            val statusMatch = STATUS_LINE_REGEX.matchEntire(lines.firstOrNull().orEmpty())
            require(statusMatch != null) { "RTSP response status line is invalid." }

            val headers = linkedMapOf<String, String>()
            lines.drop(1).forEach { line ->
                if (line.isEmpty()) return@forEach
                val separator = line.indexOf(':')
                require(separator > 0) { "RTSP response header is invalid." }
                headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                    line.substring(separator + 1).trim()
            }
            val bodyStart = headerEnd + HEADER_DELIMITER_SIZE
            return AirPlayRtspClient.Response(
                protocol = statusMatch.groupValues[1],
                statusCode = statusMatch.groupValues[2].toInt(),
                reasonPhrase = statusMatch.groupValues[3],
                headers = headers,
                body = bytes.copyOfRange(bodyStart, bytes.size)
            )
        }

        private fun littleEndianLength(size: Int): ByteArray {
            require(size in 0..MAX_FRAME_PAYLOAD_SIZE) { "AirPlay encrypted frame length is invalid." }
            return byteArrayOf((size and 0xFF).toByte(), ((size ushr 8) and 0xFF).toByte())
        }

        private fun indexOfHeaderEnd(bytes: ByteArray): Int {
            for (index in 0..bytes.size - HEADER_DELIMITER_SIZE) {
                if (
                    bytes[index] == '\r'.code.toByte() &&
                    bytes[index + 1] == '\n'.code.toByte() &&
                    bytes[index + 2] == '\r'.code.toByte() &&
                    bytes[index + 3] == '\n'.code.toByte()
                ) {
                    return index
                }
            }
            return -1
        }
    }
}
