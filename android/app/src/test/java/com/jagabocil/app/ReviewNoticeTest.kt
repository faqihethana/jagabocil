package com.jagabocil.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewNoticeTest {
    private fun record(id: String, status: String, ready: Long? = null, reviewed: Long? = null) =
        Recitation(id, "child", "112", status, 16.0, 100L, feedback = "", readyAt = ready, reviewedAt = reviewed)

    private fun dashboard(role: String, vararg recordings: Recitation) =
        Dashboard(role, "Test", false, emptyList(), recordings.toList(), emptyList(), 400L)

    @Test fun parentNoticesOnlyNewPendingRecordingsThatWereStored() {
        val data = dashboard("parent", record("old", "pending", 150), record("new", "pending", 201),
            record("uploading", "uploading", null), record("reviewed", "approved", 204, 250))
        assertEquals(listOf(ReviewNotice("new", 201, "pending")), reviewNotices(data, 200))
    }

    @Test fun childNoticesOnlyNewReviewDecisions() {
        val data = dashboard("child", record("ok", "approved", 210, 250), record("again", "rejected", 208, 241),
            record("rule-change", "rejected", 210), record("pending", "pending", 240), record("old", "approved", 120, 200))
        assertEquals(listOf(ReviewNotice("ok", 250, "approved"), ReviewNotice("again", 241, "rejected")), reviewNotices(data, 200))
    }
}
