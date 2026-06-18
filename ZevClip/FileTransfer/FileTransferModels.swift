import Foundation

enum FileTransferValidationError: Error, LocalizedError, Equatable {
    case invalidProtocolVersion
    case invalidIdentifier(String)
    case invalidPath(String)
    case invalidSize(String)
    case invalidHash
    case invalidStreamCount
    case invalidEntryCount
    case duplicatePath(String)
    case invalidRange

    var errorDescription: String? {
        switch self {
        case .invalidProtocolVersion:
            return "Unsupported file transfer protocol version."
        case .invalidIdentifier(let field):
            return "\(field) is required."
        case .invalidPath(let path):
            return "Invalid relative transfer path: \(path)"
        case .invalidSize(let field):
            return "\(field) must not be negative."
        case .invalidHash:
            return "SHA-256 values must be 64 lowercase hexadecimal characters."
        case .invalidStreamCount:
            return "Stream count must be between 1 and 8."
        case .invalidEntryCount:
            return "Transfer entry count does not match the manifest."
        case .duplicatePath(let path):
            return "Transfer manifest contains a duplicate path: \(path)"
        case .invalidRange:
            return "Transfer byte or chunk range is invalid."
        }
    }
}

enum ZevLinkTransferProtocol {
    static let version = 1
    static let chunkSizeBytes: Int64 = 16 * 1024 * 1024
    static let minStreamCount = 1
    static let maxStreamCount = 8
    static let maxEntryCount = 10_000
    static let maxRelativePathUTF8Bytes = 1_024
    static let maxDisplayNameScalars = 255

    static func chunkCount(fileSize: Int64) throws -> Int64 {
        guard fileSize >= 0 else {
            throw FileTransferValidationError.invalidSize("File size")
        }
        guard fileSize > 0 else {
            return 0
        }
        return ((fileSize - 1) / chunkSizeBytes) + 1
    }

    static func byteRangeForChunk(fileSize: Int64, chunkIndex: Int64) throws -> TransferByteRange {
        guard fileSize >= 0, chunkIndex >= 0 else {
            throw FileTransferValidationError.invalidRange
        }

        let chunks = try chunkCount(fileSize: fileSize)
        guard chunkIndex < chunks else {
            throw FileTransferValidationError.invalidRange
        }

        let startOffset = chunkIndex * chunkSizeBytes
        let endOffsetExclusive = min(startOffset + chunkSizeBytes, fileSize)
        return try TransferByteRange(
            startOffset: startOffset,
            endOffsetExclusive: endOffsetExclusive
        )
    }

    static func negotiatedStreamCount(requestedStreamCount: Int, receiverMaximum: Int = maxStreamCount) throws -> Int {
        guard (minStreamCount...maxStreamCount).contains(requestedStreamCount),
              (minStreamCount...maxStreamCount).contains(receiverMaximum) else {
            throw FileTransferValidationError.invalidStreamCount
        }
        return min(requestedStreamCount, receiverMaximum)
    }
}

enum FileTransferEntryKind: String, Codable, Equatable {
    case file
    case directory
}

enum FileTransferAddressFamily: String, Codable, Equatable {
    case ipv6
    case ipv4
}

enum FileTransferDecision: String, Codable, Equatable {
    case accept
    case decline
}

enum FileTransferSavePolicy: String, Codable, Equatable {
    case ask
    case downloads
    case custom
}

enum FileTransferErrorCode: String, Codable, Equatable {
    case authenticationFailed
    case certificateMismatch
    case unsupportedProtocolVersion
    case invalidManifest
    case invalidPath
    case insufficientStorage
    case offerDeclined
    case transferCancelled
    case resumeExpired
    case invalidRange
    case hashMismatch
    case missingChunks
    case destinationChanged
    case tooManyStreams
    case internalError
}

struct TransferByteRange: Codable, Equatable {
    let startOffset: Int64
    let endOffsetExclusive: Int64

    init(startOffset: Int64, endOffsetExclusive: Int64) throws {
        guard startOffset >= 0, endOffsetExclusive >= startOffset else {
            throw FileTransferValidationError.invalidRange
        }
        self.startOffset = startOffset
        self.endOffsetExclusive = endOffsetExclusive
    }

    var length: Int64 {
        endOffsetExclusive - startOffset
    }

