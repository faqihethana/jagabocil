package com.jagabocil.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingStepTest {
    private val now = 1000L
    private val pending = Recitation("one", "child", "112", "pending", 15.0, 800L, feedback = "")
    private val approved = pending.copy(id = "two", status = "approved", expiresAt = now + 1)

    @Test fun currentRecordingAndDraftTakePriorityOverRemoteResults() {
        val history = listOf(pending, approved)
        assertEquals(ReadingStep.Recording, readingStep(true, false, history, now))
        assertEquals(ReadingStep.Preview, readingStep(false, true, history, now))
    }

    @Test fun availablePermissionTakesPriorityUntilItExpiresOrIsUsed() {
        val history = listOf(pending, approved)
        assertEquals(ReadingStep.Approved(approved), readingStep(false, false, history, now))
        assertEquals(ReadingStep.Waiting(pending), readingStep(false, false, history, now + 1))
        assertEquals(ReadingStep.Read, readingStep(false, false, listOf(approved.copy(usedAt = now)), now))
    }

    @Test fun aNewOrRejectedTaskCanBeRecorded() {
        assertEquals(ReadingStep.Read, readingStep(false, false, emptyList(), now))
        assertEquals(ReadingStep.Read, readingStep(false, false, listOf(pending.copy(status = "rejected")), now))
    }
}
