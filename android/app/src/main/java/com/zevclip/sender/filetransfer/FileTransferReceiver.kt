package com.zevclip.sender.filetransfer

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min

class FileTransferReceiver(
    private val rootDir: File
) {
    data class CompletedFile(
        val fileId: String,
        val file: File,
        val sha256: String,
        val expectedSha256: String?
    )

    data class CompletionResult(
        val transferId: String,
        val destinationRoot: File,
        val files: List<CompletedFile>
    )

    private data class Session(
        val manifest: FileTransferManifest,
        val tempRoot: File,
        val destinationRoot: File,
        val resumeToken: String,
        val stateMachine: FileTransferStateMachine,
        val verifiedChunksByFileId: MutableMap<String, MutableSet<Long>>,
        val lock: Any = Any()
    )

    private val sessionsByTransferId = mutableMapOf<String, Session>()

    @Synchronized
    fun accept(
        manifest: FileTransferManifest,
        requestedStreamCount: Int = manifest.requestedStreamCount,
        receiverMaximumStreams: Int = ZevLinkTransferProtocol.MAX_STREAM_COUNT
    ): FileTransferOfferResponse {
        require(!sessionsByTransferId.containsKey(manifest.transferId)) { "Transfer already exists." }
        require(rootDir.mkdirs() || rootDir.isDirectory) {
            "Could not create transfer staging directory ${rootDir.absolutePath}."
        }
        require(rootDir.usableSpace >= manifest.totalBytes) {
            "Not enough disk space. Need ${manifest.totalBytes} bytes; available ${rootDir.usableSpace} bytes."
        }

        val streamCount = ZevLinkTransferProtocol.negotiatedStreamCount(requestedStreamCount, receiverMaximumStreams)
        val resumeToken = UUID.randomUUID().toString().replace("-", "").lowercase()
        val destinationRoot = uniqueDestinationRoot(manifest.senderName.ifBlank { manifest.transferId })
        val tempRoot = File(File(rootDir, ".partial"), "${manifest.transferId}-$resumeToken")
        prepareStorage(manifest, tempRoot)

        sessionsByTransferId[manifest.transferId] = Session(
            manifest = manifest,
            tempRoot = tempRoot,
            destinationRoot = destinationRoot,
            resumeToken = resumeToken,
            stateMachine = FileTransferStateMachine(
                initialState = FileTransferState.ACCEPTED,
                totalBytes = manifest.totalBytes,
                selectedStreamCount = streamCount
            ),
            verifiedChunksByFileId = manifest.fileEntries.associate { entry ->
                entry.fileId to mutableSetOf<Long>()
            }.toMutableMap()
        )

        return FileTransferOfferResponse(
            transferId = manifest.transferId,
            state = FileTransferState.ACCEPTED,
            chunkSize = ZevLinkTransferProtocol.CHUNK_SIZE_BYTES,
            streamCount = streamCount,
            resumeToken = resumeToken,
            candidates = emptyList()
        )
    }

    @Synchronized
    fun verifiedRanges(transferId: String): List<TransferVerifiedFileRanges> {
        val session = session(transferId)
        return session.manifest.fileEntries.map { entry ->
            TransferVerifiedFileRanges(
                fileId = entry.fileId,
                verifiedRanges = compactRanges(session.verifiedChunksByFileId[entry.fileId].orEmpty())
            )
        }
    }

    fun writeChunk(
        transferId: String,
        fileId: String,
        chunkIndex: Long,
        data: ByteArray,
        chunkSha256: String? = null,
        activeStreamCount: Int = 1
    ) {
        val session = session(transferId)
        val entry = session.manifest.fileEntries.firstOrNull { it.fileId == fileId }
            ?: error("File not found.")
        synchronized(session.lock) {
            if (session.verifiedChunksByFileId[fileId]?.contains(chunkIndex) == true) {
                return
            }
        }
        val size = entry.size ?: error("Directory entries cannot receive chunks.")
        val range = ZevLinkTransferProtocol.byteRangeForChunk(size, chunkIndex)
        require(data.size.toLong() == range.length) { "Chunk size does not match byte range." }
        if (chunkSha256 != null) {
            require(sha256Hex(data) == chunkSha256) { "Chunk SHA-256 mismatch." }
        }

        positionedWrite(File(session.tempRoot, entry.relativePath), range.startOffset, data)

        synchronized(session.lock) {
            val verifiedChunks = session.verifiedChunksByFileId.getValue(fileId)
            if (verifiedChunks.add(chunkIndex)) {
                if (session.stateMachine.state == FileTransferState.ACCEPTED ||
                    session.stateMachine.state == FileTransferState.INTERRUPTED
                ) {
                    session.stateMachine.markChunkRequestStarted(activeStreamCount)
                }
                session.stateMachine.markChunkVerified(range.length)
            }
        }
    }

    fun writeChunkStream(
        transferId: String,
        fileId: String,
        chunkIndex: Long,
        inputStream: InputStream,
        contentLength: Long,
        chunkSha256: String? = null,
        activeStreamCount: Int = 1
    ) {
        val session = session(transferId)
        val entry = session.manifest.fileEntries.firstOrNull { it.fileId == fileId }
            ?: error("File not found.")
        val size = entry.size ?: error("Directory entries cannot receive chunks.")
        val range = ZevLinkTransferProtocol.byteRangeForChunk(size, chunkIndex)
        require(contentLength == range.length) { "Chunk size does not match byte range." }

        val actualChunkSha256 = positionedWrite(
            file = File(session.tempRoot, entry.relativePath),
            offset = range.startOffset,
            inputStream = inputStream,
            length = range.length,
            computeSha256 = chunkSha256 != null
        )
        chunkSha256?.let { expected ->
            require(expected == actualChunkSha256) { "Chunk SHA-256 mismatch." }
        }

        synchronized(session.lock) {
            val verifiedChunks = session.verifiedChunksByFileId.getValue(fileId)
            if (verifiedChunks.add(chunkIndex)) {
                if (session.stateMachine.state == FileTransferState.ACCEPTED ||
                    session.stateMachine.state == FileTransferState.INTERRUPTED
                ) {
                    session.stateMachine.markChunkRequestStarted(activeStreamCount)
                }
                session.stateMachine.markChunkVerified(range.length)
            }
        }
    }

    @Synchronized
    fun complete(transferId: String, verifyFileHashes: Boolean = true): CompletionResult {
        val session = session(transferId)
        val completedFiles = session.manifest.fileEntries.map { entry ->
            val expectedChunks = ZevLinkTransferProtocol.chunkCount(entry.size ?: 0L)
            val verifiedChunks = session.verifiedChunksByFileId[entry.fileId].orEmpty()
            require(verifiedChunks.size.toLong() == expectedChunks) { "Transfer is missing chunks." }

            val tempFile = File(session.tempRoot, entry.relativePath)
            val actualSha256 = if (verifyFileHashes) sha256Hex(tempFile) else null
            if (actualSha256 != null) {
                entry.sha256?.let { expected ->
                    require(expected == actualSha256) { "File SHA-256 mismatch." }
                }
            }
            CompletedFile(
                fileId = entry.fileId,
                file = File(session.destinationRoot, entry.relativePath),
                sha256 = actualSha256 ?: entry.sha256.orEmpty(),
                expectedSha256 = entry.sha256
            )
        }

        session.stateMachine.beginVerification()
        session.stateMachine.complete()
        session.destinationRoot.parentFile?.mkdirs()
        require(session.tempRoot.renameTo(session.destinationRoot)) {
            "Could not atomically move transfer into place."
        }
        sessionsByTransferId.remove(transferId)

        return CompletionResult(
            transferId = transferId,
            destinationRoot = session.destinationRoot,
            files = completedFiles
        )
    }

    @Synchronized
    fun cancel(transferId: String) {
        sessionsByTransferId.remove(transferId)?.tempRoot?.deleteRecursively()
    }

    @Synchronized
    private fun session(transferId: String): Session {
        return sessionsByTransferId[transferId] ?: error("Transfer was not found.")
    }

    private fun prepareStorage(manifest: FileTransferManifest, tempRoot: File) {
        tempRoot.mkdirs()
        manifest.entries.forEach { entry ->
            val file = File(tempRoot, entry.relativePath)
            when (entry.kind) {
                FileTransferEntryKind.DIRECTORY -> file.mkdirs()
                FileTransferEntryKind.FILE -> {
                    file.parentFile?.mkdirs()
                    RandomAccessFile(file, "rw").use { randomAccessFile ->
                        randomAccessFile.setLength(entry.size ?: 0L)
                    }
                }
            }
        }
    }

    private fun positionedWrite(file: File, offset: Long, data: ByteArray) {
        RandomAccessFile(file, "rw").use { randomAccessFile ->
            randomAccessFile.channel.use { channel ->
                var written = 0
                while (written < data.size) {
                    written += channel.write(java.nio.ByteBuffer.wrap(data, written, data.size - written), offset + written)
                }
            }
        }
    }

    private fun positionedWrite(
        file: File,
        offset: Long,
        inputStream: InputStream,
        length: Long,
        computeSha256: Boolean
    ): String? {
        val digest = if (computeSha256) MessageDigest.getInstance("SHA-256") else null
        RandomAccessFile(file, "rw").use { randomAccessFile ->
            randomAccessFile.seek(offset)
            val buffer = ByteArray(1024 * 1024)
            var remaining = length
            while (remaining > 0L) {
                val read = inputStream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(read > 0) { "Unexpected EOF while receiving chunk." }
                randomAccessFile.write(buffer, 0, read)
                digest?.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest?.digest()?.joinToString("") { "%02x".format(it) }
    }

    private fun uniqueDestinationRoot(rawName: String): File {
        val base = rawName.replace(Regex("""[/\\:\u0000\r\n]+"""), " ").trim().ifBlank { "Transfer" }
        var candidate = File(rootDir, base)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(rootDir, "$base $suffix")
            suffix += 1
        }
        return candidate
    }

    private fun compactRanges(chunks: Set<Long>): List<TransferChunkRange> {
        if (chunks.isEmpty()) {
            return emptyList()
        }
        val sorted = chunks.sorted()
        val ranges = mutableListOf<TransferChunkRange>()
        var start = sorted.first()
        var previous = start
        sorted.drop(1).forEach { chunk ->
            if (chunk == previous + 1L) {
                previous = chunk
            } else {
                ranges += TransferChunkRange(start, previous + 1L)
                start = chunk
                previous = chunk
            }
        }
        ranges += TransferChunkRange(start, previous + 1L)
        return ranges
    }

    companion object {
        fun sha256Hex(data: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(data)
                .joinToString("") { "%02x".format(it) }
        }

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