    func contentRangeHeader(totalSize: Int64) throws -> String {
        guard totalSize >= 0, endOffsetExclusive <= totalSize else {
            throw FileTransferValidationError.invalidRange
        }
        let endOffsetInclusive = endOffsetExclusive - 1
        guard endOffsetInclusive >= startOffset else {
            throw FileTransferValidationError.invalidRange
        }
        return "bytes \(startOffset)-\(endOffsetInclusive)/\(totalSize)"
    }
}

struct TransferChunkRange: Codable, Equatable {
    let startChunk: Int64
    let endChunkExclusive: Int64

    init(startChunk: Int64, endChunkExclusive: Int64) throws {
        guard startChunk >= 0, endChunkExclusive >= startChunk else {
            throw FileTransferValidationError.invalidRange
        }
        self.startChunk = startChunk
        self.endChunkExclusive = endChunkExclusive
    }

    var chunkCount: Int64 {
        endChunkExclusive - startChunk
    }

    func contains(chunkIndex: Int64) -> Bool {
        startChunk <= chunkIndex && chunkIndex < endChunkExclusive
    }

    func overlaps(_ other: TransferChunkRange) -> Bool {
        startChunk < other.endChunkExclusive && other.startChunk < endChunkExclusive
    }
}

struct TransferVerifiedFileRanges: Codable, Equatable {
    let fileId: String
    let verifiedRanges: [TransferChunkRange]

    init(fileId: String, verifiedRanges: [TransferChunkRange]) throws {
        guard !fileId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("File ID")
        }

        for (previous, next) in zip(verifiedRanges, verifiedRanges.dropFirst()) {
            guard previous.endChunkExclusive <= next.startChunk else {
                throw FileTransferValidationError.invalidRange
            }
        }

        self.fileId = fileId
        self.verifiedRanges = verifiedRanges
    }

    func isChunkVerified(_ chunkIndex: Int64) -> Bool {
        verifiedRanges.contains { $0.contains(chunkIndex: chunkIndex) }
    }
}

struct FileTransferEntry: Codable, Equatable {
    let fileId: String
    let kind: FileTransferEntryKind
    let relativePath: String
    let size: Int64?
    let modifiedAt: String?
    let sha256: String?
    let mediaType: String?

    init(
        fileId: String,
        kind: FileTransferEntryKind,
        relativePath: String,
        size: Int64? = nil,
        modifiedAt: String? = nil,
        sha256: String? = nil,
        mediaType: String? = nil
    ) throws {
        guard !fileId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("File ID")
        }
        guard Self.isValidRelativePath(relativePath) else {
            throw FileTransferValidationError.invalidPath(relativePath)
        }

        switch kind {
        case .file:
            guard let size, size >= 0 else {
                throw FileTransferValidationError.invalidSize("File size")
            }
            if let sha256, !Self.isLowercaseSHA256(sha256) {
                throw FileTransferValidationError.invalidHash
            }
        case .directory:
            guard size == nil, sha256 == nil, mediaType == nil else {
                throw FileTransferValidationError.invalidSize("Directory metadata")
            }
        }

        self.fileId = fileId
        self.kind = kind
        self.relativePath = relativePath
        self.size = size
        self.modifiedAt = modifiedAt
        self.sha256 = sha256
        self.mediaType = mediaType
    }

    var chunkCount: Int64 {
        guard let size else {
            return 0
        }
        return (try? ZevLinkTransferProtocol.chunkCount(fileSize: size)) ?? 0
    }

    static func isValidRelativePath(_ path: String) -> Bool {
        guard !path.isEmpty,
              path.utf8.count <= ZevLinkTransferProtocol.maxRelativePathUTF8Bytes,
              !path.hasPrefix("/"),
              !path.contains("\u{0000}"),
              !path.contains("\\") else {
            return false
        }

        return path.split(separator: "/", omittingEmptySubsequences: false).allSatisfy { component in
            !component.isEmpty && component != "." && component != ".."
        }
    }

    static func isLowercaseSHA256(_ value: String) -> Bool {
        value.count == 64 && value.allSatisfy { character in
            ("0"..."9").contains(character) || ("a"..."f").contains(character)
        }
    }
}

struct FileTransferManifest: Codable, Equatable {
    let protocolVersion: Int
    let transferId: String
    let senderDeviceId: String
    let senderName: String
    let createdAt: String
    let nonce: String
    let totalBytes: Int64
    let entryCount: Int
    let requestedStreamCount: Int
    let entries: [FileTransferEntry]

