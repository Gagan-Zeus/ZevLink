import Foundation

struct FileTransferHTTPResponse {
    let status: String
    let contentType: String
    let body: Data

    init(status: String, text: String) {
        self.status = status
        self.contentType = "text/plain; charset=utf-8"
        self.body = Data(text.utf8)
    }

    init(status: String = "200 OK", json: Data) {
        self.status = status
        self.contentType = "application/json; charset=utf-8"
        self.body = json
    }
}

struct FileTransferDisplayState: Equatable {
    var title: String = "File transfer idle."
    var detail: String = "No file transfer yet."
    var progress: Double = 0
    var speedBytesPerSecond: Double = 0
    var etaSeconds: Double?
    var canCancel: Bool = false
    var transferId: String?

    static let idle = FileTransferDisplayState()
}

final class FileTransferService: ObservableObject {
    @Published private(set) var displayState: FileTransferDisplayState = .idle
    var onCancelPeerTransfer: ((String) -> Void)?

    private let receiver = FileTransferReceiver()
    private let sender = FileTransferMacSender()
    private let callbackQueue = DispatchQueue.main
    private let workerQueue = DispatchQueue(label: "com.zevlink.file-transfer-service", attributes: .concurrent)
    private let stateLock = NSLock()
    private var incomingProgress: [String: IncomingProgress] = [:]
    private var pendingIncomingOffers: [String: FileTransferManifest] = [:]
    private var acceptedIncomingOffers: [String: FileTransferOfferResponse] = [:]
    private var declinedIncomingOffers = Set<String>()
    private var cancelledTransferIds = Set<String>()
    private var outgoingTask: Task<Void, Never>?
    private var outgoingTransferId: String?

    private var autoAcceptIncoming = true
    private var saveIncomingToDownloads = true

    func updateSettings(autoAcceptIncoming: Bool, saveIncomingToDownloads: Bool) {
        stateLock.lock()
        self.autoAcceptIncoming = autoAcceptIncoming
        self.saveIncomingToDownloads = saveIncomingToDownloads
        stateLock.unlock()
    }

    func handleIncomingRequest(
        path: String,
        headers: [String: String],
        body: Data
    ) -> FileTransferHTTPResponse {
        do {
            switch path.components(separatedBy: "?").first {
            case "/transfer/offer":
                return try handleIncomingOffer(body: body)
            case "/transfer/offer-status":
                return try handleIncomingOfferStatus(headers: headers, body: body)
            case "/transfer/ranges":
                return try handleIncomingRanges(headers: headers, body: body)
            case "/transfer/chunk":
                return try handleIncomingChunk(headers: headers, body: body)
            case "/transfer/complete":
                return try handleIncomingComplete(headers: headers, body: body)
            case "/transfer/cancel":
                return try handleIncomingCancel(headers: headers, body: body)
            default:
                return FileTransferHTTPResponse(status: "404 Not Found", text: "Unknown transfer path.")
            }
        } catch FileTransferReceiverError.hashMismatch {
            return FileTransferHTTPResponse(status: "409 Conflict", text: "SHA-256 verification failed.")
        } catch FileTransferReceiverError.missingChunks {
            return FileTransferHTTPResponse(status: "409 Conflict", text: "Transfer is missing chunks.")
        } catch FileTransferReceiverError.invalidChunk, FileTransferValidationError.invalidRange {
            return FileTransferHTTPResponse(status: "416 Range Not Satisfiable", text: "Chunk range is invalid.")
        } catch {
            return FileTransferHTTPResponse(status: "400 Bad Request", text: error.localizedDescription)
        }
    }

    func cancelActiveTransfer() {
        guard let transferId = displayState.transferId else { return }
        cancelTransfer(transferId: transferId)
    }

    func cancelTransfer(transferId: String) {
        markTransferCancelled(transferId)
        onCancelPeerTransfer?(transferId)
        try? receiver.cancel(transferId: transferId)
        if isOutgoingTransfer(transferId) {
            outgoingTask?.cancel()
            outgoingTask = nil
            setOutgoingTransferId(nil)
        }
        MacNotificationPresenter.shared.clearFileTransfer(transferId: transferId)
        if displayState.transferId == transferId {
            displayState = .idle
        }
    }

