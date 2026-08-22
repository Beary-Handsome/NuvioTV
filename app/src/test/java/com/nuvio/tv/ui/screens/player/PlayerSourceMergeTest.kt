package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourceMergeTest {
    @Test
    fun `easynews survives riven arriving later`() {
        val easynews = stream("EasyNews", "https://easynews.test/file")
        val torbox = stream("Riven", "https://torbox.test/file")

        val merged = mergeSourceStreams(listOf(easynews), listOf(torbox))

        assertEquals(listOf("EasyNews", "Riven"), merged.map { it.addonName })
    }

    @Test
    fun `riven survives easynews arriving later`() {
        val easynews = stream("EasyNews", "https://easynews.test/file")
        val torbox = stream("Riven", "https://torbox.test/file")

        val merged = mergeSourceStreams(listOf(torbox), listOf(easynews))

        assertEquals(listOf("Riven", "EasyNews"), merged.map { it.addonName })
    }

    private fun stream(addon: String, url: String) = Stream(
        name = addon,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = addon,
        addonLogo = null
    )
}
