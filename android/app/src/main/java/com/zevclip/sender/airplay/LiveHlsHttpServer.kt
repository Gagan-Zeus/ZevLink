package com.zevclip.sender.airplay

import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LiveHlsHttpServer(
    private val segmenter: LiveHlsSegmenter
) : Closeable {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        if (running.get()) return port
        val socket = ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
        serverSocket = socket
        running.set(true)
        Log.i(TAG, "HLS server listening on port ${socket.localPort}")
        worker = thread(name = "zevclip-airplay-hls", isDaemon = true) {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "zevclip-airplay-hls-client", isDaemon = true) {
                    handle(client)
                }
            }
        }
        return socket.localPort
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine().orEmpty()
            val method = requestLine.split(' ').getOrNull(0).orEmpty()
            while (reader.readLine().orEmpty().isNotEmpty()) {
                // Drain headers.
            }
            val path = requestLine.split(' ').getOrNull(1)?.toRequestPath() ?: "/"
            when {
                path == "/" || path == PLAYLIST_PATH -> {
                    val bytes = segmenter.playlist().toByteArray(Charsets.UTF_8)
                    Log.i(TAG, "HLS playlist request from ${client.inetAddress.hostAddress}: ${bytes.size} bytes")
                    client.writeResponse(method, "200 OK", "application/vnd.apple.mpegurl", bytes)
                }
                path.startsWith("/seg") && path.endsWith(".ts") -> {
                    val index = path.removePrefix("/seg").removeSuffix(".ts").toIntOrNull()
                    val segment = index?.let { segmenter.segment(it) }
                    if (segment == null) {
                        Log.w(TAG, "HLS missing segment request from ${client.inetAddress.hostAddress}: $path")
                        client.writeResponse(method, "404 Not Found", "text/plain", "missing segment".toByteArray())
                    } else {
                        Log.i(TAG, "HLS segment request from ${client.inetAddress.hostAddress}: $path ${segment.size} bytes")
                        client.writeResponse(method, "200 OK", "video/mp2t", segment)
                    }
                }
                else -> {
                    Log.w(TAG, "HLS unknown request from ${client.inetAddress.hostAddress}: $path")
                    client.writeResponse(method, "404 Not Found", "text/plain", "not found".toByteArray())
                }
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object {
        const val PLAYLIST_PATH = "/live.m3u8"
        private const val TAG = "ZevClipAirPlayHls"
    }
}

private fun String.toRequestPath(): String {
    val withoutQuery = substringBefore('?')
    if (!withoutQuery.startsWith("http://", ignoreCase = true) &&
        !withoutQuery.startsWith("https://", ignoreCase = true)
    ) {
        return withoutQuery
    }
    return runCatching { URI(withoutQuery).rawPath }.getOrNull().orEmpty().ifBlank { "/" }
}

private fun Socket.writeResponse(method: String, status: String, contentType: String, body: ByteArray) {
    val output = getOutputStream()
    val header = buildString {
        append("HTTP/1.1 ")
        append(status)
        append("\r\n")
        append("Connection: close\r\n")
        append("Cache-Control: no-cache\r\n")
        append("Pragma: no-cache\r\n")
        append("Access-Control-Allow-Origin: *\r\n")
        append("Content-Type: ")
        append(contentType)
        append("\r\n")
        append("Content-Length: ")
        append(body.size)
        append("\r\n\r\n")
    }
    output.write(header.toByteArray(Charsets.US_ASCII))
    if (!method.equals("HEAD", ignoreCase = true)) {
        output.write(body)
    }
    output.flush()
}
