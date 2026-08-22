package com.nuvio.tv.domain.model

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale

data class EpisodeMatchCandidate(
    val season: Int?,
    val episode: Int?,
    val absoluteEpisode: Int? = null,
    val title: String? = null,
    val aliases: Set<String> = emptySet(),
    val released: String? = null
)

enum class EpisodeMatchReason { EXACT, SPECIAL, ABSOLUTE, RELEASE_DATE, ALIAS, REJECTED }

data class EpisodeMatchDecision(val matches: Boolean, val reason: EpisodeMatchReason)

object EpisodeMatchPolicy {
    fun evaluate(request: EpisodeMatchCandidate, candidate: EpisodeMatchCandidate): EpisodeMatchDecision {
        val requestedSeason = request.season
        val requestedEpisode = request.episode
        val candidateSeason = candidate.season
        val candidateEpisode = candidate.episode

        if (requestedSeason != null && requestedEpisode != null &&
            candidateSeason != null && candidateEpisode != null
        ) {
            if (requestedSeason == candidateSeason && requestedEpisode == candidateEpisode) {
                return EpisodeMatchDecision(true, if (requestedSeason == 0) EpisodeMatchReason.SPECIAL else EpisodeMatchReason.EXACT)
            }
            return EpisodeMatchDecision(false, EpisodeMatchReason.REJECTED)
        }

        if (request.absoluteEpisode != null && candidate.absoluteEpisode != null) {
            return EpisodeMatchDecision(
                request.absoluteEpisode == candidate.absoluteEpisode,
                if (request.absoluteEpisode == candidate.absoluteEpisode) EpisodeMatchReason.ABSOLUTE else EpisodeMatchReason.REJECTED
            )
        }

        val requestDate = parseDate(request.released)
        val candidateDate = parseDate(candidate.released)
        if (requestDate != null && candidateDate != null) {
            return EpisodeMatchDecision(
                requestDate == candidateDate,
                if (requestDate == candidateDate) EpisodeMatchReason.RELEASE_DATE else EpisodeMatchReason.REJECTED
            )
        }

        val requestedNames = normalizedNames(request)
        val candidateNames = normalizedNames(candidate)
        val aliasMatch = requestedNames.isNotEmpty() && candidateNames.isNotEmpty() && requestedNames.any(candidateNames::contains)
        return EpisodeMatchDecision(aliasMatch, if (aliasMatch) EpisodeMatchReason.ALIAS else EpisodeMatchReason.REJECTED)
    }

    private fun normalizedNames(value: EpisodeMatchCandidate): Set<String> =
        (value.aliases + listOfNotNull(value.title)).mapNotNull { normalizeTitle(it) }.toSet()

    private fun normalizeTitle(value: String): String? = value
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .takeIf { it.isNotBlank() && !it.matches(Regex("(?:episode|ep|e) \\d+")) }

    private fun parseDate(value: String?): LocalDate? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
    }
}
