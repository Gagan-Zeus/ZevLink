import Foundation

enum FileTransferState: String, Codable, Equatable {
    case offered
    case awaitingAcceptance
    case accepted
    case transferring
    case interrupted
    case verifying
    case completed
    case declined
    case cancelled
    case failed

    var isTerminal: Bool {
        switch self {
        case .completed, .declined, .cancelled, .failed:
            return true
        case .offered, .awaitingAcceptance, .accepted, .transferring, .interrupted, .verifying:
            return false
        }
    }
}

enum FileTransferCancellationReason: String, Codable, Equatable {
    case cancelledBySender
    case cancelledByReceiver
    case userCancelled
    case appShutdown
}

enum FileTransferFailureReason: String, Codable, Equatable {
    case authenticationFailed
    case certificateMismatch
    case invalidManifest
    case invalidRange
    case hashMismatch
    case missingChunks
    case destinationChanged
    case resumeExpired
    case internalError
}

enum FileTransferStateMachineError: Error, LocalizedError, Equatable {
    case invalidInitialState
    case invalidTransition(from: FileTransferState, to: FileTransferState)
    case invalidProgress
    case invalidActiveStreamCount
    case incompleteTransfer

    var errorDescription: String? {
        switch self {
        case .invalidInitialState:
            return "A transfer state machine cannot start in a terminal state."
        case .invalidTransition(let from, let to):
            return "Illegal file transfer state transition: \(from.rawValue) -> \(to.rawValue)."
        case .invalidProgress:
            return "Transfer progress is invalid."
        case .invalidActiveStreamCount:
            return "Active stream count is invalid."
        case .incompleteTransfer:
            return "Cannot verify a transfer before all bytes are verified."
        }
    }
}

struct FileTransferProgress: Codable, Equatable {
    let totalBytes: Int64
    let verifiedBytes: Int64
    let activeStreamCount: Int

    init(totalBytes: Int64, verifiedBytes: Int64 = 0, activeStreamCount: Int = 0) throws {
        guard totalBytes >= 0,
              verifiedBytes >= 0,
              verifiedBytes <= totalBytes,
              (0...ZevLinkTransferProtocol.maxStreamCount).contains(activeStreamCount) else {
            throw FileTransferStateMachineError.invalidProgress
        }
        self.totalBytes = totalBytes
        self.verifiedBytes = verifiedBytes
        self.activeStreamCount = activeStreamCount
    }

    var remainingBytes: Int64 {
        totalBytes - verifiedBytes
    }

    var fractionComplete: Double {
        totalBytes == 0 ? 1.0 : Double(verifiedBytes) / Double(totalBytes)
    }

    var isComplete: Bool {
        verifiedBytes == totalBytes
    }

    func addingVerifiedBytes(_ byteCount: Int64) throws -> FileTransferProgress {
        guard byteCount >= 0 else {
            throw FileTransferStateMachineError.invalidProgress
        }
        return try FileTransferProgress(
            totalBytes: totalBytes,
            verifiedBytes: min(verifiedBytes + byteCount, totalBytes),
            activeStreamCount: activeStreamCount
        )
    }

    func withActiveStreams(_ activeStreams: Int) throws -> FileTransferProgress {
        guard (0...ZevLinkTransferProtocol.maxStreamCount).contains(activeStreams) else {
            throw FileTransferStateMachineError.invalidActiveStreamCount
        }
        return try FileTransferProgress(
            totalBytes: totalBytes,
            verifiedBytes: verifiedBytes,
            activeStreamCount: activeStreams
        )
    }
}

struct FileTransferStateSnapshot: Codable, Equatable {
    let state: FileTransferState
    let progress: FileTransferProgress
    let selectedStreamCount: Int
    let cancellationReason: FileTransferCancellationReason?
    let failureReason: FileTransferFailureReason?
}

struct FileTransferStateMachine {
    private(set) var state: FileTransferState
    private(set) var progress: FileTransferProgress
    private(set) var cancellationReason: FileTransferCancellationReason?
    private(set) var failureReason: FileTransferFailureReason?
    let selectedStreamCount: Int

