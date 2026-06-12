package com.zevclip.sender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
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
    private val onTextReceived: (String) -> Unit = {},
    private val onNotificationAction: (String) -> Boolean = { false },
    private val onCallAction: (String, String?) -> AndroidCallActionResult = { _, _ ->
        AndroidCallActionResult(false, "Android call mirror is unavailable.")
    },
    private val onMediaControlAction: (String) -> Boolean = { false }
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

            val headers = parseHeaders(lines.drop(1))
            val expectedToken = tokenProvider().trim()
            if (expectedToken.isEmpty() || headers[TOKEN_HEADER] != expectedToken) {
                output.write(response("401 Unauthorized", "Missing or invalid ZevLink pairing token.").toByteArray(Charsets.UTF_8))
                return
            }

            rememberMacBatteryStatus(headers)

            if (requestParts[0] == "GET" && requestParts[1] == STATUS_PATH) {
                output.write(statusResponse().toByteArray(Charsets.UTF_8))
                return
            }

            if (requestParts[0] != "POST") {
                output.write(response("405 Method Not Allowed", "Use POST.").toByteArray(Charsets.UTF_8))
                return
            }

            if (
                requestParts[1] != CLIPBOARD_PATH &&
                requestParts[1] != NOTIFICATION_ACTION_PATH &&
                requestParts[1] != CALL_ACTION_PATH &&
                requestParts[1] != MEDIA_CONTROL_PATH
            ) {
                output.write(response("404 Not Found", "Unknown path.").toByteArray(Charsets.UTF_8))
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

            if (requestParts[1] == NOTIFICATION_ACTION_PATH) {
                output.write(handleNotificationAction(body).toByteArray(Charsets.UTF_8))
                return
            }

            if (requestParts[1] == CALL_ACTION_PATH) {
                output.write(handleCallAction(body).toByteArray(Charsets.UTF_8))
                return
            }

            if (requestParts[1] == MEDIA_CONTROL_PATH) {
                output.write(handleMediaControlAction(body).toByteArray(Charsets.UTF_8))
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
                    extraHeaders = mapOf(
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

    private fun statusResponse(): String {
        val batteryPercentage = currentBatteryPercentage()
        val body = """{"batteryPercentage":${batteryPercentage ?: "null"},"receiver":"running"}"""
        val headers = mutableMapOf(
            ANDROID_DEVICE_ID_HEADER to ZevClipPreferences.androidDeviceId(appContext),
            ANDROID_RECEIVER_NAME_HEADER to AndroidClipboardReceiverService.SERVICE_NAME
        )

        if (batteryPercentage != null) {
            headers[ANDROID_BATTERY_HEADER] = batteryPercentage.toString()
        }

        return response(
            status = "200 OK",
            body = body,
            contentType = "application/json; charset=utf-8",
            extraHeaders = headers
        )
    }

    private fun handleNotificationAction(body: ByteArray): String {
        val bodyText = try {
            decodeUtf8(body)
        } catch (_: Exception) {
            return response("400 Bad Request", "Request body must be valid UTF-8 JSON.")
        }

        val notificationKey = try {
            val json = JSONObject(bodyText)
            if (json.optString("action") != "dismiss") {
                return response("400 Bad Request", "Unsupported notification action.")
            }
            json.optString("notificationKey").trim()
        } catch (_: Exception) {
            return response("400 Bad Request", "Request body must be valid notification action JSON.")
        }

        if (notificationKey.isEmpty()) {
            return response("400 Bad Request", "notificationKey is required.")
        }

        return if (onNotificationAction(notificationKey)) {
            response("200 OK", "Android notification cancelled.")
        } else {
            response("404 Not Found", "Android notification could not be cancelled.")
        }
    }

    private fun handleCallAction(body: ByteArray): String {
        val bodyText = try {
            decodeUtf8(body)
        } catch (_: Exception) {
            return response("400 Bad Request", "Request body must be valid UTF-8 JSON.")
        }

        val parsedAction = try {
            val json = JSONObject(bodyText)
            val action = json.optString("action").trim().lowercase(Locale.US)
            val callId = json.optString("callId").trim().takeUnless { it.isEmpty() }
            action to callId
        } catch (_: Exception) {
            return response("400 Bad Request", "Request body must be valid call action JSON.")
        }

        val action = parsedAction.first
        if (action.isEmpty()) {
            return response("400 Bad Request", "action is required.")
        }

        val result = onCallAction(action, parsedAction.second)
        return if (result.success) {
            response("200 OK", result.message)
        } else {
            response("409 Conflict", result.message)
        }
    }

    private fun handleMediaControlAction(body: ByteArray): String {
        val bodyText = try {
            decodeUtf8(body)
        } catch (_: Exception) {
            return response("400 Bad Request", "Request body must be valid UTF-8 JSON.")
        }

        val action = try {
            JSONObject(bodyText)
                .optString("action")
                .trim()
                .lowercase(Locale.US)
        } catch (_: Exception) {
            return response("400 Bad Request", "Request body must be valid media control JSON.")
        }

        if (action.isEmpty()) {
            return response("400 Bad Request", "action is required.")
        }

        return if (onMediaControlAction(action)) {
            response("200 OK", "Android media action sent.")
        } else {
            response("409 Conflict", "Android media action could not be sent.")
        }
    }

    private fun currentBatteryPercentage(): Int? {
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val percentage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return percentage.takeIf { it in 0..100 }
    }

    private fun rememberMacBatteryStatus(headers: Map<String, String>) {
        val availabilityHeader = headers[MAC_BATTERY_AVAILABLE_HEADER] ?: return
        val isAvailable = availabilityHeader.equals("true", ignoreCase = true)
        val percentage = headers[MAC_BATTERY_HEADER]
            ?.toIntOrNull()
            ?.takeIf { it in 0..100 }
        val isCharging = headers[MAC_CHARGING_HEADER]
            ?.let { it.equals("true", ignoreCase = true) }

        ZevClipPreferences.setMacBatteryStatus(
            context = appContext,
            percentage = percentage,
            isCharging = isCharging,
            isAvailable = isAvailable
        )
        ZevClipStatusNotification.update(appContext)
    }

    private fun response(
        status: String,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = mutableListOf(
            "HTTP/1.1 $status",
            "Content-Type: $contentType",
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
        const val STATUS_PATH = "/status"
        const val NOTIFICATION_ACTION_PATH = "/notification-action"
        const val CALL_ACTION_PATH = "/call-action"
        const val MEDIA_CONTROL_PATH = "/media-control"

        private const val TAG = "ZevClipAndroidReceiver"
        private const val TOKEN_HEADER = "x-zevclip-token"
        private const val ANDROID_DEVICE_ID_HEADER = "X-ZevClip-Android-Device-ID"
        private const val ANDROID_RECEIVER_NAME_HEADER = "X-ZevClip-Android-Receiver-Name"
        private const val ANDROID_BATTERY_HEADER = "X-ZevClip-Android-Battery"
        private const val MAC_BATTERY_AVAILABLE_HEADER = "x-zevclip-mac-battery-available"
        private const val MAC_BATTERY_HEADER = "x-zevclip-mac-battery"
        private const val MAC_CHARGING_HEADER = "x-zevclip-mac-charging"
        private const val CLIPBOARD_LABEL = "ZevLink"
        private const val HEADER_END = "\r\n\r\n"
        private const val MAX_HEADER_LENGTH = 16_384
        private const val MAX_BODY_LENGTH = 1_048_576
        private const val READ_TIMEOUT_MS = 8_000
        private const val CLIPBOARD_WRITE_TIMEOUT_MS = 2_000L
    }
}
