package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalMediaIdentityTest {
    @Test fun normalizesSeriesAliasesAndCoordinates() {
        val identity = CanonicalMediaIdentity.create("TV", " TT1234567 ", "TT1234567:2:8")
        assertEquals("series", identity.type)
        assertEquals("tt1234567", identity.contentId)
        assertEquals(2, identity.season)
        assertEquals(8, identity.episode)
    }

    @Test fun derivesContentIdWhenNavigationIdIsMissing() {
        val identity = CanonicalMediaIdentity.create("movie", null, "tmdb:550")
        assertEquals("tmdb:550", identity.contentId)
        assertEquals("movie|tmdb:550", identity.contentKey)
    }

    @Test fun rebuildsSeriesRequestFromCanonicalParentAndCoordinates() {
        val identity = CanonicalMediaIdentity.create(
            type = "series",
            contentId = "tt8115702",
            videoId = "stale-or-provider-specific-id",
            season = 1,
            episode = 7
        )

        assertEquals("tt8115702:1:7", identity.streamRequestId)
        assertEquals("series|tt8115702|tt8115702:1:7|1|7", identity.videoKey)
    }
}
