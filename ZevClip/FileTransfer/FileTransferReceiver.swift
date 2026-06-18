import CryptoKit
import Foundation

enum FileTransferReceiverError: Error, LocalizedError {
    case transferAlreadyExists
    case transferNotFound
    case fileNotFound
    case invalidChunk
    case hashMismatch
    case missingChunks
    case ioFailure(String)

    var errorDescription: String? {
        switch self {
        case .transferAlreadyExists:
            return "Transfer already exists."
        case .transferNotFound:
            return "Transfer was not found."
        case .fileNotFound:
            return "Transfer file was not found."
        case .invalidChunk:
            return "Chunk range or size is invalid."
        case .hashMismatch:
            return "SHA-256 verification failed."
        case .missingChunks:
            return "Transfer is missing chunks."
        case .ioFailure(let message):
            return message
        }
    }
}

struct FileTransferCompletedFile: Equatable {
    let fileId: String
    let url: URL
    let sha256: String
}

struct FileTransferCompletionResult: Equatable {
    let transferId: String
    let destinationRoot: URL
    let files: [FileTransferCompletedFile]
}

final class FileTransferReceiver {
    private final class Session {
        let manifest: FileTransferManifest
        let tempRoot: URL
        let finalRoot: URL
        let resumeToken: String
        var stateMachine: FileTransferStateMachine
        var verifiedChunksByFileId: [String: Set<Int64>]

        init(
            manifest: FileTransferManifest,
            tempRoot: URL,
            finalRoot: URL,
            resumeToken: String,
            streamCount: Int
        ) throws {
            self.manifest = manifest
            self.tempRoot = tempRoot
            self.finalRoot = finalRoot
            self.resumeToken = resumeToken
            self.stateMachine = try FileTransferStateMachine(
                initialState: .accepted,
                totalBytes: manifest.totalBytes,
                selectedStreamCount: streamCount
            )
            self.verifiedChunksByFileId = Dictionary(
                uniqueKeysWithValues: manifest.fileEntries.map { ($0.fileId, Set<Int64>()) }
            )
        }
    }

    private let lock = NSLock()
    private let fileManager: FileManager
    private let downloadsRoot: URL
    private var sessionsByTransferId: [String: Session] = [:]

    init(
        downloadsRoot: URL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
            .appendingPathComponent("ZevLink Transfers", isDirectory: true),
        fileManager: FileManager = .default
    ) {
        self.downloadsRoot = downloadsRoot
        self.fileManager = fileManager
    }

    func accept(
        manifest: FileTransferManifest,
        requestedStreamCount: Int? = nil,
        receiverMaximumStreams: Int = ZevLinkTransferProtocol.maxStreamCount
    ) throws -> FileTransferOfferResponse {
        let streamCount = try ZevLinkTransferProtocol.negotiatedStreamCount(
            requestedStreamCount: requestedStreamCount ?? manifest.requestedStreamCount,
            receiverMaximum: receiverMaximumStreams
        )
        let resumeToken = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let finalRoot = try uniqueDestinationRoot(for: manifest)
        let tempRoot = downloadsRoot
            .appendingPathComponent(".partial", isDirectory: true)
            .appendingPathComponent("\(manifest.transferId)-\(resumeToken)", isDirectory: true)

        lock.lock()
        defer { lock.unlock() }

        guard sessionsByTransferId[manifest.transferId] == nil else {
            throw FileTransferReceiverError.transferAlreadyExists
        }

        try prepareStorage(for: manifest, tempRoot: tempRoot)
        let session = try Session(
            manifest: manifest,
            tempRoot: tempRoot,
            finalRoot: finalRoot,
            resumeToken: resumeToken,
            streamCount: streamCount
        )
        sessionsByTransferId[manifest.transferId] = session

        return try FileTransferOfferResponse(
            transferId: manifest.transferId,
            state: .accepted,
            chunkSize: ZevLinkTransferProtocol.chunkSizeBytes,
            streamCount: streamCount,
            resumeToken: resumeToken,
            candidates: []
        )
    }

    func verifiedRanges(transferId: String) throws -> [TransferVerifiedFileRanges] {
        let session = try session(for: transferId)
        return try session.manifest.fileEntries.map { entry in
            try TransferVerifiedFileRanges(
                fileId: entry.fileId,
                verifiedRanges: Self.compactRanges(from: session.verifiedChunksByFileId[entry.fileId] ?? [])
            )
        }
    }

