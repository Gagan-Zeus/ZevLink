package com.zevclip.sender.filetransfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.URLConnection

object AndroidPublicDownloadsPublisher {
    data class PublishedTransfer(
        val publicDirectory: String,
        val fileUris: List<Uri>
    )

    fun publish(
        context: Context,
        completion: FileTransferReceiver.CompletionResult
    ): PublishedTransfer {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(context, completion)
        } else {
            publishLegacy(completion)
        }
    }

    private fun publishWithMediaStore(
        context: Context,
        completion: FileTransferReceiver.CompletionResult
    ): PublishedTransfer {
        val resolver = context.contentResolver
        val senderDirectory = sanitizePathComponent(completion.destinationRoot.name)
        val publicRoot = "${Environment.DIRECTORY_DOWNLOADS}/ZevLink Transfers/$senderDirectory"
        val inserted = mutableListOf<Uri>()

        try {
            completion.files.forEach { completedFile ->
                val relativeFilePath = completedFile.file
                    .relativeTo(completion.destinationRoot)
                    .invariantSeparatorsPath
                val parentPath = relativeFilePath.substringBeforeLast('/', missingDelimiterValue = "")
                val relativeDirectory = buildString {
                    append(publicRoot)
                    if (parentPath.isNotEmpty()) {
                        append('/')
                        append(parentPath.split('/').joinToString("/", transform = ::sanitizePathComponent))
                    }
                    append('/')
                }
                val displayName = sanitizePathComponent(completedFile.file.name)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        URLConnection.guessContentTypeFromName(displayName) ?: "application/octet-stream"
                    )
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Android could not create $displayName in public Downloads.")
                inserted += uri
                resolver.openOutputStream(uri, "w")?.use { output ->
                    completedFile.file.inputStream().use { input ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                } ?: error("Android could not open $displayName in public Downloads.")
            }

            val visible = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            inserted.forEach { uri ->
                check(resolver.update(uri, visible, null, null) == 1) {
                    "Android could not publish a received file in Downloads."
                }
            }
            completion.destinationRoot.deleteRecursively()
            return PublishedTransfer(
                publicDirectory = "/storage/emulated/0/$publicRoot",
                fileUris = inserted
            )
        } catch (error: Exception) {
            inserted.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(
        completion: FileTransferReceiver.CompletionResult
    ): PublishedTransfer {
        val senderDirectory = sanitizePathComponent(completion.destinationRoot.name)
        val publicRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ZevLink Transfers/$senderDirectory"
        )
        check(publicRoot.mkdirs() || publicRoot.isDirectory) {
            "Android could not create ${publicRoot.absolutePath}."
        }
        completion.files.forEach { completedFile ->
            val relativePath = completedFile.file.relativeTo(completion.destinationRoot).invariantSeparatorsPath
            val destination = File(publicRoot, relativePath)
            destination.parentFile?.mkdirs()
            completedFile.file.copyTo(destination, overwrite = false)
        }
        completion.destinationRoot.deleteRecursively()
        return PublishedTransfer(
            publicDirectory = publicRoot.absolutePath,
            fileUris = emptyList()
        )
    }

    private fun sanitizePathComponent(value: String): String {
        return value
            .replace(Regex("""[/\\:\u0000\r\n]+"""), " ")
            .trim()
            .ifBlank { "Transfer" }
            .take(ZevLinkTransferProtocol.MAX_DISPLAY_NAME_SCALARS)
    }
}
