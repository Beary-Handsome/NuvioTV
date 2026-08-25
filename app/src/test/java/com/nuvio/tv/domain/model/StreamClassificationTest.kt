package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamClassificationTest {
    @Test
    fun `resolver hash without cached flag remains a torrent candidate`() {
        val stream = resolverStream(isCached = null)

        assertTrue(stream.isTorrent())
        assertTrue(stream.needsLocalDebridResolve())
    }

    @Test
    fun `cached resolver hash remains a direct debrid candidate`() {
        val stream = resolverStream(isCached = true)

        assertTrue(stream.isDirectDebrid())
        assertFalse(stream.isTorrent())
    }

    private fun resolverStream(isCached: Boolean?) = Stream(
        name = "Result",
        title = null,
        description = null,
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Riven",
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = "0123456789012345678901234567890123456789",
            fileIdx = 0,
            magnetUri = null,
            sources = emptyList(),
            torrentName = "Show.S01E02.mkv",
            filename = "Show.S01E02.mkv",
            mediaType = "series",
            mediaId = "tt1234567:1:2",
            mediaOnlyId = "tt1234567",
            title = "Show",
            season = 1,
            episode = 2,
            service = "realdebrid",
            serviceIndex = null,
            serviceExtension = null,
            isCached = isCached
        )
    )
}
