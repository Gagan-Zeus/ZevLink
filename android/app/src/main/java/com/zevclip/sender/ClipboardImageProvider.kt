package com.zevclip.sender

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

class ClipboardImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val clipboardFile = clipboardFile(uri) ?: return null
        return mimeTypeForExtension(clipboardFile.extension.lowercase())
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Clipboard images are read-only.")
        }

        val file = clipboardFile(uri)?.takeIf { it.isFile }
            ?: throw FileNotFoundException("Clipboard image was not found.")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = clipboardFile(uri)?.takeIf { it.isFile } ?: return null
        val displayName = uri.pathSegments.last()
        val requestedColumns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val supportedColumns = requestedColumns.filter {
            it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE
        }
        return MatrixCursor(supportedColumns.toTypedArray(), 1).apply {
            addRow(supportedColumns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> displayName
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun clipboardFile(uri: Uri): File? {
        val appContext = context ?: return null
        if (uri.authority != authority(appContext) || uri.pathSegments.size != 3) {
            return null
        }
        if (uri.pathSegments[0] != CONTENT_PATH) {
            return null
        }

        val id = uri.pathSegments[1]
        val displayName = uri.pathSegments[2]
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (!ID.matches(id) || !isSafeDisplayName(displayName) || extension !in SUPPORTED_EXTENSIONS) {
            return null
        }

        return File(contentDirectory(appContext), "$id.$extension")
    }

    companion object {
        private const val CONTENT_PATH = "content"
        private const val MAX_DIMENSION = 32_768
        private const val MAX_PIXEL_COUNT = 150_000_000L
        private val ID = Regex("[0-9a-f-]{36}")
        private val SAFE_DISPLAY_NAME = Regex("[^/\\u0000-\\u001f]{1,180}")
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        )

        fun publish(context: Context, pngData: ByteArray): Uri? {
            if (!isValidPng(pngData)) {
                return null
            }

            return publishApprovedFile(context, pngData, "clipboard.png")
        }

        fun publishApprovedFile(context: Context, data: ByteArray, displayName: String): Uri? {
            val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            val extension = safeName.substringAfterLast('.', "").lowercase()
            if (!isSafeDisplayName(safeName) || extension !in SUPPORTED_EXTENSIONS) {
                return null
            }
            if (extension == "xml" && !isAndroidVectorDrawable(data)) {
                return null
            }

            val appContext = context.applicationContext
            val directory = contentDirectory(appContext)
            if (!directory.exists() && !directory.mkdirs()) {
                return null
            }

            directory.listFiles()?.forEach { it.delete() }
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id.$extension")
            return try {
                file.outputStream().use { it.write(data) }
                Uri.Builder()
                    .scheme("content")
                    .authority(authority(appContext))
                    .appendPath(CONTENT_PATH)
                    .appendPath(id)
                    .appendPath(safeName)
                    .build()
            } catch (_: Exception) {
                file.delete()
                null
            }
        }

        private fun isSafeDisplayName(fileName: String): Boolean =
            SAFE_DISPLAY_NAME.matches(fileName) && fileName != "." && fileName != ".."

        private fun isAndroidVectorDrawable(data: ByteArray): Boolean {
            val prefix = data.copyOfRange(0, minOf(data.size, 16_384)).toString(Charsets.UTF_8)
            return Regex("<vector(?:\\s|>)").containsMatchIn(prefix) &&
                prefix.contains("http://schemas.android.com/apk/res/android")
        }

        private fun isValidPng(data: ByteArray): Boolean {
            if (data.size < PNG_SIGNATURE.size ||
                !data.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
            ) {
                return false
            }

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            val width = options.outWidth
            val height = options.outHeight
            return width in 1..MAX_DIMENSION &&
                height in 1..MAX_DIMENSION &&
                width.toLong() * height.toLong() <= MAX_PIXEL_COUNT
        }

        private fun contentDirectory(context: Context): File =
            File(context.cacheDir, "clipboard-content")

        private fun authority(context: Context): String =
            "${context.packageName}.clipboard-images"

        private fun mimeTypeForExtension(extension: String): String = MIME_TYPES[extension]
            ?: "application/octet-stream"

        private val SUPPORTED_EXTENSIONS = setOf(
            "jpeg", "jpg", "png", "gif", "webp", "avif", "heif", "heic",
            "tif", "tiff", "bmp", "svg", "xml", "icns", "raw", "dng",
            "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "ico", "psd"
        )

        private val MIME_TYPES = mapOf(
            "jpeg" to "image/jpeg", "jpg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp", "avif" to "image/avif",
            "heif" to "image/heif", "heic" to "image/heic", "tif" to "image/tiff",
            "tiff" to "image/tiff", "bmp" to "image/bmp", "svg" to "image/svg+xml",
            "xml" to "application/vnd.android.vector-drawable", "icns" to "image/icns",
            "raw" to "image/x-raw", "dng" to "image/x-adobe-dng",
            "cr2" to "image/x-canon-cr2", "cr3" to "image/x-canon-cr3",
            "nef" to "image/x-nikon-nef", "arw" to "image/x-sony-arw",
            "orf" to "image/x-olympus-orf", "rw2" to "image/x-panasonic-rw2",
            "raf" to "image/x-fuji-raf", "ico" to "image/x-icon",
            "psd" to "image/vnd.adobe.photoshop"
        )
    }
}
