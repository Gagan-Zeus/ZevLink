import Foundation

enum AndroidFileTransferHTTPClient {
    static func send(
        manifest: FileTransferManifest,
        sources: [FileTransferLocalFileSource],
        endpoint: AndroidReceiverEndpoint,
        token: String,
        progress: @escaping @Sendable (Int64) -> Void
    ) async throws {
        try await withTaskCancellationHandler {
            try await sendTransfer(
                manifest: manifest,
                sources: sources,
                endpoint: endpoint,
                token: token,
                progress: progress
            )
        } onCancel: {
            Task.detached(priority: .utility) {
                try? await cancel(
                    transferId: manifest.transferId,
                    endpoint: endpoint,
                    token: token
                )
            }
        }
    }

    static func cancel(
        transferId: String,
        endpoint: AndroidReceiverEndpoint,
        token: String
    ) async throws {
        _ = try await postJSON(
            path: "/transfer/cancel",
            json: try JSONSerialization.data(withJSONObject: ["transferId": transferId]),
            endpoint: endpoint,
            token: token,
            transferId: transferId
        )
    }

    private static func sendTransfer(
        manifest: FileTransferManifest,
        sources: [FileTransferLocalFileSource],
        endpoint: AndroidReceiverEndpoint,
        token: String,
        progress: @escaping @Sendable (Int64) -> Void
    ) async throws {
        let response = try await awaitOfferDecision(
            manifest: manifest,
            endpoint: endpoint,
            token: token
        )
        let offer = try JSONDecoder().decode(FileTransferOfferResponse.self, from: response.body)

        let rangesData = try await postJSON(
            path: "/transfer/ranges",
            json: try JSONSerialization.data(withJSONObject: ["transferId": manifest.transferId]),
            endpoint: endpoint,
            token: token,
            transferId: manifest.transferId
        ).body
        let verifiedRanges = (try? JSONDecoder().decode([TransferVerifiedFileRanges].self, from: rangesData)) ?? []

        let sourceByFileId = Dictionary(uniqueKeysWithValues: sources.map { ($0.fileId, $0) })
        var work: [(FileTransferLocalFileSource, Int64)] = []
        for entry in manifest.fileEntries {
            guard let source = sourceByFileId[entry.fileId] else { continue }
            let verified = verifiedRanges.first(where: { $0.fileId == entry.fileId })
            for chunkIndex in 0..<entry.chunkCount where verified?.isChunkVerified(chunkIndex) != true {
                work.append((source, chunkIndex))
            }
        }

        var sentBytes = Int64(0)
        var samples: [FileTransferStreamBenchmarkSample] = []
        let candidates = FileTransferAdaptiveStreamSelector.candidates.filter { $0 <= offer.streamCount }
        for streamCount in candidates {
            let sampleCount = min(work.count, streamCount * 2)
            guard sampleCount >= streamCount else { break }
            let sampleWork = Array(work.prefix(sampleCount))
            work.removeFirst(sampleCount)
            let startedAt = CFAbsoluteTimeGetCurrent()
            let bytes = try await upload(
                sampleWork,
                streamCount: streamCount,
                endpoint: endpoint,
                token: token,
                transferId: manifest.transferId,
                onChunkComplete: {
                    sentBytes += $0
                    progress(sentBytes)
                }
            )
            samples.append(
                FileTransferStreamBenchmarkSample(
                    streamCount: streamCount,
                    bytes: bytes,
                    duration: CFAbsoluteTimeGetCurrent() - startedAt
                )
            )
        }

        let selectedStreamCount = min(
            offer.streamCount,
            FileTransferAdaptiveStreamSelector().chooseBestStreamCount(from: samples)
        )
        let benchmarkSummary = samples.map {
            "\($0.streamCount)=\(String(format: "%.1f", $0.bytesPerSecond / 1_000_000)) MB/s"
        }.joined(separator: ", ")
        NSLog("ZevLink stream benchmark [%@], selected %d", benchmarkSummary, selectedStreamCount)
        _ = try await upload(
            work,
            streamCount: selectedStreamCount,
            endpoint: endpoint,
            token: token,
            transferId: manifest.transferId,
            onChunkComplete: {
                sentBytes += $0
                progress(sentBytes)
            }
        )

        let completeResponse = try await postJSON(
            path: "/transfer/complete",
            json: try JSONSerialization.data(withJSONObject: ["transferId": manifest.transferId]),
            endpoint: endpoint,
            token: token,
            transferId: manifest.transferId
        )
        guard (200..<300).contains(completeResponse.statusCode) else {
            throw FileTransferHTTPClientError.server("Android could not complete transfer (\(completeResponse.statusCode)).")
        }
    }

