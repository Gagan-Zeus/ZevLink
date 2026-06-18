package com.zevclip.sender

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.content.Intent
import android.util.Log

class FileTransferShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            Log.i(TAG, "No files were shared.")
            finishAndRemoveTask()
            return
        }

        FileTransferSendService.start(this, intent, uris)
        finishAndRemoveTask()
    }

    private fun sharedUris(intent: Intent?): List<Uri> {
        return when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::listOf).orEmpty()
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
    }

    companion object {
        private const val TAG = "ZevLinkFileShare"
    }
}
