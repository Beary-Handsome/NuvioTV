package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklRatingsServiceTest {
    private val movie = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "Example",
        year = 2026,
        ids = TrackingExternalIds(imdb = "tt1234567", tmdb = 42)
    )

    @Test
    fun ratingBodyUsesOfficialMovieShape() {
        val item = Json.parseToJsonElement(buildSimklRatingBody(movie, 8))
            .jsonObject.getValue("movies").toString()
        assertTrue(item.contains("\"rating\":8"))
        assertTrue(item.contains("\"imdb\":\"tt1234567\""))
    }

    @Test
    fun clearBodyOmitsRating() {
        val body = buildSimklRatingBody(movie.copy(kind = TrackingMediaKind.SHOW), null)
        val root = Json.parseToJsonElement(body).jsonObject
        assertTrue("shows" in root)
        assertFalse(root.getValue("shows").toString().contains("rating"))
    }

    @Test
    fun successRequiresConfirmedStatusOrDeletedCount() {
        assertTrue(simklRatingApplied("""{"added":{"statuses":[{"request":{}}]}}"""))
        assertFalse(simklRatingApplied("""{"added":{"statuses":[]}}"""))
        assertTrue(simklRatingCleared("""{"deleted":{"movies":1}}""", "movies"))
        assertFalse(simklRatingCleared("""{"deleted":{"movies":0}}""", "movies"))
    }
}