    func acceptIncomingTransfer(transferId: String) {
        workerQueue.async { [weak self] in
            guard let self else { return }
            self.stateLock.lock()
            let manifest = self.pendingIncomingOffers[transferId]
            self.stateLock.unlock()
            guard let manifest else { return }

            do {
                let response = try self.receiver.accept(
                    manifest: manifest,
                    requestedStreamCount: manifest.requestedStreamCount,
                    receiverMaximumStreams: ZevLinkTransferProtocol.maxStreamCount
                )
                let progress = IncomingProgress(
                    totalBytes: manifest.totalBytes,
                    verifiedBytes: 0,
                    startedAt: Date(),
                    fileName: Self.transferTitle(manifest)
                )
                self.stateLock.lock()
                guard self.pendingIncomingOffers.removeValue(forKey: transferId) != nil else {
                    self.stateLock.unlock()
                    try? self.receiver.cancel(transferId: transferId)
                    return
                }
                self.declinedIncomingOffers.remove(transferId)
                self.cancelledTransferIds.remove(transferId)
                self.acceptedIncomingOffers[transferId] = response
                self.incomingProgress[transferId] = progress
                self.stateLock.unlock()
                self.publishIncomingProgress(transferId: transferId, progress: progress)
            } catch {
                self.stateLock.lock()
                self.pendingIncomingOffers.removeValue(forKey: transferId)
                self.declinedIncomingOffers.insert(transferId)
                self.stateLock.unlock()
                DispatchQueue.main.async {
                    MacNotificationPresenter.shared.clearFileTransfer(transferId: transferId)
                }
            }
        }
    }

    func declineIncomingTransfer(transferId: String) {
        stateLock.lock()
        let wasPending = pendingIncomingOffers.removeValue(forKey: transferId) != nil
        if wasPending {
            declinedIncomingOffers.insert(transferId)
            cancelledTransferIds.insert(transferId)
        }
        stateLock.unlock()
        guard wasPending else { return }
        MacNotificationPresenter.shared.clearFileTransfer(transferId: transferId)
        if displayState.transferId == transferId {
            displayState = .idle
        }
    }

    func sendFilesToPhone(urls: [URL], endpoint: AndroidReceiverEndpoint?, token: String) {
        guard outgoingTask == nil else {
            displayState.detail = "A file transfer is already running."
            return
        }
        guard let endpoint else {
            displayState.detail = "Android receiver is not connected."
            return
        }
        guard !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            displayState.detail = "Pairing token is empty."
            return
        }

