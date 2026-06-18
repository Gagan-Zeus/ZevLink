package com.zevclip.sender

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

class FileTransferNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return
                FileTransferNotificationCenter.cancelTransfer(context, transferId)
            }
        }
    }

    companion object {
        private const val ACTION_CANCEL = "com.zevclip.sender.action.FILE_TRANSFER_CANCEL"
        private const val ACTION_OPEN = "com.zevclip.sender.action.FILE_TRANSFER_OPEN"
        private const val ACTION_SHOW_IN_FILES = "com.zevclip.sender.action.FILE_TRANSFER_SHOW_IN_FILES"
        private const val EXTRA_TRANSFER_ID = "com.zevclip.sender.extra.TRANSFER_ID"

        fun cancelIntent(context: Context, transferId: String): PendingIntent {
            return broadcastIntent(context, transferId, ACTION_CANCEL)
        }

        fun showInFilesIntent(context: Context, transferId: String): PendingIntent {
            val documentsUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${Environment.DIRECTORY_DOWNLOADS}"
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(documentsUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return PendingIntent.getActivity(
                context,
                requestCode(transferId, ACTION_SHOW_IN_FILES),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        fun openIntent(context: Context, transferId: String, uri: Uri, mimeType: String?): PendingIntent {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: context.contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return PendingIntent.getActivity(
                context,
                requestCode(transferId, ACTION_OPEN),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun broadcastIntent(context: Context, transferId: String, action: String): PendingIntent {
            return PendingIntent.getBroadcast(
                context,
                requestCode(transferId, action),
                baseIntent(context, transferId, action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun baseIntent(context: Context, transferId: String, actionValue: String): Intent {
            return Intent(context, FileTransferNotificationActionReceiver::class.java).apply {
                action = actionValue
                putExtra(EXTRA_TRANSFER_ID, transferId)
            }
        }

        private fun requestCode(transferId: String, action: String): Int {
            return 50_000 + ((transferId + action).hashCode() and 0x7fffffff) % 20_000
        }
    }
}