    func writeChunk(
        transferId: String,
        fileId: String,
        chunkIndex: Int64,
        data: Data,
        chunkSHA256: String? = nil,
        activeStreamCount: Int = 1
    ) throws {
        let session = try session(for: transferId)
        guard let entry = session.manifest.fileEntries.first(where: { $0.fileId == fileId }),
              let fileSize = entry.size else {
            throw FileTransferReceiverError.fileNotFound
        }
        if session.verifiedChunksByFileId[fileId]?.contains(chunkIndex) == true {
            return
        }

        let range = try ZevLinkTransferProtocol.byteRangeForChunk(fileSize: fileSize, chunkIndex: chunkIndex)
        guard Int64(data.count) == range.length else {
            throw FileTransferReceiverError.invalidChunk
        }
        if let chunkSHA256, Self.sha256Hex(data: data) != chunkSHA256 {
            throw FileTransferReceiverError.hashMismatch
        }

        try positionedWrite(data, to: fileURL(for: entry, root: session.tempRoot), offset: range.startOffset)

        lock.lock()
        defer { lock.unlock() }

        guard let lockedSession = sessionsByTransferId[transferId] else {
            throw FileTransferReceiverError.transferNotFound
        }
        if lockedSession.verifiedChunksByFileId[fileId, default: []].insert(chunkIndex).inserted {
            if lockedSession.stateMachine.state == .accepted || lockedSession.stateMachine.state == .interrupted {
                try lockedSession.stateMachine.markChunkRequestStarted(activeStreamCount: activeStreamCount)
            }
            try lockedSession.stateMachine.markChunkVerified(byteCount: range.length)
        }
    }

