package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeMatchPolicyTest {
    @Test fun rejectsConflictingSeasonEpisodeEvenWhenTitleMatches() {
        val request = EpisodeMatchCandidate(2, 3, title = "Finale")
        val candidate = EpisodeMatchCandidate(1, 3, title = "Finale")
        assertFalse(EpisodeMatchPolicy.evaluate(request, candidate).matches)
    }

    @Test fun matchesAbsoluteAnimeEpisode() {
        val decision = EpisodeMatchPolicy.evaluate(
            EpisodeMatchCandidate(null, null, absoluteEpisode = 68),
            EpisodeMatchCandidate(1, 68, absoluteEpisode = 68)
        )
        assertTrue(decision.matches)
        assertEquals(EpisodeMatchReason.ABSOLUTE, decision.reason)
    }

    @Test fun matchesSpecialsAndAirDates() {
        assertEquals(
            EpisodeMatchReason.SPECIAL,
            EpisodeMatchPolicy.evaluate(EpisodeMatchCandidate(0, 2), EpisodeMatchCandidate(0, 2)).reason
        )
        assertTrue(
            EpisodeMatchPolicy.evaluate(
                EpisodeMatchCandidate(null, null, released = "2026-08-22T12:00:00Z"),
                EpisodeMatchCandidate(null, null, released = "2026-08-22")
            ).matches
        )
    }
}
