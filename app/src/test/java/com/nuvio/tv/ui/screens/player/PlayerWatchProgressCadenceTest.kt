package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerWatchProgressCadenceTest {
    @Test
    fun `first progress save happens promptly`() {
        assertTrue(WATCH_PROGRESS_INITIAL_SAVE_DELAY_MS <= 5_000L)
        assertTrue(WATCH_PROGRESS_INITIAL_SAVE_DELAY_MS < WATCH_PROGRESS_SAVE_INTERVAL_MS)
    }

    @Test
    fun `periodic local saves remain responsive without excessive writes`() {
        assertTrue(WATCH_PROGRESS_SAVE_INTERVAL_MS in 15_000L..30_000L)
    }

    @Test
    fun `older or duplicate progress snapshots cannot replace newer state`() {
        assertTrue(shouldPersistProgressSnapshot(101L, 100L))
        assertTrue(!shouldPersistProgressSnapshot(100L, 100L))
        assertTrue(!shouldPersistProgressSnapshot(99L, 100L))
    }
}
