import CryptoKit
import Foundation

struct FileTransferLocalFileSource {
    let fileId: String
    let url: URL
    let relativePath: String
    let mediaType: String?

    var size: Int64 {
        Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }

    func entry(includeHash: Bool = true) throws -> FileTransferEntry {
        try FileTransferEntry(
            fileId: fileId,
            kind: .file,
            relativePath: relativePath,
            size: size,
            sha256: includeHash ? FileTransferReceiver.sha256Hex(fileURL: url) : nil,
            mediaType: mediaType
        )
    }
}

struct FileTransferChunkPayload {
    let fileId: String
    let chunkIndex: Int64
    let byteRange: TransferByteRange
    let data: Data
    let sha256: String
}

final class FileTransferMacSender {
    static func fileSources(from urls: [URL]) throws -> [FileTransferLocalFileSource] {
        var sources: [FileTransferLocalFileSource] = []
        let fileManager = FileManager.default

        for rootURL in urls {
            let rootName = sanitizedRelativeComponent(rootURL.lastPathComponent)
            var isDirectory: ObjCBool = false
            guard fileManager.fileExists(atPath: rootURL.path, isDirectory: &isDirectory) else {
                continue
            }

            if isDirectory.boolValue {
                let resourceKeys: Set<URLResourceKey> = [.isRegularFileKey, .isDirectoryKey]
                guard let enumerator = fileManager.enumerator(
                    at: rootURL,
                    includingPropertiesForKeys: Array(resourceKeys),
                    options: [.skipsHiddenFiles, .skipsPackageDescendants]
                ) else {
                    continue
                }
                for case let fileURL as URL in enumerator {
                    let values = try fileURL.resourceValues(forKeys: resourceKeys)
                    guard values.isRegularFile == true else {
                        continue
                    }
                    let relative = fileURL.path.replacingOccurrences(of: rootURL.path + "/", with: "")
                    let path = ([rootName] + relative.split(separator: "/").map { sanitizedRelativeComponent(String($0)) })
                        .joined(separator: "/")
                    sources.append(
                        FileTransferLocalFileSource(
                            fileId: UUID().uuidString,
                            url: fileURL,
                            relativePath: path,
                            mediaType: nil
                        )
                    )
                }
            } else {
                sources.append(
                    FileTransferLocalFileSource(
                        fileId: UUID().uuidString,
                        url: rootURL,
                        relativePath: rootName,
                        mediaType: nil
                    )
                )
            }
        }

        return sources
    }

    func manifest(
        transferId: String = UUID().uuidString,
        senderDeviceId: String,
        senderName: String,
        sources: [FileTransferLocalFileSource],
        requestedStreamCount: Int,
        includeFileHashes: Bool = true
    ) throws -> FileTransferManifest {
        let entries = try sources.map { try $0.entry(includeHash: includeFileHashes) }
        return try FileTransferManifest(
            transferId: transferId,
            senderDeviceId: senderDeviceId,
            senderName: senderName,
            createdAt: ISO8601DateFormatter().string(from: Date()),
            nonce: UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
            totalBytes: entries.reduce(Int64(0)) { $0 + ($1.size ?? 0) },
            entryCount: entries.count,
            requestedStreamCount: requestedStreamCount,
            entries: entries
        )
    }

    func readChunk(
        source: FileTransferLocalFileSource,
        chunkIndex: Int64,
        includeHash: Bool = true
    ) throws -> FileTransferChunkPayload {
        let fileSize = source.size
        let range = try ZevLinkTransferProtocol.byteRangeForChunk(fileSize: fileSize, chunkIndex: chunkIndex)
        let fileDescriptor = open(source.url.path, O_RDONLY)
        guard fileDescriptor >= 0 else {
            throw FileTransferReceiverError.ioFailure("Could not open \(source.url.path): \(String(cString: strerror(errno)))")
        }
        defer { close(fileDescriptor) }

        var data = Data(count: Int(range.length))
        try data.withUnsafeMutableBytes { buffer in
            guard let baseAddress = buffer.baseAddress else {
                return
            }
            var readBytes = 0
            while readBytes < Int(range.length) {
                let result = pread(
                    fileDescriptor,
                    baseAddress.advanced(by: readBytes),
                    Int(range.length) - readBytes,
                    off_t(range.startOffset) + off_t(readBytes)
                )
                guard result > 0 else {
                    throw FileTransferReceiverError.ioFailure("Could not read \(source.url.path): \(String(cString: strerror(errno)))")
                }
                readBytes += result
            }
        }

        return FileTransferChunkPayload(
            fileId: source.fileId,
            chunkIndex: chunkIndex,
            byteRange: range,
            data: data,
            sha256: includeHash ? FileTransferReceiver.sha256Hex(data: data) : ""
        )
    }
}

private func sanitizedRelativeComponent(_ component: String) -> String {
    let illegal = CharacterSet(charactersIn: "/:\\\0").union(.newlines)
    let cleaned = component
        .components(separatedBy: illegal)
        .joined(separator: " ")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    return cleaned.isEmpty ? "file" : String(cleaned.prefix(ZevLinkTransferProtocol.maxDisplayNameScalars))
}

struct FileTransferStreamBenchmarkSample: Equatable {
    let streamCount: Int
    let bytes: Int64
    let duration: TimeInterval

    var bytesPerSecond: Double {
        guard duration > 0 else {
            return 0
        }
        return Double(bytes) / duration
    }
}

struct FileTransferAdaptiveStreamSelector {
    static let candidates = [1, 2, 4, 8]

    func chooseBestStreamCount(from samples: [FileTransferStreamBenchmarkSample]) -> Int {
        let validSamples = samples.filter { Self.candidates.contains($0.streamCount) && $0.bytes > 0 && $0.duration > 0 }
        guard let best = validSamples.max(by: { $0.bytesPerSecond < $1.bytesPerSecond }) else {
            return 4
        }
        return best.streamCount
    }
}

final class FileTransferMacStreamBenchmarker {
    private let sender: FileTransferMacSender

    init(sender: FileTransferMacSender = FileTransferMacSender()) {
        self.sender = sender
    }

    func benchmarkSource(
        _ source: FileTransferLocalFileSource,
        streamCounts: [Int] = FileTransferAdaptiveStreamSelector.candidates
    ) throws -> [FileTransferStreamBenchmarkSample] {
        let chunkCount = try ZevLinkTransferProtocol.chunkCount(fileSize: source.size)
        let chunkIndexes = Array(0..<chunkCount)

        return try streamCounts.map { streamCount in
            guard FileTransferAdaptiveStreamSelector.candidates.contains(streamCount) else {
                throw FileTransferValidationError.invalidStreamCount
            }

            let lock = NSLock()
            var nextIndex = 0
            var firstError: Error?
            let start = CFAbsoluteTimeGetCurrent()
            DispatchQueue.concurrentPerform(iterations: streamCount) { _ in
                while true {
                    lock.lock()
                    if nextIndex >= chunkIndexes.count || firstError != nil {
                        lock.unlock()
                        return
                    }
                    let chunkIndex = chunkIndexes[nextIndex]
                    nextIndex += 1
                    lock.unlock()

                    do {
                        _ = try sender.readChunk(source: source, chunkIndex: chunkIndex)
                    } catch {
                        lock.lock()
                        firstError = error
                        lock.unlock()
                        return
                    }
                }
            }
            if let firstError {
                throw firstError
            }

            return FileTransferStreamBenchmarkSample(
                streamCount: streamCount,
                bytes: source.size,
                duration: CFAbsoluteTimeGetCurrent() - start
            )
        }
    }
}
