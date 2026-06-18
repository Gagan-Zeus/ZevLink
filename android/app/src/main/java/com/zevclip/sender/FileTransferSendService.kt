package com.zevclip.sender

import android.app.Activity
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.zevclip.sender.filetransfer.ContentResolverFileTransferSource
import com.zevclip.sender.filetransfer.FileTransferAndroidSender
import com.zevclip.sender.filetransfer.MacFileTransferHttpClient
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class FileTransferSendService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeTransferCount = AtomicInteger(0)
    private val cancellations = Collections.synchronizedSet(mutableSetOf<AtomicBoolean>())
    private val discoveries = Collections.synchronizedSet(mutableSetOf<MacDiscoveryManager>())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            Log.i(TAG, "No files were shared.")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        activeTransferCount.incrementAndGet()
        val cancelled = AtomicBoolean(false)
        val endpointResolved = AtomicBoolean(false)
        cancellations += cancelled

        lateinit var discovery: MacDiscoveryManager
        val timeout = Runnable {
            if (endpointResolved.compareAndSet(false, true)) {
                Log.w(TAG, "No ZevLink Mac receiver found for file transfer.")
                discovery.stop()
                discoveries -= discovery
                finishTransfer(startId, cancelled)
            }
        }

        discovery = MacDiscoveryManager(
            context = this,
            onStatusChanged = { status, _ -> Log.d(TAG, status) },
            onEndpointResolved = { host, port ->
                if (endpointResolved.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    discovery.stop()
                    discoveries -= discovery
                    startSend(startId, host, port, uris, cancelled)
                }
            }
        )

        discoveries += discovery
        discovery.discover()
        mainHandler.postDelayed(timeout, DISCOVERY_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        discoveries.toList().forEach { it.stop() }
        discoveries.clear()
        cancellations.toList().forEach { it.set(true) }
        cancellations.clear()
        super.onDestroy()
    }

    private fun startSend(
        startId: Int,
        host: String,
        port: Int,
        uris: List<Uri>,
        cancelled: AtomicBoolean
    ) {
        val token = ZevClipPreferences.pairingToken(this)
        if (token.isBlank()) {
            Log.i(TAG, "Pair with the Mac before sending files.")
            finishTransfer(startId, cancelled)
            return
        }

        thread(name = "ZevLinkFileShareSend") {
            var activeTransferId: String? = null
            var activeTitle = "File transfer"
            try {
                val sources = uris.map { uri ->
                    ContentResolverFileTransferSource(contentResolver, uri)
                }
                val manifest = FileTransferAndroidSender().manifest(
                    senderDeviceId = ZevClipPreferences.androidDeviceId(this),
                    senderName = Build.MODEL ?: "Android",
                    sources = sources,
                    requestedStreamCount = 8
                )
                val title = transferTitle(sources.map { it.displayName })
                activeTransferId = manifest.transferId
                activeTitle = title
                FileTransferNotificationCenter.registerCancelCallback(manifest.transferId) {
                    cancelled.set(true)
                }
                FileTransferNotificationCenter.showActive(
                    context = this,
                    transferId = manifest.transferId,
                    fileName = title,
                    direction = FileTransferNotificationCenter.Direction.SENDING,
                    transferredBytes = 0L,
                    totalBytes = manifest.totalBytes
                )
                Log.i(TAG, "Sending ${sources.size} file(s) to Mac.")
                MacFileTransferHttpClient(host, port, token).send(
                    manifest = manifest,
                    sources = sources,
                    isCancelled = cancelled,
                    onProgress = { sent ->
                        FileTransferNotificationCenter.showActive(
                            context = this,
                            transferId = manifest.transferId,
                            fileName = title,
                            direction = FileTransferNotificationCenter.Direction.SENDING,
                            transferredBytes = sent,
                            totalBytes = manifest.totalBytes
                        )
                        Log.d(TAG, "Sent ${formatBytes(sent)} / ${formatBytes(manifest.totalBytes)}")
                    }
                )
                FileTransferNotificationCenter.showComplete(
                    context = this,
                    transferId = manifest.transferId,
                    fileName = title,
                    direction = FileTransferNotificationCenter.Direction.SENDING,
                    openUri = uris.firstOrNull(),
                    mimeType = uris.firstOrNull()?.let { contentResolver.getType(it) }
                )
                Log.i(TAG, "Sent ${sources.size} file(s) to Mac.")
            } catch (error: Exception) {
                if (cancelled.get()) {
                    Log.i(TAG, "File transfer cancelled.")
                    activeTransferId?.let { transferId ->
                        FileTransferNotificationCenter.clear(this, transferId)
                    }
                } else {
                    activeTransferId?.let { transferId ->
                        FileTransferNotificationCenter.showFailed(
                            context = this,
                            transferId = transferId,
                            fileName = activeTitle,
                            direction = FileTransferNotificationCenter.Direction.SENDING,
                            message = error.message ?: "unknown error"
                        )
                    }
                    Log.w(TAG, "File transfer failed: ${error.message ?: "unknown error"}", error)
                }
            } finally {
                finishTransfer(startId, cancelled)
            }
        }
    }

    private fun finishTransfer(startId: Int, cancelled: AtomicBoolean) {
        cancellations -= cancelled
        if (activeTransferCount.decrementAndGet() <= 0) {
            stopSelf(startId)
        }
    }

    private fun sharedUris(intent: Intent?): List<Uri> {
        val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableArrayListExtra(EXTRA_URIS)
        }
        return uris.orEmpty()
    }

    private fun formatBytes(bytes: Long): String {
        return android.text.format.Formatter.formatFileSize(this, bytes)
    }

    private fun transferTitle(fileNames: List<String>): String {
        val first = fileNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: "File transfer"
        return if (fileNames.size <= 1) first else "$first + ${fileNames.size - 1} more"
    }

    companion object {
        private const val TAG = "ZevLinkFileShare"
        private const val EXTRA_URIS = "com.zevclip.sender.extra.FILE_TRANSFER_URIS"
        private const val DISCOVERY_TIMEOUT_MS = 12_000L

        fun start(activity: Activity, sourceIntent: Intent?, uris: List<Uri>) {
            val serviceIntent = Intent(activity, FileTransferSendService::class.java).apply {
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = sourceIntent?.clipData ?: clipDataForUris(activity, uris)
            }
            activity.startService(serviceIntent)
        }

        private fun clipDataForUris(context: Context, uris: List<Uri>): ClipData? {
            val first = uris.firstOrNull() ?: return null
            return ClipData.newUri(context.contentResolver, "ZevLink shared file", first).apply {
                uris.drop(1).forEach { uri ->
                    addItem(ClipData.Item(uri))
                }
            }
        }
    }
}
