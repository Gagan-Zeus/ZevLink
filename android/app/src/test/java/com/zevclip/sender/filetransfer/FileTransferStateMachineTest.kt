package com.zevclip.sender.filetransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferStateMachineTest {
    @Test
    fun acceptedTransferMovesThroughSuccessfulLifecycle() {
        val machine = FileTransferStateMachine(
            totalBytes = 10,
            selectedStreamCount = 4
        )

        machine.persistOffer(requiresUserDecision = false)
        machine.markChunkRequestStarted(activeStreamCount = 2)
        machine.markChunkVerified(byteCount = 6)
        machine.markChunkVerified(byteCount = 6)
        machine.beginVerification()
        machine.complete()

        assertEquals(FileTransferState.COMPLETED, machine.state)
        assertEquals(10, machine.progress.verifiedBytes)
        assertEquals(0, machine.progress.activeStreamCount)
        assertTrue(machine.progress.isComplete)
    }

    @Test
    fun declinedTransferIsTerminal() {
        val machine = FileTransferStateMachine(
            totalBytes = 10,
            selectedStreamCount = 1
        )

        machine.persistOffer(requiresUserDecision = true)
        machine.applyDecision(FileTransferDecision.DECLINE)

        assertEquals(FileTransferState.DECLINED, machine.state)
        assertFalse(
            FileTransferStateMachine.canTransition(
                FileTransferState.DECLINED,
                FileTransferState.ACCEPTED
            )
        )
    }

    @Test
    fun interruptedTransferCanResume() {
        val machine = FileTransferStateMachine(
            totalBytes = 100,
            selectedStreamCount = 2
        )

        machine.persistOffer(requiresUserDecision = false)
        machine.markChunkRequestStarted(activeStreamCount = 1)
        machine.markChunkVerified(byteCount = 25)
        machine.markNoActiveConnections()
        machine.markChunkRequestStarted(activeStreamCount = 2)

        assertEquals(FileTransferState.TRANSFERRING, machine.state)
        assertEquals(25, machine.progress.verifiedBytes)
        assertEquals(2, machine.progress.activeStreamCount)
    }

    @Test(expected = IllegalStateException::class)
    fun cannotCompleteBeforeVerification() {
        val machine = FileTransferStateMachine(
            totalBytes = 0,
            selectedStreamCount = 1
        )

        machine.persistOffer(requiresUserDecision = false)
        machine.complete()
    }
}

