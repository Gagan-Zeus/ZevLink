package com.zevclip.sender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidClipboardHttpReceiver(
    context: Context,
    private val port: Int = DEFAULT_PORT,
    private val tokenProvider: () -> String = { ZevClipPreferences.pairingToken(context) },
    private val onReady: (Int) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val onTextReceived: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val acceptExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZevClipAndroidReceiver")
    }
    private val connectionExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ZevClipAndroidReceiverClient")
    }

    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        acceptExecutor.execute {
            if (serverSocket != null) {
                return@execute
            }

            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                serverSocket = socket
                mainHandler.post { onReady(port) }
                acceptLoop(socket)
            } catch (error: Exception) {
                serverSocket = null
                Log.w(TAG, "Android clipboard receiver failed", error)
                mainHandler.post {
                    onFailure("Could not start Android receiver on port $port: ${error.message ?: "unknown error"}")
                }
            }
        }
    }

    fun stop() {
        val socket = serverSocket
        serverSocket = null

        try {
            socket?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Error closing Android clipboard receiver", error)
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (serverSocket === socket && !socket.isClosed) {
            try {
                val client = socket.accept()
                connectionExecutor.execute { handleConnection(client) }
            } catch (_: SocketException) {
                break
            } catch (error: Exception) {
                Log.w(TAG, "Android clipboard receiver accept failed", error)
            }
        }

        if (serverSocket === socket) {
            serverSocket = null
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { client ->
            client.soTimeout = READ_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val headerBytes = try {
                readHeaders(input)
            } catch (error: HTTPFailure) {
                output.write(response(error.status, error.message).toByteArray(Charsets.UTF_8))
                return
            }

            val headerText = try {
                decodeUtf8(headerBytes)
            } catch (_: Exception) {
                output.write(response("400 Bad Request", "Headers must be valid UTF-8.").toByteArray(Charsets.UTF_8))
                return
            }

            val lines = headerText.split("\r\n")
            val requestParts = lines.firstOrNull()?.split(" ").orEmpty()
            if (requestParts.size != 3) {
                output.write(response("400 Bad Request", "Invalid request line.").toByteArray(Charsets.UTF_8))
                return
            }

            if (requestParts[0] != "POST") {
                output.write(response("405 Method Not Allowed", "Use POST.").toByteArray(Charsets.UTF_8))
                return
            }

            if (requestParts[1] != CLIPBOARD_PATH) {
                output.write(response("404 Not Found", "Use $CLIPBOARD_PATH.").toByteArray(Charsets.UTF_8))
                return
            }

            val headers = parseHeaders(lines.drop(1))
            val expectedToken = tokenProvider().trim()
            if (expectedToken.isEmpty() || headers[TOKEN_HEADER] != expectedToken) {
                output.write(response("401 Unauthorized", "Missing or invalid ZevClip pairing token.").toByteArray(Charsets.UTF_8))
                return
            }

            val contentLength = headers["content-length"]?.toIntOrNull()
            if (contentLength == null || contentLength < 0) {
                output.write(response("411 Length Required", "Content-Length is required.").toByteArray(Charsets.UTF_8))
                return
            }

            if (contentLength > MAX_BODY_LENGTH) {
                output.write(response("413 Content Too Large", "Clipboard text is limited to 1 MB.").toByteArray(Charsets.UTF_8))
                return
            }

            val body = try {
                readExactly(input, contentLength)
            } catch (error: HTTPFailure) {
                output.write(response(error.status, error.message).toByteArray(Charsets.UTF_8))
                return
            }

            val text = try {
                decodeUtf8(body)
            } catch (_: Exception) {
                output.write(response("400 Bad Request", "Request body must be valid UTF-8 text.").toByteArray(Charsets.UTF_8))
                return
            }

            if (!writeClipboard(text)) {
                output.write(response("500 Internal Server Error", "Android rejected the clipboard update.").toByteArray(Charsets.UTF_8))
                return
            }

            mainHandler.post { onTextReceived(text) }
            output.write(
                response(
                    "200 OK",
                    "Clipboard updated.",
                    mapOf(
                        ANDROID_DEVICE_ID_HEADER to ZevClipPreferences.androidDeviceId(appContext),
                        ANDROID_RECEIVER_NAME_HEADER to AndroidClipboardReceiverService.SERVICE_NAME
                    )
                ).toByteArray(Charsets.UTF_8)
            )
        }
    }

    private fun readHeaders(input: java.io.InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        var matched = 0

        while (buffer.size() <= MAX_HEADER_LENGTH) {
            val next = input.read()
            if (next == -1) {
                throw HTTPFailure("400 Bad Request", "Incomplete HTTP request.")
            }

            buffer.write(next)
            matched = if (next == HEADER_END[matched].code) {
                matched + 1
            } else if (next == HEADER_END[0].code) {
                1
            } else {
                0
            }

            if (matched == HEADER_END.length) {
                val bytes = buffer.toByteArray()
                return bytes.copyOf(bytes.size - HEADER_END.length)
            }
        }

        throw HTTPFailure("431 Request Header Fields Too Large", "Headers are too large.")
    }

    private fun readExactly(input: java.io.InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count == -1) {
                throw HTTPFailure("400 Bad Request", "Incomplete request body.")
            }
            offset += count
        }
        return body
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        return lines.mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) {
                null
            } else {
                line.substring(0, colon).trim().lowercase(Locale.US) to
                    line.substring(colon + 1).trim()
            }
        }.toMap()
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun writeClipboard(text: String): Boolean {
        val latch = CountDownLatch(1)
        var succeeded = false

        mainHandler.post {
            try {
                val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                ClipboardSyncCoordinator.rememberRemoteClipboardWrite(text)
                clipboard.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
                succeeded = true
            } catch (error: Exception) {
                Log.w(TAG, "Android clipboard write failed", error)
            } finally {
                latch.countDown()
            }
        }

        latch.await(CLIPBOARD_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return succeeded
    }

    private fun response(
        status: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = mutableListOf(
            "HTTP/1.1 $status",
            "Content-Type: text/plain; charset=utf-8",
            "Content-Length: ${bodyBytes.size}",
            "Connection: close"
        )
        extraHeaders.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                headers += "${name.trim()}: ${value.trim()}"
            }
        }
        headers += ""
        headers += body
        return headers.joinToString("\r\n")
    }

    private class HTTPFailure(val status: String, override val message: String) : Exception(message)

    companion object {
        const val DEFAULT_PORT = 9877
        const val CLIPBOARD_PATH = "/clipboard"

        private const val TAG = "ZevClipAndroidReceiver"
        private const val TOKEN_HEADER = "x-zevclip-token"
        private const val ANDROID_DEVICE_ID_HEADER = "X-ZevClip-Android-Device-ID"
        private const val ANDROID_RECEIVER_NAME_HEADER = "X-ZevClip-Android-Receiver-Name"
        private const val CLIPBOARD_LABEL = "ZevClip"
        private const val HEADER_END = "\r\n\r\n"
        private const val MAX_HEADER_LENGTH = 16_384
        private const val MAX_BODY_LENGTH = 1_048_576
        private const val READ_TIMEOUT_MS = 8_000
        private const val CLIPBOARD_WRITE_TIMEOUT_MS = 2_000L
    }
}
