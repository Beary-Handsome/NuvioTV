package com.nuvio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIssueReportRedactionTest {
    @Test
    fun `signed urls and credentials are removed from diagnostics`() {
        val redacted = redactPlaybackDiagnosticText(
            "load https://cdn.test/video.mkv?token=secret Authorization: Bearer private"
        )

        assertTrue("[redacted-url]" in redacted)
        assertFalse("secret" in redacted)
        assertFalse("private" in redacted)
    }

    @Test
    fun `standalone credential assignments are removed`() {
        val redacted = redactPlaybackDiagnosticText("api_key=secret token:another")

        assertFalse("secret" in redacted)
        assertFalse("another" in redacted)
        assertTrue("[redacted]" in redacted)
    }
}
