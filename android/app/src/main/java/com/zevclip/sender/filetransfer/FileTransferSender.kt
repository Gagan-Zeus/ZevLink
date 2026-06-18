package com.zevclip.sender.filetransfer

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

interface FileTransferSource {
    val fileId: String
    val displayName: String
    val size: Long
    val mediaType: String?
    fun openChannel(): OpenedFileTransferSource
}

class OpenedFileTransferSource(
    val channel: FileChannel,
    private val closeables: List<Closeable>
) : Closeable {
    override fun close() {
        closeables.asReversed().forEach { closeable -> closeable.close() }
    }
}

class ContentResolverFileTransferSource(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    override val fileId: String = UUID.randomUUID().toString()
) : FileTransferSource {
    override val displayName: String by lazy {
        queryString(OpenableColumns.DISPLAY_NAME) ?: uri.lastPathSegment ?: "file-$fileId"
    }

    override val size: Long by lazy {
        queryLong(OpenableColumns.SIZE)
            ?: contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it >= 0L }
            ?: error("Could not determine file size for $uri.")
    }

    override val mediaType: String? by lazy {
        contentResolver.getType(uri)
    }

    override fun openChannel(): OpenedFileTransferSource {
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: error("Could not open $uri.")
        val inputStream = FileInputStream(descriptor.fileDescriptor)
        return OpenedFileTransferSource(
            channel = inputStream.channel,
            closeables = listOf(inputStream, descriptor)
        )
    }

    private fun queryString(column: String): String? {
        return query { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }

    private fun queryLong(column: String): Long? {
        return query { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getLong(index)
            } else {
                null
            }
        }
    }

    private fun <T> query(block: (Cursor) -> T?): T? {
        return contentResolver.query(uri, null, null, null, null)?.use(block)
    }
}

class FileTransferAndroidSender {
    fun manifest(
        transferId: String = UUID.randomUUID().toString(),
        senderDeviceId: String,
        senderName: String,
        sources: List<FileTransferSource>,
        requestedStreamCount: Int
    ): FileTransferManifest {
        val entries = sources.map { source ->
            FileTransferEntry(
                fileId = source.fileId,
                kind = FileTransferEntryKind.FILE,
                relativePath = sanitizeRelativePath(source.displayName),
                size = source.size,
                sha256 = sha256Hex(source),
                mediaType = source.mediaType
            )
        }
        return FileTransferManifest(
            transferId = transferId,
            senderDeviceId = senderDeviceId,
            senderName = senderName,
            createdAt = Instant.now().toString(),
            nonce = UUID.randomUUID().toString().replace("-", "").lowercase(),
            totalBytes = entries.sumOf { it.size ?: 0L },
            entryCount = entries.size,
            requestedStreamCount = requestedStreamCount,
            entries = entries
        )
    }

    fun streamChunk(
        source: FileTransferSource,
        chunkIndex: Long,
        outputStream: OutputStream,
        bufferSize: Int = 1024 * 1024,
        computeSha256: Boolean = true
    ): String {
        val range = ZevLinkTransferProtocol.byteRangeForChunk(source.size, chunkIndex)
        val digest = if (computeSha256) MessageDigest.getInstance("SHA-256") else null
        source.openChannel().use { opened ->
            opened.channel.position(range.startOffset)
            val bytes = ByteArray(bufferSize)
            val buffer = ByteBuffer.wrap(bytes)
            var remaining = range.length
            while (remaining > 0L) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val read = opened.channel.read(buffer)
                require(read >= 0) { "Unexpected EOF while reading ${source.displayName}." }
                digest?.update(bytes, 0, read)
                outputStream.write(bytes, 0, read)
                remaining -= read
            }
        }
        return digest?.digest()?.joinToString("") { "%02x".format(it) }.orEmpty()
    }

    fun sha256Hex(source: FileTransferSource, bufferSize: Int = 1024 * 1024): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.openChannel().use { opened ->
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            while (opened.channel.read(buffer) >= 0) {
                buffer.flip()
                if (buffer.hasRemaining()) {
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    digest.update(bytes)
                }
                buffer.clear()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeRelativePath(name: String): String {
        return name
            .replace(Regex("""[/\\:\u0000\r\n]+"""), " ")
            .trim()
            .ifBlank { "file" }
            .take(ZevLinkTransferProtocol.MAX_DISPLAY_NAME_SCALARS)
    }
}

data class FileTransferStreamBenchmarkSample(
    val streamCount: Int,
    val bytes: Long,
    val durationMillis: Long
) {
    val bytesPerSecond: Double
        get() = if (durationMillis <= 0L) 0.0 else bytes.toDouble() / (durationMillis.toDouble() / 1000.0)
}

class FileTransferAdaptiveStreamSelector {
    fun chooseBestStreamCount(samples: List<FileTransferStreamBenchmarkSample>): Int {
        return samples
            .filter { it.streamCount in CANDIDATE_STREAM_COUNTS && it.bytes > 0L && it.durationMillis > 0L }
            .maxByOrNull { it.bytesPerSecond }
            ?.streamCount
            ?: 4
    }

    companion object {
        val CANDIDATE_STREAM_COUNTS = listOf(1, 2, 4, 8)
    }
}

class FileTransferStreamBenchmarker(
    private val sender: FileTransferAndroidSender = FileTransferAndroidSender()
) {
    fun benchmarkSource(
        source: FileTransferSource,
        streamCounts: List<Int> = FileTransferAdaptiveStreamSelector.CANDIDATE_STREAM_COUNTS
    ): List<FileTransferStreamBenchmarkSample> {
        val chunkIndexes = (0 until ZevLinkTransferProtocol.chunkCount(source.size)).toList()
        return streamCounts.map { streamCount ->
            require(streamCount in FileTransferAdaptiveStreamSelector.CANDIDATE_STREAM_COUNTS) {
                "Unsupported benchmark stream count."
            }

            val executor = Executors.newFixedThreadPool(streamCount)
            val nextIndex = AtomicInteger(0)
            val startedNanos = System.nanoTime()
            val futures = (0 until streamCount).map {
                executor.submit {
                    while (true) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= chunkIndexes.size) {
                            break
                        }
                        sender.streamChunk(
                            source = source,
                            chunkIndex = chunkIndexes[index],
                            outputStream = DiscardingOutputStream
                        )
                    }
                }
            }
            futures.forEach { future -> future.get() }
            executor.shutdown()
            val durationMillis = ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(1L)
            FileTransferStreamBenchmarkSample(
                streamCount = streamCount,
                bytes = source.size,
                durationMillis = durationMillis
            )
        }
    }
}

private object DiscardingOutputStream : OutputStream() {
    override fun write(b: Int) = Unit

    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}
