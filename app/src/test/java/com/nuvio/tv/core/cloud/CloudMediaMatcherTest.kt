package com.nuvio.tv.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMediaMatcherTest {
    @Test
    fun `matches exact episode in season pack`() {
        val item = item(
            "Example Show Season 2",
            "Example.Show.S02E02.mkv",
            "Example.Show.S02E03.mkv"
        )

        val matches = CloudMediaMatcher.findMatches(listOf(item), "Example Show", 2024, 2, 3)

        assertEquals(listOf("Example.Show.S02E03.mkv"), matches.map { it.file.name })
    }

    @Test
    fun `rejects conflicting movie year`() {
        val matches = CloudMediaMatcher.findMatches(
            listOf(item("The Example 1994", "The.Example.1994.1080p.mkv")),
            "The Example",
            2024,
            null,
            null
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `does not trust episode title without numbering`() {
        val matches = CloudMediaMatcher.findMatches(
            listOf(item("Example Show", "The Return.mkv")),
            "Example Show",
            2024,
            1,
            2
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `short movie title does not match inside another word`() {
        val matches = CloudMediaMatcher.findMatches(
            listOf(item("Little Women 2019", "Little.Women.2019.mkv")),
            "It",
            2017,
            null,
            null
        )
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `rejects unsafe episode download`() {
        val matches = CloudMediaMatcher.findMatches(
            listOf(item("Example Show", "Example.Show.S01E02.Downloader.dmg")),
            "Example Show",
            2024,
            1,
            2
        )
        assertTrue(matches.isEmpty())
    }

    private fun item(name: String, vararg files: String) = CloudLibraryItem(
        providerId = "realdebrid",
        providerName = "Real-Debrid",
        id = name,
        type = CloudLibraryItemType.Torrent,
        name = name,
        files = files.map { CloudLibraryFile(id = "https://example.test/$it", name = it) }
    )
}
