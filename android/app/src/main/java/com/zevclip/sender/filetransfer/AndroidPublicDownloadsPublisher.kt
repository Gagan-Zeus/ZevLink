package com.zevclip.sender.filetransfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest

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
        val publicRoot = Environment.DIRECTORY_DOWNLOADS
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
                    copyAndVerify(completedFile.file, output, completedFile.expectedSha256)
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
                publicDirectory = "/storage/emulated/0/${Environment.DIRECTORY_DOWNLOADS}",
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
        val publicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        check(publicRoot.mkdirs() || publicRoot.isDirectory) {
            "Android could not create ${publicRoot.absolutePath}."
        }
        completion.files.forEach { completedFile ->
            val relativePath = completedFile.file.relativeTo(completion.destinationRoot).invariantSeparatorsPath
            val destination = uniqueDestinationFile(File(publicRoot, relativePath))
            destination.parentFile?.mkdirs()
            destination.outputStream().use { output ->
                copyAndVerify(completedFile.file, output, completedFile.expectedSha256)
            }
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

    private fun uniqueDestinationFile(requested: File): File {
        if (!requested.exists()) return requested

        val extension = requested.extension
        val baseName = requested.nameWithoutExtension
        var suffix = 2
        var candidate: File
        do {
            val name = if (extension.isEmpty()) {
                "$baseName $suffix"
            } else {
                "$baseName $suffix.$extension"
            }
            candidate = File(requested.parentFile, name)
            suffix += 1
        } while (candidate.exists())
        return candidate
    }

    private fun copyAndVerify(
        source: File,
        output: java.io.OutputStream,
        expectedSha256: String?
    ) {
        val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
        source.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                digest?.update(buffer, 0, read)
            }
        }
        expectedSha256?.let { expected ->
            val actualSha256 = checkNotNull(digest)
                .digest()
                .joinToString("") { "%02x".format(it) }
            require(expected == actualSha256) { "File SHA-256 mismatch." }
        }
    }
}