        outgoingTask = Task { [weak self] in
            guard let self else { return }
            var activeTransferId: String?
            do {
                let sources = try FileTransferMacSender.fileSources(from: urls)
                guard !sources.isEmpty else {
                    await self.finishOutgoing(message: "No regular files were selected.")
                    return
                }
                let manifest = try self.sender.manifest(
                    senderDeviceId: DeviceIdentityStore.loadOrCreateDeviceId(),
                    senderName: Host.current().localizedName ?? "Mac",
                    sources: sources,
                    requestedStreamCount: 8,
                    includeFileHashes: false
                )
                activeTransferId = manifest.transferId
                self.setOutgoingTransferId(manifest.transferId)
                self.clearTransferCancellation(manifest.transferId)
                let manifestFileName = Self.transferTitle(manifest)
                await self.updateOutgoing(
                    title: "Sending to Android",
                    fileName: manifestFileName,
                    verifiedBytes: 0,
                    totalBytes: manifest.totalBytes,
                    transferId: manifest.transferId,
                    startedAt: Date()
                )
                try await AndroidFileTransferHTTPClient.send(
                    manifest: manifest,
                    sources: sources,
                    endpoint: endpoint,
                    token: token,
                    progress: { [weak self] sentBytes in
                        Task { @MainActor in
                            self?.updateOutgoing(
                                title: "Sending to Android",
                                fileName: manifestFileName,
                                verifiedBytes: sentBytes,
                                totalBytes: manifest.totalBytes,
                                transferId: manifest.transferId,
                                startedAt: nil
                            )
                        }
                    }
                )
                await MainActor.run {
                    MacNotificationPresenter.shared.showFileTransferSent(
                        transferId: manifest.transferId,
                        fileName: manifestFileName
                    )
                }
                await self.finishOutgoing(message: "Sent \(manifest.entryCount) item(s) to Android.")
            } catch is CancellationError {
                if let activeTransferId {
                    await MainActor.run {
                        MacNotificationPresenter.shared.clearFileTransfer(transferId: activeTransferId)
                    }
                }
                await self.finishOutgoing(message: "File transfer cancelled.")
            } catch {
                if let activeTransferId {
                    await MainActor.run {
                        MacNotificationPresenter.shared.clearFileTransfer(transferId: activeTransferId)
                    }
                }
                await self.finishOutgoing(message: "File transfer failed: \(error.localizedDescription)")
            }
        }
    }

    private func handleIncomingOffer(body: Data) throws -> FileTransferHTTPResponse {
        let manifest = try JSONDecoder().decode(FileTransferManifest.self, from: body)
        stateLock.lock()
        let shouldAccept = autoAcceptIncoming
        if let accepted = acceptedIncomingOffers[manifest.transferId] {
            stateLock.unlock()
            return FileTransferHTTPResponse(json: try JSONEncoder().encode(accepted))
        }
        if declinedIncomingOffers.contains(manifest.transferId) {
            stateLock.unlock()
            return FileTransferHTTPResponse(status: "403 Forbidden", text: "Incoming file transfer was declined.")
        }
        stateLock.unlock()
        guard shouldAccept else {
            stateLock.lock()
            let isNewOffer = pendingIncomingOffers[manifest.transferId] == nil
            pendingIncomingOffers[manifest.transferId] = manifest
            stateLock.unlock()
            if isNewOffer {
                DispatchQueue.main.async { [weak self] in
                    MacNotificationPresenter.shared.showFileTransferApproval(
                        transferId: manifest.transferId,
                        fileName: Self.transferTitle(manifest),
                        senderName: manifest.senderName,
                        totalBytes: manifest.totalBytes
                    )
                    self?.displayState = FileTransferDisplayState(
                        title: "Incoming file transfer",
                        detail: "Waiting for approval",
                        progress: 0,
                        speedBytesPerSecond: 0,
                        etaSeconds: nil,
                        canCancel: false,
                        transferId: manifest.transferId
                    )
                }
            }
            return pendingOfferResponse(transferId: manifest.transferId)
        }

        let response = try acceptIncomingManifest(manifest)
        return FileTransferHTTPResponse(json: try JSONEncoder().encode(response))
    }

    private func handleIncomingOfferStatus(headers: [String: String], body: Data) throws -> FileTransferHTTPResponse {
        let transferId = try transferId(from: headers, body: body)
        stateLock.lock()
        if let accepted = acceptedIncomingOffers[transferId] {
            stateLock.unlock()
            return FileTransferHTTPResponse(json: try JSONEncoder().encode(accepted))
        }
        if declinedIncomingOffers.remove(transferId) != nil {
            stateLock.unlock()
            return FileTransferHTTPResponse(status: "403 Forbidden", text: "Incoming file transfer was declined.")
        }
        let isPending = pendingIncomingOffers[transferId] != nil
        stateLock.unlock()
        if isPending {
            return pendingOfferResponse(transferId: transferId)
        }
        return FileTransferHTTPResponse(status: "404 Not Found", text: "Incoming file transfer offer was not found.")
    }

    private func acceptIncomingManifest(_ manifest: FileTransferManifest) throws -> FileTransferOfferResponse {
        let response = try receiver.accept(
            manifest: manifest,
            requestedStreamCount: manifest.requestedStreamCount,
            receiverMaximumStreams: ZevLinkTransferProtocol.maxStreamCount
        )
        stateLock.lock()
        cancelledTransferIds.remove(manifest.transferId)
        acceptedIncomingOffers[manifest.transferId] = response
        incomingProgress[manifest.transferId] = IncomingProgress(
            totalBytes: manifest.totalBytes,
            verifiedBytes: 0,
            startedAt: Date(),
            fileName: Self.transferTitle(manifest)
        )
        stateLock.unlock()
        publishIncomingProgress(manifest: manifest, verifiedBytes: 0)
        return response
    }

    private func pendingOfferResponse(transferId: String) -> FileTransferHTTPResponse {
        let payload: [String: Any] = [
            "transferId": transferId,
            "status": "pending",
            "retryAfterMillis": 500
        ]
        return FileTransferHTTPResponse(
            status: "202 Accepted",
            json: (try? JSONSerialization.data(withJSONObject: payload)) ?? Data("{}".utf8)
        )
    }

    private func handleIncomingRanges(headers: [String: String], body: Data) throws -> FileTransferHTTPResponse {
        let transferId = try transferId(from: headers, body: body)
        let ranges = try receiver.verifiedRanges(transferId: transferId)
        return FileTransferHTTPResponse(json: try JSONEncoder().encode(ranges))
    }

    private func handleIncomingChunk(headers: [String: String], body: Data) throws -> FileTransferHTTPResponse {
        let transferId = try requiredHeader("x-zevlink-transfer-id", headers: headers)
        let fileId = try requiredHeader("x-zevlink-file-id", headers: headers)
        let chunkIndexText = try requiredHeader("x-zevlink-chunk-index", headers: headers)
        guard let chunkIndex = Int64(chunkIndexText) else {
            throw FileTransferValidationError.invalidRange
        }
        let chunkHash = headers["x-zevlink-chunk-sha256"]
            .flatMap { FileTransferEntry.isLowercaseSHA256($0) ? $0 : nil }

        try receiver.writeChunk(
            transferId: transferId,
            fileId: fileId,
            chunkIndex: chunkIndex,
            data: body,
            chunkSHA256: chunkHash,
            activeStreamCount: 4
        )
        let progress = recordIncomingBytes(transferId: transferId, byteCount: Int64(body.count))
        publishIncomingProgress(transferId: transferId, progress: progress)
        return FileTransferHTTPResponse(status: "200 OK", text: "Chunk accepted.")
    }

    private func handleIncomingComplete(headers: [String: String], body: Data) throws -> FileTransferHTTPResponse {
        let transferId = try transferId(from: headers, body: body)
        let result = try receiver.complete(transferId: transferId)
        stateLock.lock()
        let completedProgress = incomingProgress.removeValue(forKey: transferId)
        acceptedIncomingOffers.removeValue(forKey: transferId)
        stateLock.unlock()
        DispatchQueue.main.async { [weak self] in
            MacNotificationPresenter.shared.showFileTransferReceived(
                transferId: transferId,
                fileName: completedProgress?.fileName ?? Self.transferTitle(fileCount: result.files.count),
                fileURLs: result.files.map(\.url)
            )
            self?.displayState = FileTransferDisplayState(
                title: "Received files",
                detail: "Saved to \(result.destinationRoot.path)",
                progress: 1,
                speedBytesPerSecond: 0,
                etaSeconds: nil,
                canCancel: false,
                transferId: nil
            )
        }
        let payload: [String: Any] = [
            "transferId": transferId,
            "destination": result.destinationRoot.path,
            "files": result.files.map { ["fileId": $0.fileId, "sha256": $0.sha256] }
        ]
        return FileTransferHTTPResponse(json: try JSONSerialization.data(withJSONObject: payload))
    }

    private func handleIncomingCancel(headers: [String: String], body: Data) throws -> FileTransferHTTPResponse {
        let transferId = try transferId(from: headers, body: body)
        markTransferCancelled(transferId)
        try receiver.cancel(transferId: transferId)
        stateLock.lock()
        pendingIncomingOffers.removeValue(forKey: transferId)
        acceptedIncomingOffers.removeValue(forKey: transferId)
        declinedIncomingOffers.remove(transferId)
        incomingProgress.removeValue(forKey: transferId)
        stateLock.unlock()
        DispatchQueue.main.async { [weak self] in
            MacNotificationPresenter.shared.clearFileTransfer(transferId: transferId)
            self?.displayState = .idle
        }
        return FileTransferHTTPResponse(status: "200 OK", text: "Transfer cancelled.")
    }

    private func transferId(from headers: [String: String], body: Data) throws -> String {
        if let header = headers["x-zevlink-transfer-id"]?.trimmingCharacters(in: .whitespacesAndNewlines), !header.isEmpty {
            return header
        }
        if
            let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
            let transferId = json["transferId"] as? String,
            !transferId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            return transferId
        }
        throw FileTransferValidationError.invalidIdentifier("Transfer ID")
    }

    private func requiredHeader(_ name: String, headers: [String: String]) throws -> String {
        guard let value = headers[name]?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            throw FileTransferValidationError.invalidIdentifier(name)
        }
        return value
    }

    private func recordIncomingBytes(transferId: String, byteCount: Int64) -> IncomingProgress {
        stateLock.lock()
        var progress = incomingProgress[transferId] ?? IncomingProgress(
            totalBytes: byteCount,
            verifiedBytes: 0,
            startedAt: Date(),
            fileName: "File transfer"
        )
        progress.verifiedBytes = min(progress.totalBytes, progress.verifiedBytes + max(0, byteCount))
        incomingProgress[transferId] = progress
        stateLock.unlock()
        return progress
    }

    private func publishIncomingProgress(manifest: FileTransferManifest, verifiedBytes: Int64) {
        let progress = IncomingProgress(
            totalBytes: manifest.totalBytes,
            verifiedBytes: verifiedBytes,
            startedAt: Date(),
            fileName: Self.transferTitle(manifest)
        )
        publishIncomingProgress(transferId: manifest.transferId, progress: progress)
    }

    private func publishIncomingProgress(transferId: String, progress: IncomingProgress) {
        guard !isTransferCancelled(transferId) else { return }
        let elapsed = max(0.001, Date().timeIntervalSince(progress.startedAt))
        let speed = Double(progress.verifiedBytes) / elapsed
        let remaining = max(0, progress.totalBytes - progress.verifiedBytes)
        let eta = speed > 0 ? Double(remaining) / speed : nil
        DispatchQueue.main.async { [weak self] in
            MacNotificationPresenter.shared.showFileTransferActive(
                transferId: transferId,
                fileName: progress.fileName,
                status: "Receiving from Android",
                transferredBytes: progress.verifiedBytes,
                totalBytes: progress.totalBytes
            )
            self?.displayState = FileTransferDisplayState(
                title: "Receiving files",
                detail: "\(Self.formatBytes(progress.verifiedBytes)) / \(Self.formatBytes(progress.totalBytes))",
                progress: progress.totalBytes == 0 ? 1 : Double(progress.verifiedBytes) / Double(progress.totalBytes),
                speedBytesPerSecond: speed,
                etaSeconds: eta,
                canCancel: true,
                transferId: transferId
            )
        }
    }

    @MainActor
    private func updateOutgoing(
        title: String,
        fileName: String,
        verifiedBytes: Int64,
        totalBytes: Int64,
        transferId: String,
        startedAt: Date?
    ) {
        guard !isTransferCancelled(transferId) else { return }
        let currentStartedAt = startedAt ?? outgoingStartedAt ?? Date()
        outgoingStartedAt = currentStartedAt
        let elapsed = max(0.001, Date().timeIntervalSince(currentStartedAt))
        let speed = Double(verifiedBytes) / elapsed
        let remaining = max(0, totalBytes - verifiedBytes)
        MacNotificationPresenter.shared.showFileTransferActive(
            transferId: transferId,
            fileName: fileName,
            status: title,
            transferredBytes: verifiedBytes,
            totalBytes: totalBytes
        )
        displayState = FileTransferDisplayState(
            title: title,
            detail: "\(Self.formatBytes(verifiedBytes)) / \(Self.formatBytes(totalBytes))",
            progress: totalBytes == 0 ? 1 : Double(verifiedBytes) / Double(totalBytes),
            speedBytesPerSecond: speed,
            etaSeconds: speed > 0 ? Double(remaining) / speed : nil,
            canCancel: true,
            transferId: transferId
        )
    }

    @MainActor
    private func finishOutgoing(message: String) {
        outgoingStartedAt = nil
        outgoingTask = nil
        setOutgoingTransferId(nil)
        displayState = FileTransferDisplayState(
            title: "File transfer",
            detail: message,
            progress: 1,
            speedBytesPerSecond: 0,
            etaSeconds: nil,
            canCancel: false,
            transferId: nil
        )
    }

    private var outgoingStartedAt: Date?

    private struct IncomingProgress {
        let totalBytes: Int64
        var verifiedBytes: Int64
        let startedAt: Date
        let fileName: String
    }

    private static func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private static func transferTitle(_ manifest: FileTransferManifest) -> String {
        if manifest.entryCount == 1,
           let entry = manifest.entries.first {
            return URL(fileURLWithPath: entry.relativePath).lastPathComponent
        }
        return transferTitle(fileCount: manifest.entryCount)
    }

    private static func transferTitle(fileCount: Int) -> String {
        fileCount == 1 ? "1 item" : "\(fileCount) items"
    }

    private func markTransferCancelled(_ transferId: String) {
        stateLock.lock()
        cancelledTransferIds.insert(transferId)
        stateLock.unlock()
    }

    private func clearTransferCancellation(_ transferId: String) {
        stateLock.lock()
        cancelledTransferIds.remove(transferId)
        stateLock.unlock()
    }

    private func isTransferCancelled(_ transferId: String) -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return cancelledTransferIds.contains(transferId)
    }

    private func setOutgoingTransferId(_ transferId: String?) {
        stateLock.lock()
        outgoingTransferId = transferId
        stateLock.unlock()
    }

    private func isOutgoingTransfer(_ transferId: String) -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return outgoingTransferId == transferId
    }
}