    init(
        initialState: FileTransferState = .offered,
        totalBytes: Int64,
        selectedStreamCount: Int
    ) throws {
        guard !initialState.isTerminal else {
            throw FileTransferStateMachineError.invalidInitialState
        }
        guard (ZevLinkTransferProtocol.minStreamCount...ZevLinkTransferProtocol.maxStreamCount).contains(selectedStreamCount) else {
            throw FileTransferValidationError.invalidStreamCount
        }

        state = initialState
        progress = try FileTransferProgress(totalBytes: totalBytes)
        self.selectedStreamCount = selectedStreamCount
    }

    var snapshot: FileTransferStateSnapshot {
        FileTransferStateSnapshot(
            state: state,
            progress: progress,
            selectedStreamCount: selectedStreamCount,
            cancellationReason: cancellationReason,
            failureReason: failureReason
        )
    }

    mutating func persistOffer(requiresUserDecision: Bool) throws {
        try transition(
            to: requiresUserDecision ? .awaitingAcceptance : .accepted
        )
    }

    mutating func applyDecision(_ decision: FileTransferDecision) throws {
        switch decision {
        case .accept:
            try transition(to: .accepted)
        case .decline:
            try transition(to: .declined)
        }
    }

    mutating func markChunkRequestStarted(activeStreamCount: Int) throws {
        guard (ZevLinkTransferProtocol.minStreamCount...selectedStreamCount).contains(activeStreamCount) else {
            throw FileTransferStateMachineError.invalidActiveStreamCount
        }

        if state == .accepted || state == .interrupted {
            try transition(to: .transferring)
        } else if state != .transferring {
            throw FileTransferStateMachineError.invalidTransition(from: state, to: .transferring)
        }

        progress = try progress.withActiveStreams(activeStreamCount)
    }

    mutating func markChunkVerified(byteCount: Int64) throws {
        guard state == .transferring else {
            throw FileTransferStateMachineError.invalidTransition(from: state, to: .transferring)
        }
        progress = try progress.addingVerifiedBytes(byteCount)
    }

    mutating func markNoActiveConnections() throws {
        progress = try progress.withActiveStreams(0)
        if state == .accepted || state == .transferring {
            try transition(to: .interrupted)
        }
    }

    mutating func beginVerification() throws {
        guard progress.isComplete else {
            throw FileTransferStateMachineError.incompleteTransfer
        }
        try transition(to: .verifying)
    }

    mutating func complete() throws {
        try transition(to: .completed)
    }

    mutating func cancel(reason: FileTransferCancellationReason) throws {
        cancellationReason = reason
        try transition(to: .cancelled)
    }

    mutating func fail(reason: FileTransferFailureReason) throws {
        failureReason = reason
        try transition(to: .failed)
    }

    mutating func transition(to nextState: FileTransferState) throws {
        guard Self.canTransition(from: state, to: nextState) else {
            throw FileTransferStateMachineError.invalidTransition(from: state, to: nextState)
        }
        state = nextState
        if nextState.isTerminal {
            progress = try progress.withActiveStreams(0)
        }
    }

    static func canTransition(from: FileTransferState, to: FileTransferState) -> Bool {
        if from == to {
            return true
        }
        if from.isTerminal {
            return false
        }
        if to == .cancelled {
            return from != .declined && from != .completed
        }
        if to == .failed {
            return true
        }

        switch from {
        case .offered:
            return to == .awaitingAcceptance || to == .accepted
        case .awaitingAcceptance:
            return to == .accepted || to == .declined
        case .accepted:
            return to == .transferring || to == .interrupted || to == .verifying
        case .transferring:
            return to == .interrupted || to == .verifying
        case .interrupted:
            return to == .transferring
        case .verifying:
            return to == .completed
        case .completed, .declined, .cancelled, .failed:
            return false
        }
    }
}

