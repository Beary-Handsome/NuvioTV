package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktIdUtilsTest {
    @Test
    fun `watched movie aliases cover typed TMDB catalog IDs`() {
        val aliases = watchedContentAliases(
            TraktIdsDto(trakt = 12, imdb = "tt1234567", tmdb = 34),
            contentType = "movie"
        )

        assertTrue(aliases.containsAll(listOf("tt1234567", "tmdb:34", "tmdb:movie:34", "trakt:12")))
    }

    @Test
    fun `watched series aliases cover typed TMDB catalog IDs`() {
        val aliases = watchedContentAliases(
            TraktIdsDto(tmdb = 56),
            contentType = "series"
        )

        assertEquals(setOf("tmdb:56", "tmdb:series:56"), aliases.toSet())
    }
}
