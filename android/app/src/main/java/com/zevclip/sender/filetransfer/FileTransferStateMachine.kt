package com.zevclip.sender.filetransfer

enum class FileTransferState(val wireValue: String, val isTerminal: Boolean = false) {
    OFFERED("offered"),
    AWAITING_ACCEPTANCE("awaitingAcceptance"),
    ACCEPTED("accepted"),
    TRANSFERRING("transferring"),
    INTERRUPTED("interrupted"),
    VERIFYING("verifying"),
    COMPLETED("completed", isTerminal = true),
    DECLINED("declined", isTerminal = true),
    CANCELLED("cancelled", isTerminal = true),
    FAILED("failed", isTerminal = true);

    companion object {
        fun fromWireValue(value: String): FileTransferState? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

enum class FileTransferCancellationReason(val wireValue: String) {
    CANCELLED_BY_SENDER("cancelledBySender"),
    CANCELLED_BY_RECEIVER("cancelledByReceiver"),
    USER_CANCELLED("userCancelled"),
    APP_SHUTDOWN("appShutdown")
}

enum class FileTransferFailureReason(val wireValue: String) {
    AUTHENTICATION_FAILED("authenticationFailed"),
    CERTIFICATE_MISMATCH("certificateMismatch"),
    INVALID_MANIFEST("invalidManifest"),
    INVALID_RANGE("invalidRange"),
    HASH_MISMATCH("hashMismatch"),
    MISSING_CHUNKS("missingChunks"),
    DESTINATION_CHANGED("destinationChanged"),
    RESUME_EXPIRED("resumeExpired"),
    INTERNAL_ERROR("internalError")
}

data class FileTransferProgress(
    val totalBytes: Long,
    val verifiedBytes: Long = 0L,
    val activeStreamCount: Int = 0
) {
    init {
        require(totalBytes >= 0L) { "Total bytes must not be negative." }
        require(verifiedBytes in 0L..totalBytes) { "Verified bytes must be within total bytes." }
        require(activeStreamCount in 0..ZevLinkTransferProtocol.MAX_STREAM_COUNT) {
            "Active stream count must be between 0 and 8."
        }
    }

    val remainingBytes: Long
        get() = totalBytes - verifiedBytes

    val fractionComplete: Double
        get() = if (totalBytes == 0L) 1.0 else verifiedBytes.toDouble() / totalBytes.toDouble()

    val isComplete: Boolean
        get() = verifiedBytes == totalBytes

    fun addingVerifiedBytes(byteCount: Long): FileTransferProgress {
        require(byteCount >= 0L) { "Verified byte count must not be negative." }
        val nextVerifiedBytes = (verifiedBytes + byteCount).coerceAtMost(totalBytes)
        return copy(verifiedBytes = nextVerifiedBytes)
    }

    fun withActiveStreams(activeStreams: Int): FileTransferProgress {
        require(activeStreams in 0..ZevLinkTransferProtocol.MAX_STREAM_COUNT) {
            "Active stream count must be between 0 and 8."
        }
        return copy(activeStreamCount = activeStreams)
    }
}

data class FileTransferStateSnapshot(
    val state: FileTransferState,
    val progress: FileTransferProgress,
    val selectedStreamCount: Int,
    val cancellationReason: FileTransferCancellationReason? = null,
    val failureReason: FileTransferFailureReason? = null
)

class FileTransferStateMachine(
    initialState: FileTransferState = FileTransferState.OFFERED,
    totalBytes: Long,
    selectedStreamCount: Int
) {
    var state: FileTransferState = initialState
        private set

    var progress: FileTransferProgress = FileTransferProgress(totalBytes = totalBytes)
        private set

    var cancellationReason: FileTransferCancellationReason? = null
        private set

    var failureReason: FileTransferFailureReason? = null
        private set

    init {
        require(selectedStreamCount in ZevLinkTransferProtocol.MIN_STREAM_COUNT..ZevLinkTransferProtocol.MAX_STREAM_COUNT) {
            "Selected stream count must be between 1 and 8."
        }
        require(!initialState.isTerminal) { "A new state machine cannot start in a terminal state." }
    }

    val selectedStreamCount: Int = selectedStreamCount

    val snapshot: FileTransferStateSnapshot
        get() = FileTransferStateSnapshot(
            state = state,
            progress = progress,
            selectedStreamCount = selectedStreamCount,
            cancellationReason = cancellationReason,
            failureReason = failureReason
        )

    fun persistOffer(requiresUserDecision: Boolean) {
        transitionTo(
            if (requiresUserDecision) {
                FileTransferState.AWAITING_ACCEPTANCE
            } else {
                FileTransferState.ACCEPTED
            }
        )
    }

    fun applyDecision(decision: FileTransferDecision) {
        when (decision) {
            FileTransferDecision.ACCEPT -> transitionTo(FileTransferState.ACCEPTED)
            FileTransferDecision.DECLINE -> transitionTo(FileTransferState.DECLINED)
        }
    }

    fun markChunkRequestStarted(activeStreamCount: Int) {
        require(activeStreamCount in ZevLinkTransferProtocol.MIN_STREAM_COUNT..selectedStreamCount) {
            "Active stream count must be between 1 and the selected stream count."
        }
        if (state == FileTransferState.ACCEPTED || state == FileTransferState.INTERRUPTED) {
            transitionTo(FileTransferState.TRANSFERRING)
        } else if (state != FileTransferState.TRANSFERRING) {
            error("Cannot start chunk upload while transfer is ${state.wireValue}.")
        }
        progress = progress.withActiveStreams(activeStreamCount)
    }

    fun markChunkVerified(byteCount: Long) {
        if (state != FileTransferState.TRANSFERRING) {
            error("Cannot verify chunk bytes while transfer is ${state.wireValue}.")
        }
        progress = progress.addingVerifiedBytes(byteCount)
    }

    fun markNoActiveConnections() {
        progress = progress.withActiveStreams(0)
        if (state == FileTransferState.ACCEPTED || state == FileTransferState.TRANSFERRING) {
            transitionTo(FileTransferState.INTERRUPTED)
        }
    }

    fun beginVerification() {
        require(progress.isComplete) { "Cannot verify a transfer before all bytes are verified." }
        transitionTo(FileTransferState.VERIFYING)
    }

    fun complete() {
        transitionTo(FileTransferState.COMPLETED)
    }

    fun cancel(reason: FileTransferCancellationReason) {
        cancellationReason = reason
        transitionTo(FileTransferState.CANCELLED)
    }

    fun fail(reason: FileTransferFailureReason) {
        failureReason = reason
        transitionTo(FileTransferState.FAILED)
    }

    fun transitionTo(nextState: FileTransferState) {
        if (!canTransition(state, nextState)) {
            error("Illegal file transfer state transition: ${state.wireValue} -> ${nextState.wireValue}.")
        }
        state = nextState
        if (nextState.isTerminal) {
            progress = progress.withActiveStreams(0)
        }
    }

    companion object {
        fun canTransition(from: FileTransferState, to: FileTransferState): Boolean {
            if (from == to) {
                return true
            }
            if (from.isTerminal) {
                return false
            }
            if (to == FileTransferState.CANCELLED) {
                return from != FileTransferState.DECLINED && from != FileTransferState.COMPLETED
            }
            if (to == FileTransferState.FAILED) {
                return true
            }

            return when (from) {
                FileTransferState.OFFERED ->
                    to == FileTransferState.AWAITING_ACCEPTANCE ||
                        to == FileTransferState.ACCEPTED
                FileTransferState.AWAITING_ACCEPTANCE ->
                    to == FileTransferState.ACCEPTED ||
                        to == FileTransferState.DECLINED
                FileTransferState.ACCEPTED ->
                    to == FileTransferState.TRANSFERRING ||
                        to == FileTransferState.INTERRUPTED ||
                        to == FileTransferState.VERIFYING
                FileTransferState.TRANSFERRING ->
                    to == FileTransferState.INTERRUPTED ||
                        to == FileTransferState.VERIFYING
                FileTransferState.INTERRUPTED ->
                    to == FileTransferState.TRANSFERRING
                FileTransferState.VERIFYING ->
                    to == FileTransferState.COMPLETED
                FileTransferState.COMPLETED,
                FileTransferState.DECLINED,
                FileTransferState.CANCELLED,
                FileTransferState.FAILED -> false
            }
        }
    }
}

