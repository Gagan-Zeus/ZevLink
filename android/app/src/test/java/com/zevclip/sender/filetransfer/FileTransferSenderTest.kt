package com.zevclip.sender.filetransfer

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTransferSenderTest {
    @Test
    fun streamsChunksFromFileChannelAndReportsChunkHash() {
        val root = Files.createTempDirectory("zevlink-sender-test")
        try {
            val file = root.resolve("payload.bin").toFile()
            val bytes = ByteArray((ZevLinkTransferProtocol.CHUNK_SIZE_BYTES + 3L).toInt()) { index ->
                (index % 127).toByte()
            }
            file.writeBytes(bytes)

            val source = TestFileTransferSource(file)
            val output = ByteArrayOutputStream()
            val hash = FileTransferAndroidSender().streamChunk(source, chunkIndex = 1, outputStream = output)

            val expectedChunk = bytes.copyOfRange(ZevLinkTransferProtocol.CHUNK_SIZE_BYTES.toInt(), bytes.size)
            assertArrayEquals(expectedChunk, output.toByteArray())
            assertEquals(FileTransferReceiver.sha256Hex(expectedChunk), hash)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun choosesFastestMeasuredStreamCount() {
        val selector = FileTransferAdaptiveStreamSelector()
        val selected = selector.chooseBestStreamCount(
            listOf(
                FileTransferStreamBenchmarkSample(streamCount = 1, bytes = 100, durationMillis = 100),
                FileTransferStreamBenchmarkSample(streamCount = 2, bytes = 100, durationMillis = 50),
                FileTransferStreamBenchmarkSample(streamCount = 4, bytes = 100, durationMillis = 20),
                FileTransferStreamBenchmarkSample(streamCount = 8, bytes = 100, durationMillis = 35)
            )
        )

        assertEquals(4, selected)
    }

    private class TestFileTransferSource(
        private val file: File
    ) : FileTransferSource {
        override val fileId: String = "file-1"
        override val displayName: String = file.name
        override val size: Long = file.length()
        override val mediaType: String = "application/octet-stream"

        override fun openChannel(): OpenedFileTransferSource {
            val inputStream = FileInputStream(file)
            return OpenedFileTransferSource(inputStream.channel, listOf<Closeable>(inputStream))
        }
    }
}
