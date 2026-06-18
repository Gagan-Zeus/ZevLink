package com.zevclip.sender.filetransfer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTransferReceiverTest {
    @Test
    fun positionedWritesCompleteOutOfOrderAndVerifyHash() {
        val root = Files.createTempDirectory("zevlink-transfer-test")
        try {
            val bytes = ByteArray((ZevLinkTransferProtocol.CHUNK_SIZE_BYTES + 7L).toInt()) { index ->
                (index % 251).toByte()
            }
            val manifest = manifestFor(bytes)
            val receiver = FileTransferReceiver(root.toFile())
            receiver.accept(manifest, requestedStreamCount = 2)

            val secondChunk = bytes.copyOfRange(ZevLinkTransferProtocol.CHUNK_SIZE_BYTES.toInt(), bytes.size)
            val firstChunk = bytes.copyOfRange(0, ZevLinkTransferProtocol.CHUNK_SIZE_BYTES.toInt())
            receiver.writeChunk(
                transferId = manifest.transferId,
                fileId = "file-1",
                chunkIndex = 1,
                data = secondChunk,
                chunkSha256 = FileTransferReceiver.sha256Hex(secondChunk),
                activeStreamCount = 2
            )
            receiver.writeChunk(
                transferId = manifest.transferId,
                fileId = "file-1",
                chunkIndex = 0,
                data = firstChunk,
                chunkSha256 = FileTransferReceiver.sha256Hex(firstChunk),
                activeStreamCount = 2
            )

            val ranges = receiver.verifiedRanges(manifest.transferId).single().verifiedRanges
            assertEquals(listOf(TransferChunkRange(0, 2)), ranges)

            val result = receiver.complete(manifest.transferId)
            val completed = result.files.single().file.readBytes()
            assertArrayEquals(bytes, completed)
            assertEquals(FileTransferReceiver.sha256Hex(bytes), result.files.single().sha256)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsChunkHashMismatch() {
        val root = Files.createTempDirectory("zevlink-transfer-test")
        try {
            val bytes = "hello".toByteArray(StandardCharsets.UTF_8)
            val manifest = manifestFor(bytes)
            val receiver = FileTransferReceiver(root.toFile())
            receiver.accept(manifest)
            receiver.writeChunk(
                transferId = manifest.transferId,
                fileId = "file-1",
                chunkIndex = 0,
                data = bytes,
                chunkSha256 = "0".repeat(64)
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun streamsChunkDirectlyIntoPositionedFile() {
        val root = Files.createTempDirectory("zevlink-transfer-stream-test")
        try {
            val bytes = ByteArray(1024 * 1024 + 17) { (it % 239).toByte() }
            val manifest = manifestFor(bytes)
            val receiver = FileTransferReceiver(root.toFile())
            receiver.accept(manifest)

            receiver.writeChunkStream(
                transferId = manifest.transferId,
                fileId = "file-1",
                chunkIndex = 0,
                inputStream = bytes.inputStream(),
                contentLength = bytes.size.toLong(),
                chunkSha256 = FileTransferReceiver.sha256Hex(bytes)
            )

            val result = receiver.complete(manifest.transferId)
            assertArrayEquals(bytes, result.files.single().file.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun manifestFor(bytes: ByteArray): FileTransferManifest {
        return FileTransferManifest(
            transferId = "transfer-1",
            senderDeviceId = "android-1",
            senderName = "Android",
            createdAt = "2026-06-18T00:00:00Z",
            nonce = "nonce",
            totalBytes = bytes.size.toLong(),
            entryCount = 1,
            requestedStreamCount = 4,
            entries = listOf(
                FileTransferEntry(
                    fileId = "file-1",
                    kind = FileTransferEntryKind.FILE,
                    relativePath = "sample.bin",
                    size = bytes.size.toLong(),
                    sha256 = FileTransferReceiver.sha256Hex(bytes),
                    mediaType = "application/octet-stream"
                )
            )
        )
    }
}
