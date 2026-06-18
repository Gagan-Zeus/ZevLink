package com.zevclip.sender.filetransfer

import java.util.Locale

object ZevLinkTransferProtocol {
    const val VERSION = 1
    const val CHUNK_SIZE_BYTES = 16L * 1024L * 1024L
    const val MIN_STREAM_COUNT = 1
    const val MAX_STREAM_COUNT = 8
    const val MAX_ENTRY_COUNT = 10_000
    const val MAX_RELATIVE_PATH_UTF8_BYTES = 1_024
    const val MAX_DISPLAY_NAME_SCALARS = 255

    fun chunkCount(fileSize: Long): Long {
        require(fileSize >= 0L) { "File size must not be negative." }
        if (fileSize == 0L) {
            return 0L
        }
        return ((fileSize - 1L) / CHUNK_SIZE_BYTES) + 1L
    }

    fun byteRangeForChunk(fileSize: Long, chunkIndex: Long): TransferByteRange {
        require(fileSize >= 0L) { "File size must not be negative." }
        require(chunkIndex >= 0L) { "Chunk index must not be negative." }

        val chunkCount = chunkCount(fileSize)
        require(chunkIndex < chunkCount) { "Chunk index is outside the file." }

        val startOffset = chunkIndex * CHUNK_SIZE_BYTES
        val endOffsetExclusive = minOf(startOffset + CHUNK_SIZE_BYTES, fileSize)
        return TransferByteRange(startOffset, endOffsetExclusive)
    }

    fun negotiatedStreamCount(requestedStreamCount: Int, receiverMaximum: Int = MAX_STREAM_COUNT): Int {
        require(requestedStreamCount in MIN_STREAM_COUNT..MAX_STREAM_COUNT) {
            "Requested stream count must be between 1 and 8."
        }
        require(receiverMaximum in MIN_STREAM_COUNT..MAX_STREAM_COUNT) {
            "Receiver stream count must be between 1 and 8."
        }
        return minOf(requestedStreamCount, receiverMaximum)
    }
}

enum class FileTransferEntryKind(val wireValue: String) {
    FILE("file"),
    DIRECTORY("directory");

