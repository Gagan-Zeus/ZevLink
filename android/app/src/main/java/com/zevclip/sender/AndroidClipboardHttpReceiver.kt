package com.zevclip.sender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import com.zevclip.sender.filetransfer.FileTransferJson
import com.zevclip.sender.filetransfer.FileTransferManifest
import com.zevclip.sender.filetransfer.FileTransferOfferResponse
import com.zevclip.sender.filetransfer.FileTransferReceiver
import com.zevclip.sender.filetransfer.AndroidPublicDownloadsPublisher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidClipboardHttpReceiver(
    context: Context,
    private val port: Int = DEFAULT_PORT,
    private val tokenProvider: () -> String = { ZevClipPreferences.pairingToken(context) },
    private val onReady: (Int) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val onTextReceived: (String) -> Unit = {},
    private val onNotificationAction: (String, String?, String?) -> Boolean = { _, _, _ -> false },
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
    private val fileTransferReceiver = FileTransferReceiver(
        File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir, "ZevLink Transfers")
    )
    private val pendingIncomingOffers = ConcurrentHashMap<String, FileTransferManifest>()
    private val acceptedIncomingOffers = ConcurrentHashMap<String, FileTransferOfferResponse>()
    private val declinedIncomingOffers = ConcurrentHashMap.newKeySet<String>()

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

            while (!client.isClosed) {
            val headerBytes = try {
                readHeaders(input)
            } catch (_: SocketTimeoutException) {
                return
            } catch (_: SocketException) {
                return
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
                output.flush()
                continue
            }

            if (requestParts[0] != "POST") {
                output.write(response("405 Method Not Allowed", "Use POST.").toByteArray(Charsets.UTF_8))
                return
            }

            if (
                requestParts[1] != CLIPBOARD_PATH &&
                requestParts[1] != NOTIFICATION_ACTION_PATH &&
                requestParts[1] != CALL_ACTION_PATH &&
                requestParts[1] != MEDIA_CONTROL_PATH &&
                !requestParts[1].startsWith(TRANSFER_PREFIX)
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
                output.write(response("413 Content Too Large", "Request body is too large.").toByteArray(Charsets.UTF_8))
                return
            }

            if (requestParts[1] == TRANSFER_CHUNK_PATH) {
                output.write(
                    handleFileTransferChunk(headers, input, contentLength)
                        .toByteArray(Charsets.UTF_8)
                )
                output.flush()
                continue
            }

            val body = try {
                readExactly(input, contentLength)
            } catch (error: HTTPFailure) {
                output.write(response(error.status, error.message).toByteArray(Charsets.UTF_8))
                return
            }

            if (requestParts[1] == NOTIFICATION_ACTION_PATH) {
                output.write(handleNotificationAction(body).toByteArray(Charsets.UTF_8))
                output.flush()
                continue
            }

            if (requestParts[1] == CALL_ACTION_PATH) {
                output.write(handleCallAction(body).toByteArray(Charsets.UTF_8))
                output.flush()
                continue
            }

            if (requestParts[1] == MEDIA_CONTROL_PATH) {
                output.write(handleMediaControlAction(body).toByteArray(Charsets.UTF_8))
                output.flush()
                continue
            }

            if (requestParts[1].startsWith(TRANSFER_PREFIX)) {
                output.write(handleFileTransfer(requestParts[1], headers, body).toByteArray(Charsets.UTF_8))
                output.flush()
                continue
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
            output.flush()
            }
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

        val parsedAction = try {
            val json = JSONObject(bodyText)
            val action = json.optString("action").trim()
            if (action != "dismiss" && action != "perform") {
                return response("400 Bad Request", "Unsupported notification action.")
            }
            ParsedNotificationAction(
                action = action,
                notificationKey = json.optString("notificationKey").trim(),
                actionId = json.optString("actionId").trim().takeUnless { it.isEmpty() },
                replyText = json.optString("replyText").takeUnless { it.isEmpty() }
            )
        } catch (_: Exception) {
            return response("400 Bad Request", "Request body must be valid notification action JSON.")
        }

        if (parsedAction.notificationKey.isEmpty()) {
            return response("400 Bad Request", "notificationKey is required.")
        }
        if (parsedAction.action == "perform" && parsedAction.actionId.isNullOrBlank()) {
            return response("400 Bad Request", "actionId is required.")
        }

        return if (onNotificationAction(parsedAction.notificationKey, parsedAction.actionId, parsedAction.replyText)) {
            val message = if (parsedAction.action == "dismiss") {
                "Android notification cancelled."
            } else {
                "Android notification action performed."
            }
            response("200 OK", message)
        } else {
            response("404 Not Found", "Android notification action could not be performed.")
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

    private fun handleFileTransfer(path: String, headers: Map<String, String>, body: ByteArray): String {
        return try {
            when (path) {
                TRANSFER_OFFER_PATH -> {
                    val manifest = FileTransferJson.manifestFromJson(JSONObject(String(body, Charsets.UTF_8)))
                    acceptedIncomingOffers[manifest.transferId]?.let { accepted ->
                        return response(
                            status = "200 OK",
                            body = FileTransferJson.offerResponseToJson(accepted).toString(),
                            contentType = "application/json; charset=utf-8"
                        )
                    }
                    if (declinedIncomingOffers.contains(manifest.transferId)) {
                        return response("403 Forbidden", "Incoming file transfer was declined.")
                    }
                    if (!ZevClipPreferences.isFileTransferAutoAcceptEnabled(appContext)) {
                        if (pendingIncomingOffers.putIfAbsent(manifest.transferId, manifest) == null) {
                            registerPendingTransfer(manifest)
                        }
                        pendingOfferResponse(manifest.transferId)
                    } else {
                        acceptedOfferResponse(acceptIncomingManifest(manifest))
                    }
                }
                TRANSFER_OFFER_STATUS_PATH -> {
                    val transferId = transferId(headers, body)
                    acceptedIncomingOffers[transferId]?.let { accepted ->
                        return acceptedOfferResponse(accepted)
                    }
                    if (declinedIncomingOffers.remove(transferId)) {
                        return response("403 Forbidden", "Incoming file transfer was declined.")
                    }
                    if (pendingIncomingOffers.containsKey(transferId)) {
                        pendingOfferResponse(transferId)
                    } else {
                        response("404 Not Found", "Incoming file transfer offer was not found.")
                    }
                }
                TRANSFER_RANGES_PATH -> {
                    val transferId = transferId(headers, body)
                    response(
                        status = "200 OK",
                        body = FileTransferJson.rangesToJson(fileTransferReceiver.verifiedRanges(transferId)).toString(),
                        contentType = "application/json; charset=utf-8"
                    )
                }
                TRANSFER_CHUNK_PATH -> {
                    val transferId = requiredHeader(headers, "x-zevlink-transfer-id")
                    val fileId = requiredHeader(headers, "x-zevlink-file-id")
                    val chunkIndex = requiredHeader(headers, "x-zevlink-chunk-index").toLong()
                    val chunkHash = headers["x-zevlink-chunk-sha256"]?.takeUnless { it.isBlank() }
                    fileTransferReceiver.writeChunk(
                        transferId = transferId,
                        fileId = fileId,
                        chunkIndex = chunkIndex,
                        data = body,
                        chunkSha256 = chunkHash,
                        activeStreamCount = 4
                    )
                    updateReceivingNotification(transferId)
                    response("200 OK", "Chunk accepted.")
                }
                TRANSFER_COMPLETE_PATH -> {
                    val transferId = transferId(headers, body)
                    val completed = fileTransferReceiver.complete(transferId, verifyFileHashes = false)
                    acceptedIncomingOffers.remove(transferId)
                    val published = AndroidPublicDownloadsPublisher.publish(appContext, completed)
                    val openUri = published.fileUris.firstOrNull()
                    FileTransferNotificationCenter.showComplete(
                        context = appContext,
                        transferId = completed.transferId,
                        fileName = transferTitle(completed.files.map { it.file.name }),
                        direction = FileTransferNotificationCenter.Direction.RECEIVING,
                        openUri = openUri,
                        mimeType = openUri?.let { appContext.contentResolver.getType(it) }
                    )
                    response(
                        status = "200 OK",
                        body = JSONObject()
                            .put("transferId", completed.transferId)
                            .put("destination", published.publicDirectory)
                            .put("publishedFileCount", published.fileUris.size)
                            .toString(),
                        contentType = "application/json; charset=utf-8"
                    )
                }
                TRANSFER_CANCEL_PATH -> {
                    val transferId = transferId(headers, body)
                    pendingIncomingOffers.remove(transferId)
                    acceptedIncomingOffers.remove(transferId)
                    declinedIncomingOffers.remove(transferId)
                    FileTransferNotificationCenter.cancelTransfer(appContext, transferId)
                    fileTransferReceiver.cancel(transferId)
                    response("200 OK", "Transfer cancelled.")
                }
                else -> response("404 Not Found", "Unknown transfer path.")
            }
        } catch (error: Exception) {
            Log.w(TAG, "File transfer request failed", error)
            response("400 Bad Request", error.message ?: "File transfer failed.")
        }
    }

    private fun registerPendingTransfer(manifest: FileTransferManifest) {
        val fileName = transferTitle(manifest.fileEntries.map { it.relativePath.substringAfterLast('/') })
        FileTransferNotificationCenter.registerCancelCallback(manifest.transferId) {
            pendingIncomingOffers.remove(manifest.transferId)
            acceptedIncomingOffers.remove(manifest.transferId)
            declinedIncomingOffers.remove(manifest.transferId)
            fileTransferReceiver.cancel(manifest.transferId)
        }
        FileTransferNotificationCenter.registerApprovalCallbacks(
            transferId = manifest.transferId,
            onAccept = {
                connectionExecutor.execute {
                    val pending = pendingIncomingOffers[manifest.transferId] ?: return@execute
                    runCatching { acceptIncomingManifest(pending) }
                        .onFailure { error ->
                            pendingIncomingOffers.remove(manifest.transferId)
                            declinedIncomingOffers.add(manifest.transferId)
                            FileTransferNotificationCenter.showFailed(
                                context = appContext,
                                transferId = manifest.transferId,
                                fileName = fileName,
                                direction = FileTransferNotificationCenter.Direction.RECEIVING,
                                message = error.message ?: "Could not accept file transfer."
                            )
                        }
                }
            },
            onDecline = {
                pendingIncomingOffers.remove(manifest.transferId)
                declinedIncomingOffers.add(manifest.transferId)
                FileTransferNotificationCenter.unregisterCancelCallback(manifest.transferId)
            }
        )
        FileTransferNotificationCenter.showApproval(
            context = appContext,
            transferId = manifest.transferId,
            fileName = fileName,
            senderName = manifest.senderName,
            totalBytes = manifest.totalBytes
        )
    }

    private fun acceptIncomingManifest(manifest: FileTransferManifest): FileTransferOfferResponse {
        val offer = fileTransferReceiver.accept(
            manifest = manifest,
            requestedStreamCount = manifest.requestedStreamCount,
            receiverMaximumStreams = com.zevclip.sender.filetransfer.ZevLinkTransferProtocol.MAX_STREAM_COUNT
        )
        pendingIncomingOffers.remove(manifest.transferId)
        declinedIncomingOffers.remove(manifest.transferId)
        acceptedIncomingOffers[manifest.transferId] = offer
        FileTransferNotificationCenter.unregisterApprovalCallbacks(manifest.transferId)
        FileTransferNotificationCenter.registerCancelCallback(manifest.transferId) {
            acceptedIncomingOffers.remove(manifest.transferId)
            fileTransferReceiver.cancel(manifest.transferId)
        }
        FileTransferNotificationCenter.showActive(
            context = appContext,
            transferId = manifest.transferId,
            fileName = transferTitle(manifest.fileEntries.map { it.relativePath.substringAfterLast('/') }),
            direction = FileTransferNotificationCenter.Direction.RECEIVING,
            transferredBytes = 0L,
            totalBytes = manifest.totalBytes
        )
        return offer
    }

    private fun acceptedOfferResponse(offer: FileTransferOfferResponse): String {
        return response(
            status = "200 OK",
            body = FileTransferJson.offerResponseToJson(offer).toString(),
            contentType = "application/json; charset=utf-8"
        )
    }

    private fun pendingOfferResponse(transferId: String): String {
        return response(
            status = "202 Accepted",
            body = JSONObject()
                .put("transferId", transferId)
                .put("status", "pending")
                .put("retryAfterMillis", 500)
                .toString(),
            contentType = "application/json; charset=utf-8"
        )
    }

    private fun handleFileTransferChunk(
        headers: Map<String, String>,
        inputStream: InputStream,
        contentLength: Int
    ): String {
        return try {
            val transferId = requiredHeader(headers, "x-zevlink-transfer-id")
            val fileId = requiredHeader(headers, "x-zevlink-file-id")
            val chunkIndex = requiredHeader(headers, "x-zevlink-chunk-index").toLong()
            val chunkHash = headers["x-zevlink-chunk-sha256"]?.takeUnless { it.isBlank() }
            fileTransferReceiver.writeChunkStream(
                transferId = transferId,
                fileId = fileId,
                chunkIndex = chunkIndex,
                inputStream = inputStream,
                contentLength = contentLength.toLong(),
                chunkSha256 = chunkHash,
                activeStreamCount = 8
            )
            updateReceivingNotification(transferId)
            response("200 OK", "Chunk accepted.")
        } catch (error: Exception) {
            Log.w(TAG, "Streaming file chunk failed", error)
            response("400 Bad Request", error.message ?: "File transfer failed.")
        }
    }

    private fun updateReceivingNotification(transferId: String) {
        val manifest = fileTransferReceiver.manifest(transferId)
        val progress = fileTransferReceiver.progress(transferId)
        FileTransferNotificationCenter.showActive(
            context = appContext,
            transferId = transferId,
            fileName = transferTitle(manifest.fileEntries.map { it.relativePath.substringAfterLast('/') }),
            direction = FileTransferNotificationCenter.Direction.RECEIVING,
            transferredBytes = progress.verifiedBytes,
            totalBytes = progress.totalBytes
        )
    }

    private fun transferTitle(fileNames: List<String>): String {
        val first = fileNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: "File transfer"
        return if (fileNames.size <= 1) first else "$first + ${fileNames.size - 1} more"
    }

    private fun transferId(headers: Map<String, String>, body: ByteArray): String {
        return headers["x-zevlink-transfer-id"]?.takeUnless { it.isBlank() }
            ?: FileTransferJson.transferIdFromJson(body)
    }

    private fun requiredHeader(headers: Map<String, String>, name: String): String {
        return headers[name]?.takeUnless { it.isBlank() } ?: error("$name is required.")
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
            "Connection: keep-alive"
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

    private data class ParsedNotificationAction(
        val action: String,
        val notificationKey: String,
        val actionId: String?,
        val replyText: String?
    )

    companion object {
        const val DEFAULT_PORT = 9877
        const val CLIPBOARD_PATH = "/clipboard"
        const val STATUS_PATH = "/status"
        const val NOTIFICATION_ACTION_PATH = "/notification-action"
        const val CALL_ACTION_PATH = "/call-action"
        const val MEDIA_CONTROL_PATH = "/media-control"
        const val TRANSFER_PREFIX = "/transfer/"
        const val TRANSFER_OFFER_PATH = "/transfer/offer"
        const val TRANSFER_OFFER_STATUS_PATH = "/transfer/offer-status"
        const val TRANSFER_RANGES_PATH = "/transfer/ranges"
        const val TRANSFER_CHUNK_PATH = "/transfer/chunk"
        const val TRANSFER_COMPLETE_PATH = "/transfer/complete"
        const val TRANSFER_CANCEL_PATH = "/transfer/cancel"

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
        private const val MAX_BODY_LENGTH = 20 * 1024 * 1024
        private const val READ_TIMEOUT_MS = 8_000
        private const val CLIPBOARD_WRITE_TIMEOUT_MS = 2_000L
    }
}