    func complete(transferId: String) throws -> FileTransferCompletionResult {
        let session = try session(for: transferId)
        var completedFiles: [FileTransferCompletedFile] = []

        for entry in session.manifest.fileEntries {
            guard let expectedSize = entry.size else {
                continue
            }
            let expectedChunks = try ZevLinkTransferProtocol.chunkCount(fileSize: expectedSize)
            let verifiedChunks = session.verifiedChunksByFileId[entry.fileId] ?? []
            guard verifiedChunks.count == expectedChunks else {
                throw FileTransferReceiverError.missingChunks
            }

            let tempURL = fileURL(for: entry, root: session.tempRoot)
            let actualSHA256 = try Self.sha256Hex(fileURL: tempURL)
            if let expectedSHA256 = entry.sha256, expectedSHA256 != actualSHA256 {
                throw FileTransferReceiverError.hashMismatch
            }
            completedFiles.append(
                FileTransferCompletedFile(
                    fileId: entry.fileId,
                    url: session.finalRoot.appendingPathComponent(entry.relativePath, isDirectory: false),
                    sha256: actualSHA256
                )
            )
        }

        try markVerifyingAndComplete(transferId: transferId)
        try fileManager.createDirectory(
            at: session.finalRoot.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try fileManager.moveItem(at: session.tempRoot, to: session.finalRoot)

        lock.lock()
        sessionsByTransferId.removeValue(forKey: transferId)
        lock.unlock()

        return FileTransferCompletionResult(
            transferId: transferId,
            destinationRoot: session.finalRoot,
            files: completedFiles
        )
    }

    func cancel(transferId: String) throws {
        let session = try session(for: transferId)
        lock.lock()
        sessionsByTransferId.removeValue(forKey: transferId)
        lock.unlock()
        try? fileManager.removeItem(at: session.tempRoot)
    }

    private func session(for transferId: String) throws -> Session {
        lock.lock()
        defer { lock.unlock() }
        guard let session = sessionsByTransferId[transferId] else {
            throw FileTransferReceiverError.transferNotFound
        }
        return session
    }

    private func prepareStorage(for manifest: FileTransferManifest, tempRoot: URL) throws {
        try fileManager.createDirectory(
            at: downloadsRoot,
            withIntermediateDirectories: true
        )

        if let available = try downloadsRoot
            .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage,
           available < manifest.totalBytes {
            throw FileTransferReceiverError.ioFailure(
                "Not enough disk space. Need \(ByteCountFormatter.string(fromByteCount: manifest.totalBytes, countStyle: .file)); available \(ByteCountFormatter.string(fromByteCount: available, countStyle: .file))."
            )
        }

        try fileManager.createDirectory(at: tempRoot, withIntermediateDirectories: true)
        for entry in manifest.entries {
            let url = fileURL(for: entry, root: tempRoot)
            switch entry.kind {
            case .directory:
                try fileManager.createDirectory(at: url, withIntermediateDirectories: true)
            case .file:
                try fileManager.createDirectory(
                    at: url.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                let fileDescriptor = open(url.path, O_CREAT | O_TRUNC | O_RDWR, S_IRUSR | S_IWUSR)
                guard fileDescriptor >= 0 else {
                    throw FileTransferReceiverError.ioFailure("Could not create \(url.path): \(String(cString: strerror(errno)))")
                }
                defer { close(fileDescriptor) }
                if ftruncate(fileDescriptor, entry.size ?? 0) != 0 {
                    throw FileTransferReceiverError.ioFailure("Could not preallocate \(url.path): \(String(cString: strerror(errno)))")
                }
            }
        }
    }

    private func uniqueDestinationRoot(for manifest: FileTransferManifest) throws -> URL {
        let baseName = sanitizedName(manifest.senderName.isEmpty ? manifest.transferId : manifest.senderName)
        var candidate = downloadsRoot.appendingPathComponent(baseName, isDirectory: true)
        var suffix = 2
        while fileManager.fileExists(atPath: candidate.path) {
            candidate = downloadsRoot.appendingPathComponent("\(baseName) \(suffix)", isDirectory: true)
            suffix += 1
        }
        return candidate
    }

    private func sanitizedName(_ name: String) -> String {
        let illegal = CharacterSet(charactersIn: "/:\\\0").union(.newlines)
        let cleaned = name.components(separatedBy: illegal).joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "Transfer" : cleaned
    }

    private func fileURL(for entry: FileTransferEntry, root: URL) -> URL {
        root.appendingPathComponent(entry.relativePath, isDirectory: entry.kind == .directory)
    }

    private func positionedWrite(_ data: Data, to url: URL, offset: Int64) throws {
        let fileDescriptor = open(url.path, O_WRONLY)
        guard fileDescriptor >= 0 else {
            throw FileTransferReceiverError.ioFailure("Could not open \(url.path): \(String(cString: strerror(errno)))")
        }
        defer { close(fileDescriptor) }

        try data.withUnsafeBytes { buffer in
            guard let baseAddress = buffer.baseAddress else {
                return
            }
            var written = 0
            while written < data.count {
                let result = pwrite(
                    fileDescriptor,
                    baseAddress.advanced(by: written),
                    data.count - written,
                    off_t(offset) + off_t(written)
                )
                guard result > 0 else {
                    throw FileTransferReceiverError.ioFailure("Could not write \(url.path): \(String(cString: strerror(errno)))")
                }
                written += result
            }
        }
    }

    private func markVerifyingAndComplete(transferId: String) throws {
        lock.lock()
        defer { lock.unlock() }
        guard let session = sessionsByTransferId[transferId] else {
            throw FileTransferReceiverError.transferNotFound
        }
        try session.stateMachine.beginVerification()
        try session.stateMachine.complete()
    }

    private static func compactRanges(from chunks: Set<Int64>) throws -> [TransferChunkRange] {
        let sorted = chunks.sorted()
        guard let first = sorted.first else {
            return []
        }

        var ranges: [TransferChunkRange] = []
        var start = first
        var previous = first
        for chunk in sorted.dropFirst() {
            if chunk == previous + 1 {
                previous = chunk
            } else {
                ranges.append(try TransferChunkRange(startChunk: start, endChunkExclusive: previous + 1))
                start = chunk
                previous = chunk
            }
        }
        ranges.append(try TransferChunkRange(startChunk: start, endChunkExclusive: previous + 1))
        return ranges
    }

    static func sha256Hex(data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func sha256Hex(fileURL: URL) throws -> String {
        guard let fileHandle = FileHandle(forReadingAtPath: fileURL.path) else {
            throw FileTransferReceiverError.ioFailure("Could not open \(fileURL.path) for hashing.")
        }
        defer { try? fileHandle.close() }

        var hasher = SHA256()
        while true {
            let data = try fileHandle.read(upToCount: 1024 * 1024) ?? Data()
            if data.isEmpty {
                break
            }
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
