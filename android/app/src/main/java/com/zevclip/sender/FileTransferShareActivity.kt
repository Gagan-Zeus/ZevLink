package com.zevclip.sender

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.content.Intent
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.zevclip.sender.filetransfer.ContentResolverFileTransferSource
import com.zevclip.sender.filetransfer.FileTransferAndroidSender
import com.zevclip.sender.filetransfer.MacFileTransferHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class FileTransferShareActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private val cancelled = AtomicBoolean(false)
    private var discovery: MacDiscoveryManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            finishWithStatus("No files were shared.")
            return
        }

        statusText.text = "Finding Mac..."
        discovery = MacDiscoveryManager(
            context = this,
            onStatusChanged = { status, _ -> runOnUiThread { statusText.text = status } },
            onEndpointResolved = { host, port -> startSend(host, port, uris) }
        )
        discovery?.discover()
    }

    override fun onDestroy() {
        cancelled.set(true)
        discovery?.stop()
        super.onDestroy()
    }

    private fun buildUi() {
        statusText = TextView(this).apply {
            text = "Preparing file transfer..."
            textSize = 16f
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                cancelled.set(true)
                finishWithStatus("Cancelling...")
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(48, 48, 48, 48)
                addView(statusText)
                addView(progressBar)
                addView(cancel)
            }
        )
    }

    private fun startSend(host: String, port: Int, uris: List<Uri>) {
        discovery?.stop()
        val token = ZevClipPreferences.pairingToken(this)
        if (token.isBlank()) {
            finishWithStatus("Pair with the Mac before sending files.")
            return
        }

        thread(name = "ZevLinkFileShareSend") {
            try {
                val sources = uris.map { uri ->
                    ContentResolverFileTransferSource(contentResolver, uri)
                }
                val manifest = FileTransferAndroidSender().manifest(
                    senderDeviceId = ZevClipPreferences.androidDeviceId(this),
                    senderName = android.os.Build.MODEL ?: "Android",
                    sources = sources,
                    requestedStreamCount = 4
                )
                runOnUiThread {
                    progressBar.progress = 0
                    statusText.text = "Sending ${sources.size} file(s)..."
                }
                MacFileTransferHttpClient(host, port, token).send(
                    manifest = manifest,
                    sources = sources,
                    isCancelled = cancelled,
                    onProgress = { sent ->
                        runOnUiThread {
                            progressBar.progress = if (manifest.totalBytes == 0L) {
                                1000
                            } else {
                                ((sent.toDouble() / manifest.totalBytes.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
                            }
                            statusText.text = "Sent ${formatBytes(sent)} / ${formatBytes(manifest.totalBytes)}"
                        }
                    }
                )
                runOnUiThread { finishWithStatus("Sent ${sources.size} file(s) to Mac.") }
            } catch (error: Exception) {
                runOnUiThread { finishWithStatus("File transfer failed: ${error.message ?: "unknown error"}") }
            }
        }
    }

    private fun finishWithStatus(status: String) {
        statusText.text = status
        statusText.postDelayed({ finish() }, 1600)
    }

    private fun sharedUris(intent: Intent?): List<Uri> {
        return when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::listOf).orEmpty()
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
    }

    private fun formatBytes(bytes: Long): String {
        return android.text.format.Formatter.formatFileSize(this, bytes)
    }
}
