package com.zevclip.sender.filetransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferModelsTest {
    @Test
    fun computesChunkCountsAndByteRanges() {
        assertEquals(0, ZevLinkTransferProtocol.chunkCount(0))
        assertEquals(1, ZevLinkTransferProtocol.chunkCount(1))
        assertEquals(1, ZevLinkTransferProtocol.chunkCount(ZevLinkTransferProtocol.CHUNK_SIZE_BYTES))
        assertEquals(2, ZevLinkTransferProtocol.chunkCount(ZevLinkTransferProtocol.CHUNK_SIZE_BYTES + 1))

        val range = ZevLinkTransferProtocol.byteRangeForChunk(
            fileSize = ZevLinkTransferProtocol.CHUNK_SIZE_BYTES + 7,
            chunkIndex = 1
        )

        assertEquals(ZevLinkTransferProtocol.CHUNK_SIZE_BYTES, range.startOffset)
        assertEquals(ZevLinkTransferProtocol.CHUNK_SIZE_BYTES + 7, range.endOffsetExclusive)
        val fileSize = ZevLinkTransferProtocol.CHUNK_SIZE_BYTES + 7
        assertEquals(
            "bytes ${ZevLinkTransferProtocol.CHUNK_SIZE_BYTES}-${fileSize - 1}/$fileSize",
            range.contentRangeHeader(fileSize)
        )
    }

    @Test
    fun validatesManifestTotalsAndPaths() {
        val entry = FileTransferEntry(
            fileId = "7fc9ad9e-ce8e-4cf2-b764-ce1d343c2e66",
            kind = FileTransferEntryKind.FILE,
            relativePath = "folder/video.mov",
            size = 12,
            sha256 = "a".repeat(64),
            mediaType = "video/quicktime"
        )

        val manifest = FileTransferManifest(
            transferId = "3b88ee67-f1c4-46ca-a868-a494ba34571b",
            senderDeviceId = "9721584a-8fb0-4ecc-bad5-25d2693f3d54",
            senderName = "Android Phone",
            createdAt = "2026-06-18T07:30:00Z",
            nonce = "d95fe338f5a14e56837d936f4f869f23",
            totalBytes = 12,
            entryCount = 1,
            requestedStreamCount = 4,
            entries = listOf(entry)
        )

        assertEquals(12, manifest.totalBytes)
        assertEquals(1, manifest.fileEntries.size)
        assertEquals(1, manifest.totalChunkCount)
        assertTrue(FileTransferEntry.isValidRelativePath("folder/video.mov"))
        assertFalse(FileTransferEntry.isValidRelativePath("../video.mov"))
        assertFalse(FileTransferEntry.isValidRelativePath("/tmp/video.mov"))
        assertFalse(FileTransferEntry.isValidRelativePath("folder\\video.mov"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedManifestTotal() {
        FileTransferManifest(
            transferId = "3b88ee67-f1c4-46ca-a868-a494ba34571b",
            senderDeviceId = "9721584a-8fb0-4ecc-bad5-25d2693f3d54",
            senderName = "Android Phone",
            createdAt = "2026-06-18T07:30:00Z",
            nonce = "d95fe338f5a14e56837d936f4f869f23",
            totalBytes = 13,
            entryCount = 1,
            requestedStreamCount = 4,
            entries = listOf(
                FileTransferEntry(
                    fileId = "7fc9ad9e-ce8e-4cf2-b764-ce1d343c2e66",
                    kind = FileTransferEntryKind.FILE,
                    relativePath = "video.mov",
                    size = 12,
                    sha256 = "a".repeat(64)
                )
            )
        )
    }

    @Test
    fun verifiedRangesAreSortedAndSearchable() {
        val ranges = TransferVerifiedFileRanges(
            fileId = "7fc9ad9e-ce8e-4cf2-b764-ce1d343c2e66",
            verifiedRanges = listOf(
                TransferChunkRange(0, 2),
                TransferChunkRange(4, 5)
            )
        )

        assertTrue(ranges.isChunkVerified(1))
        assertFalse(ranges.isChunkVerified(3))
        assertTrue(TransferChunkRange(0, 2).overlaps(TransferChunkRange(1, 3)))
        assertFalse(TransferChunkRange(0, 2).overlaps(TransferChunkRange(2, 3)))
    }
}