    private static func awaitOfferDecision(
        manifest: FileTransferManifest,
        endpoint: AndroidReceiverEndpoint,
        token: String
    ) async throws -> HTTPPayload {
        var response = try await postJSON(
            path: "/transfer/offer",
            json: try JSONEncoder().encode(manifest),
            endpoint: endpoint,
            token: token,
            transferId: manifest.transferId
        )
        while response.statusCode == 202 {
            try Task.checkCancellation()
            try await Task.sleep(for: .milliseconds(500))
            response = try await postJSON(
                path: "/transfer/offer-status",
                json: try JSONSerialization.data(withJSONObject: ["transferId": manifest.transferId]),
                endpoint: endpoint,
                token: token,
                transferId: manifest.transferId
            )
        }
        if response.statusCode == 403 {
            throw FileTransferHTTPClientError.server("File transfer was declined on Android.")
        }
        guard (200..<300).contains(response.statusCode) else {
            throw FileTransferHTTPClientError.server("Android rejected offer (\(response.statusCode)).")
        }
        return response
    }

    private static func upload(
        _ work: [(FileTransferLocalFileSource, Int64)],
        streamCount: Int,
        endpoint: AndroidReceiverEndpoint,
        token: String,
        transferId: String,
        onChunkComplete: (Int64) -> Void
    ) async throws -> Int64 {
        var transferredBytes = Int64(0)
        try await withThrowingTaskGroup(of: Int64.self) { group in
            var active = 0
            var iterator = work.makeIterator()

            func scheduleNext() {
                guard active < streamCount, let next = iterator.next() else { return }
                active += 1
                group.addTask {
                    try Task.checkCancellation()
                    let payload = try FileTransferMacSender().readChunk(
                        source: next.0,
                        chunkIndex: next.1,
                        includeHash: true
                    )
                    try await postChunk(payload, endpoint: endpoint, token: token, transferId: transferId)
                    return payload.byteRange.length
                }
            }

            for _ in 0..<streamCount { scheduleNext() }
            while let completed = try await group.next() {
                active -= 1
                transferredBytes += completed
                onChunkComplete(completed)
                scheduleNext()
            }
        }
        return transferredBytes
    }

    private static func postJSON(
        path: String,
        json: Data,
        endpoint: AndroidReceiverEndpoint,
        token: String,
        transferId: String
    ) async throws -> HTTPPayload {
        var request = try request(path: path, endpoint: endpoint, token: token, transferId: transferId)
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = json
        let (body, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw FileTransferHTTPClientError.server("Android returned a non-HTTP response.")
        }
        return HTTPPayload(statusCode: httpResponse.statusCode, body: body)
    }

    private static func postChunk(
        _ payload: FileTransferChunkPayload,
        endpoint: AndroidReceiverEndpoint,
        token: String,
        transferId: String
    ) async throws {
        var request = try request(path: "/transfer/chunk", endpoint: endpoint, token: token, transferId: transferId)
        request.setValue(payload.fileId, forHTTPHeaderField: "X-ZevLink-File-Id")
        request.setValue(String(payload.chunkIndex), forHTTPHeaderField: "X-ZevLink-Chunk-Index")
        if !payload.sha256.isEmpty {
            request.setValue(payload.sha256, forHTTPHeaderField: "X-ZevLink-Chunk-SHA256")
        }
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.httpBody = payload.data
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200..<300).contains(httpResponse.statusCode) else {
            throw FileTransferHTTPClientError.server("Android rejected chunk \(payload.chunkIndex).")
        }
    }

    private static func request(
        path: String,
        endpoint: AndroidReceiverEndpoint,
        token: String,
        transferId: String
    ) throws -> URLRequest {
        guard let url = url(for: endpoint, path: path) else {
            throw FileTransferHTTPClientError.invalidURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 120
        request.setValue(token, forHTTPHeaderField: "X-ZevClip-Token")
        request.setValue(transferId, forHTTPHeaderField: "X-ZevLink-Transfer-Id")
        return request
    }

    private static func url(for endpoint: AndroidReceiverEndpoint, path: String) -> URL? {
        if endpoint.host.contains(":") {
            return URL(string: "http://[\(endpoint.host)]:\(endpoint.port)\(path)")
        }
        var components = URLComponents()
        components.scheme = "http"
        components.host = endpoint.host
        components.port = endpoint.port
        components.path = path
        return components.url
    }

    private struct HTTPPayload {
        let statusCode: Int
        let body: Data
    }
}

enum FileTransferHTTPClientError: Error, LocalizedError {
    case invalidURL
    case server(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Transfer URL is invalid."
        case .server(let message):
            return message
        }
    }
}