    companion object {
        fun fromWireValue(value: String): FileTransferEntryKind? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

enum class FileTransferAddressFamily(val wireValue: String) {
    IPV6("ipv6"),
    IPV4("ipv4");

    companion object {
        fun fromWireValue(value: String): FileTransferAddressFamily? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

enum class FileTransferDecision(val wireValue: String) {
    ACCEPT("accept"),
    DECLINE("decline");

    companion object {
        fun fromWireValue(value: String): FileTransferDecision? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

enum class FileTransferSavePolicy(val wireValue: String) {
    ASK("ask"),
    DOWNLOADS("downloads"),
    CUSTOM("custom");

    companion object {
        fun fromWireValue(value: String): FileTransferSavePolicy? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

enum class FileTransferErrorCode(val wireValue: String) {
    AUTHENTICATION_FAILED("authenticationFailed"),
    CERTIFICATE_MISMATCH("certificateMismatch"),
    UNSUPPORTED_PROTOCOL_VERSION("unsupportedProtocolVersion"),
    INVALID_MANIFEST("invalidManifest"),
    INVALID_PATH("invalidPath"),
    INSUFFICIENT_STORAGE("insufficientStorage"),
    OFFER_DECLINED("offerDeclined"),
    TRANSFER_CANCELLED("transferCancelled"),
    RESUME_EXPIRED("resumeExpired"),
    INVALID_RANGE("invalidRange"),
    HASH_MISMATCH("hashMismatch"),
    MISSING_CHUNKS("missingChunks"),
    DESTINATION_CHANGED("destinationChanged"),
    TOO_MANY_STREAMS("tooManyStreams"),
    INTERNAL_ERROR("internalError")
}

data class TransferByteRange(
    val startOffset: Long,
    val endOffsetExclusive: Long
) {
    init {
        require(startOffset >= 0L) { "Range start must not be negative." }
        require(endOffsetExclusive >= startOffset) { "Range end must be greater than or equal to start." }
    }

    val length: Long
        get() = endOffsetExclusive - startOffset

    fun contentRangeHeader(totalSize: Long): String {
        require(totalSize >= 0L) { "Total size must not be negative." }
        require(endOffsetExclusive <= totalSize) { "Range cannot exceed total size." }
        val endOffsetInclusive = endOffsetExclusive - 1L
        require(endOffsetInclusive >= startOffset) { "Empty byte ranges cannot be encoded as Content-Range." }
        return "bytes $startOffset-$endOffsetInclusive/$totalSize"
    }
}

data class TransferChunkRange(
    val startChunk: Long,
    val endChunkExclusive: Long
) {
    init {
        require(startChunk >= 0L) { "Range start must not be negative." }
        require(endChunkExclusive >= startChunk) { "Range end must be greater than or equal to start." }
    }

    val chunkCount: Long
        get() = endChunkExclusive - startChunk

    fun contains(chunkIndex: Long): Boolean {
        return chunkIndex in startChunk until endChunkExclusive
    }

    fun overlaps(other: TransferChunkRange): Boolean {
        return startChunk < other.endChunkExclusive && other.startChunk < endChunkExclusive
    }
}

data class TransferVerifiedFileRanges(
    val fileId: String,
    val verifiedRanges: List<TransferChunkRange>
) {
    init {
        require(fileId.isNotBlank()) { "File ID is required." }
        verifiedRanges.zipWithNext().forEach { (previous, next) ->
            require(previous.endChunkExclusive <= next.startChunk) {
                "Verified ranges must be sorted and non-overlapping."
            }
        }
    }

    fun isChunkVerified(chunkIndex: Long): Boolean {
        return verifiedRanges.any { it.contains(chunkIndex) }
    }
}

data class FileTransferEntry(
    val fileId: String,
    val kind: FileTransferEntryKind,
    val relativePath: String,
    val size: Long? = null,
    val modifiedAt: String? = null,
    val sha256: String? = null,
    val mediaType: String? = null
) {
    init {
        require(fileId.isNotBlank()) { "File ID is required." }
        require(isValidRelativePath(relativePath)) { "Relative path is invalid." }
        when (kind) {
            FileTransferEntryKind.FILE -> {
                require(size != null && size >= 0L) { "File entries require a non-negative size." }
                require(sha256 == null || isLowercaseSha256(sha256)) {
                    "File SHA-256 must be lowercase hex."
                }
            }
            FileTransferEntryKind.DIRECTORY -> {
                require(size == null) { "Directory entries must not include size." }
                require(sha256 == null) { "Directory entries must not include SHA-256." }
                require(mediaType == null) { "Directory entries must not include media type." }
            }
        }
    }

    val chunkCount: Long
        get() = size?.let(ZevLinkTransferProtocol::chunkCount) ?: 0L

    companion object {
        fun isValidRelativePath(path: String): Boolean {
            if (path.isEmpty()) {
                return false
            }
            if (path.toByteArray(Charsets.UTF_8).size > ZevLinkTransferProtocol.MAX_RELATIVE_PATH_UTF8_BYTES) {
                return false
            }
            if (path.startsWith("/") || path.contains('\u0000') || path.contains('\\')) {
                return false
            }

            val components = path.split("/")
            return components.all { component ->
                component.isNotEmpty() && component != "." && component != ".."
            }
        }

        fun isLowercaseSha256(value: String): Boolean {
            return value.length == 64 && value.all { character ->
                character in '0'..'9' || character in 'a'..'f'
            }
        }
    }
}

data class FileTransferManifest(
    val protocolVersion: Int = ZevLinkTransferProtocol.VERSION,
    val transferId: String,
    val senderDeviceId: String,
    val senderName: String,
    val createdAt: String,
    val nonce: String,
    val totalBytes: Long,
    val entryCount: Int,
    val requestedStreamCount: Int,
    val entries: List<FileTransferEntry>
) {
    init {
        require(protocolVersion == ZevLinkTransferProtocol.VERSION) {
            "Unsupported file transfer protocol version."
        }
        require(transferId.isNotBlank()) { "Transfer ID is required." }
        require(senderDeviceId.isNotBlank()) { "Sender device ID is required." }
        require(senderName.isNotBlank()) { "Sender name is required." }
        require(createdAt.isNotBlank()) { "Creation timestamp is required." }
        require(nonce.isNotBlank()) { "Transfer nonce is required." }
        require(totalBytes >= 0L) { "Total bytes must not be negative." }
        require(entries.size <= ZevLinkTransferProtocol.MAX_ENTRY_COUNT) {
            "A transfer cannot contain more than ${ZevLinkTransferProtocol.MAX_ENTRY_COUNT} entries."
        }
        require(entryCount == entries.size) { "Entry count does not match manifest entries." }
        require(requestedStreamCount in ZevLinkTransferProtocol.MIN_STREAM_COUNT..ZevLinkTransferProtocol.MAX_STREAM_COUNT) {
            "Requested stream count must be between 1 and 8."
        }

        val computedTotalBytes = entries.sumOf { entry -> entry.size ?: 0L }
        require(totalBytes == computedTotalBytes) { "Total bytes do not match manifest entries." }

        val normalizedPaths = entries.map { it.relativePath.lowercase(Locale.ROOT) }
        require(normalizedPaths.distinct().size == normalizedPaths.size) {
            "Manifest contains duplicate relative paths."
        }
    }

    val fileEntries: List<FileTransferEntry>
        get() = entries.filter { it.kind == FileTransferEntryKind.FILE }

    val totalChunkCount: Long
        get() = fileEntries.sumOf { it.chunkCount }

}

data class FileTransferCandidate(
    val id: String,
    val family: FileTransferAddressFamily,
    val host: String,
    val port: Int,
    val priority: Int,
    val networkId: String
) {
    init {
        require(id.isNotBlank()) { "Candidate ID is required." }
        require(host.isNotBlank()) { "Candidate host is required." }
        require(port in 1..65_535) { "Candidate port is invalid." }
        require(networkId.isNotBlank()) { "Candidate network ID is required." }
    }
}

data class FileTransferOfferResponse(
    val transferId: String,
    val state: FileTransferState,
    val chunkSize: Long,
    val streamCount: Int,
    val resumeToken: String?,
    val candidates: List<FileTransferCandidate>
) {
    init {
        require(transferId.isNotBlank()) { "Transfer ID is required." }
        require(chunkSize == ZevLinkTransferProtocol.CHUNK_SIZE_BYTES) { "Unexpected chunk size." }
        require(streamCount in ZevLinkTransferProtocol.MIN_STREAM_COUNT..ZevLinkTransferProtocol.MAX_STREAM_COUNT) {
            "Stream count must be between 1 and 8."
        }
        require(state != FileTransferState.ACCEPTED || !resumeToken.isNullOrBlank()) {
            "Accepted transfers require a resume token."
        }
    }
}