    init(
        protocolVersion: Int = ZevLinkTransferProtocol.version,
        transferId: String,
        senderDeviceId: String,
        senderName: String,
        createdAt: String,
        nonce: String,
        totalBytes: Int64,
        entryCount: Int,
        requestedStreamCount: Int,
        entries: [FileTransferEntry]
    ) throws {
        guard protocolVersion == ZevLinkTransferProtocol.version else {
            throw FileTransferValidationError.invalidProtocolVersion
        }
        guard !transferId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("Transfer ID")
        }
        guard !senderDeviceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("Sender device ID")
        }
        guard !senderName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("Sender name")
        }
        guard !createdAt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("Creation timestamp")
        }
        guard !nonce.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("Transfer nonce")
        }
        guard totalBytes >= 0 else {
            throw FileTransferValidationError.invalidSize("Total bytes")
        }
        guard entries.count <= ZevLinkTransferProtocol.maxEntryCount,
              entryCount == entries.count else {
            throw FileTransferValidationError.invalidEntryCount
        }
        guard (ZevLinkTransferProtocol.minStreamCount...ZevLinkTransferProtocol.maxStreamCount).contains(requestedStreamCount) else {
            throw FileTransferValidationError.invalidStreamCount
        }

        let computedTotalBytes = entries.reduce(Int64(0)) { partialResult, entry in
            partialResult + (entry.size ?? 0)
        }
        guard computedTotalBytes == totalBytes else {
            throw FileTransferValidationError.invalidSize("Total bytes")
        }

        var normalizedPaths = Set<String>()
        for entry in entries {
            let normalizedPath = entry.relativePath.folding(
                options: [.caseInsensitive, .diacriticInsensitive],
                locale: Locale(identifier: "en_US_POSIX")
            )
            guard normalizedPaths.insert(normalizedPath).inserted else {
                throw FileTransferValidationError.duplicatePath(entry.relativePath)
            }
        }

        self.protocolVersion = protocolVersion
        self.transferId = transferId
        self.senderDeviceId = senderDeviceId
        self.senderName = senderName
        self.createdAt = createdAt
        self.nonce = nonce
        self.totalBytes = totalBytes
        self.entryCount = entryCount
        self.requestedStreamCount = requestedStreamCount
        self.entries = entries
    }

    var fileEntries: [FileTransferEntry] {
        entries.filter { $0.kind == .file }
    }

    var totalChunkCount: Int64 {
        fileEntries.reduce(Int64(0)) { $0 + $1.chunkCount }
    }
}

struct FileTransferCandidate: Codable, Equatable {
    let id: String
    let family: FileTransferAddressFamily
    let host: String
    let port: Int
    let priority: Int
    let networkId: String

    init(
        id: String,
        family: FileTransferAddressFamily,
        host: String,
        port: Int,
        priority: Int,
        networkId: String
    ) throws {
        guard !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("Candidate ID")
        }
        guard !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("Candidate host")
        }
        guard (1...65_535).contains(port) else {
            throw FileTransferValidationError.invalidRange
        }
        guard !networkId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("Candidate network ID")
        }

        self.id = id
        self.family = family
        self.host = host
        self.port = port
        self.priority = priority
        self.networkId = networkId
    }
}

struct FileTransferOfferResponse: Codable, Equatable {
    let transferId: String
    let state: FileTransferState
    let chunkSize: Int64
    let streamCount: Int
    let resumeToken: String?
    let candidates: [FileTransferCandidate]

    init(
        transferId: String,
        state: FileTransferState,
        chunkSize: Int64,
        streamCount: Int,
        resumeToken: String?,
        candidates: [FileTransferCandidate]
    ) throws {
        guard !transferId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FileTransferValidationError.invalidIdentifier("Transfer ID")
        }
        guard chunkSize == ZevLinkTransferProtocol.chunkSizeBytes else {
            throw FileTransferValidationError.invalidRange
        }
        guard (ZevLinkTransferProtocol.minStreamCount...ZevLinkTransferProtocol.maxStreamCount).contains(streamCount) else {
            throw FileTransferValidationError.invalidStreamCount
        }
        guard state != .accepted || !(resumeToken?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) else {
            throw FileTransferValidationError.invalidIdentifier("Resume token")
        }

        self.transferId = transferId
        self.state = state
        self.chunkSize = chunkSize
        self.streamCount = streamCount
        self.resumeToken = resumeToken
        self.candidates = candidates
    }
}
