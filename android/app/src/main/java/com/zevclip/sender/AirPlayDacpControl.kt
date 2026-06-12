package com.zevclip.sender

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.zevclip.sender.airplay.AirPlayIdentity
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class AirPlayDacpSession(
    val dacpId: String,
    val activeRemote: String = "1",
    val senderName: String
) {
    companion object {
        fun fromIdentity(identity: AirPlayIdentity): AirPlayDacpSession {
            return AirPlayDacpSession(
                dacpId = identity.deviceId
                    .filter { it.isLetterOrDigit() }
                    .take(16)
                    .uppercase(Locale.US),
                senderName = identity.senderName
            )
        }
    }
}

enum class AirPlayDacpCommand {
    PlayPause,
    Play,
    Pause,
    Next,
    Previous
}

class AirPlayDacpControlServer(
    context: Context,
    private val session: AirPlayDacpSession,
    private val onCommand: (AirPlayDacpCommand) -> Unit
) : Closeable {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registrationLatch: CountDownLatch? = null

    fun start(): Int {
        if (running.get()) return serverSocket?.localPort ?: 0

        val socket = ServerSocket(0)
        serverSocket = socket
        running.set(true)

        serverThread = thread(name = "zevclip-dacp-control", isDaemon = true) {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "zevclip-dacp-client", isDaemon = true) {
                    runCatching { handleClient(client) }
                        .onFailure { Log.w(TAG, "DACP command handling failed", it) }
                    runCatching { client.close() }
                }
            }
        }

        registerService(socket.localPort)
        registrationLatch?.await(REGISTRATION_WAIT_MS, TimeUnit.MILLISECONDS)
        return socket.localPort
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = REQUEST_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine().orEmpty()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val method = requestLine.split(' ').getOrNull(0).orEmpty()
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        Log.i(TAG, "DACP request: $method $path")

        val response = responseForPath(path)
        socket.getOutputStream().use { output ->
            output.write(response)
            output.flush()
        }
    }

    private fun responseForPath(path: String): ByteArray {
        val command = commandForPath(path)
        if (command != null) {
            Log.i(TAG, "Received DACP command: $command")
            val handled = runCatching { onCommand(command) }.isSuccess
            return emptyResponse(if (handled) "204 No Content" else "500 Internal Server Error")
        }

        val cleanPath = path.substringBefore('?').trim('/').lowercase(Locale.US)
        return when (cleanPath) {
            "login" -> dmapResponse(loginBody())
            "server-info" -> dmapResponse(serverInfoBody())
            "content-codes" -> dmapResponse(contentCodesBody())
            "ctrl-int/1/playstatusupdate" -> dmapResponse(playStatusBody())
            "ctrl-int/1/nowplayingartwork" -> emptyResponse("204 No Content", "image/jpeg")
            else -> emptyResponse("404 Not Found")
        }
    }

    private fun commandForPath(path: String): AirPlayDacpCommand? {
        val command = path.substringBefore('?')
            .trim('/')
            .substringAfterLast('/')
            .lowercase(Locale.US)

        return when (command) {
            "playpause" -> AirPlayDacpCommand.PlayPause
            "play" -> AirPlayDacpCommand.Play
            "pause" -> AirPlayDacpCommand.Pause
            "nextitem" -> AirPlayDacpCommand.Next
            "previtem" -> AirPlayDacpCommand.Previous
            else -> null
        }
    }

    private fun registerService(port: Int) {
        val manager = appContext.getSystemService(NsdManager::class.java) ?: return
        val latch = CountDownLatch(1)
        registrationLatch = latch
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "iTunes_Ctrl_${session.dacpId}"
            serviceType = DACP_SERVICE_TYPE
            this.port = port
            setAttribute("txtvers", "1")
            setAttribute("Ver", "131075")
            setAttribute("DbId", session.dacpId)
            setAttribute("DvNm", session.senderName)
            setAttribute("CtlN", session.senderName)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "DACP registered as ${serviceInfo.serviceName}:${serviceInfo.port}")
                latch.countDown()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "DACP registration failed: $errorCode")
                latch.countDown()
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "DACP unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "DACP unregistration failed: $errorCode")
            }
        }
        registrationListener = listener

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, appContext.mainExecutor, listener)
        } else {
            @Suppress("DEPRECATION")
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    override fun close() {
        running.set(false)
        val manager = appContext.getSystemService(NsdManager::class.java)
        registrationListener?.let { listener ->
            runCatching { manager?.unregisterService(listener) }
        }
        registrationListener = null
        registrationLatch = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
    }

    private companion object {
        private const val TAG = "ZevClipDACP"
        private const val DACP_SERVICE_TYPE = "_dacp._tcp."
        private const val REQUEST_TIMEOUT_MS = 2_000
        private const val REGISTRATION_WAIT_MS = 1_500L

        private fun emptyResponse(status: String, contentType: String? = null): ByteArray {
            val headers = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n\r\n")
            }
            return headers.toByteArray(Charsets.US_ASCII)
        }

        private fun dmapResponse(body: ByteArray): ByteArray {
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/x-dmap-tagged\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            return headers + body
        }

        private fun loginBody(): ByteArray {
            return dmapContainer(
                "mlog",
                dmapUInt32("mstt", 200) +
                    dmapUInt32("mlid", 1)
            )
        }

        private fun serverInfoBody(): ByteArray {
            return dmapContainer(
                "msrv",
                dmapUInt32("mstt", 200) +
                    dmapUInt32("mpro", 0x0002000a) +
                    dmapUInt32("apro", 0x0003000c) +
                    dmapString("minm", "ZevLink") +
                    dmapUInt32("msal", 1) +
                    dmapUInt32("mslr", 1) +
                    dmapUInt32("mstm", 1800)
            )
        }

        private fun contentCodesBody(): ByteArray {
            return dmapContainer("mccr", dmapUInt32("mstt", 200))
        }

        private fun playStatusBody(): ByteArray {
            return dmapContainer(
                "cmst",
                dmapUInt32("mstt", 200) +
                    dmapUInt32("cmsr", statusRevisionSeed()) +
                    dmapUInt32("caps", 4) +
                    dmapUInt32("cash", 0) +
                    dmapUInt32("carp", 0) +
                    dmapUInt32("cavc", 100)
            )
        }

        private fun statusRevisionSeed(): Int {
            return ((System.currentTimeMillis() / 1_000L) and 0x7FFFFFFF).toInt()
        }

        private fun dmapContainer(tag: String, payload: ByteArray): ByteArray = dmapTag(tag, payload)

        private fun dmapString(tag: String, value: String): ByteArray {
            return dmapTag(tag, value.toByteArray(Charsets.UTF_8))
        }

        private fun dmapUInt32(tag: String, value: Int): ByteArray {
            return dmapTag(
                tag,
                byteArrayOf(
                    ((value ushr 24) and 0xFF).toByte(),
                    ((value ushr 16) and 0xFF).toByte(),
                    ((value ushr 8) and 0xFF).toByte(),
                    (value and 0xFF).toByte()
                )
            )
        }

        private fun dmapTag(tag: String, payload: ByteArray): ByteArray {
            require(tag.length == 4) { "DMAP tags must be four ASCII characters." }
            val output = ByteArrayOutputStream(8 + payload.size)
            output.write(tag.toByteArray(Charsets.US_ASCII))
            output.write((payload.size ushr 24) and 0xFF)
            output.write((payload.size ushr 16) and 0xFF)
            output.write((payload.size ushr 8) and 0xFF)
            output.write(payload.size and 0xFF)
            output.write(payload)
            return output.toByteArray()
        }
    }
}

object AirPlayMediaControlDispatcher {
    fun dispatch(context: Context, command: AirPlayDacpCommand): Boolean {
        val appContext = context.applicationContext
        val controller = AndroidNowPlayingReader.activeController(appContext)
        if (controller != null && dispatchToController(controller, command)) {
            return true
        }
        return dispatchMediaKey(appContext, command)
    }

    private fun dispatchToController(controller: MediaController, command: AirPlayDacpCommand): Boolean {
        val controls = controller.transportControls ?: return false
        when (command) {
            AirPlayDacpCommand.PlayPause -> {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controls.pause()
                } else {
                    controls.play()
                }
            }
            AirPlayDacpCommand.Play -> controls.play()
            AirPlayDacpCommand.Pause -> controls.pause()
            AirPlayDacpCommand.Next -> controls.skipToNext()
            AirPlayDacpCommand.Previous -> controls.skipToPrevious()
        }
        return true
    }

    private fun dispatchMediaKey(context: Context, command: AirPlayDacpCommand): Boolean {
        val keyCode = when (command) {
            AirPlayDacpCommand.PlayPause -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            AirPlayDacpCommand.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
            AirPlayDacpCommand.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
            AirPlayDacpCommand.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
            AirPlayDacpCommand.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        val eventTime = System.currentTimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
        return true
    }
}
