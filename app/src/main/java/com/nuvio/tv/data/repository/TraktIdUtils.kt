package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import java.time.Instant

internal data class ParsedContentIds(
    val trakt: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null
)

internal fun parseContentIds(contentId: String?): ParsedContentIds {
    if (contentId.isNullOrBlank()) return ParsedContentIds()
    val raw = contentId.trim()

    if (raw.startsWith("tt")) {
        return ParsedContentIds(imdb = raw.substringBefore(':'))
    }

    if (raw.startsWith("tmdb:", ignoreCase = true)) {
        // Handle both tmdb:123 and tmdb:movie:123 / tmdb:series:123 forms —
        // take the first numeric segment after the tmdb: prefix. (The old
        // substringBefore(':') grabbed "movie"/"series" and parsed to null,
        // breaking Trakt scrobble/progress for any tmdb-fallback ID.)
        val tmdbNum = raw.split(":").drop(1).firstNotNullOfOrNull { it.toIntOrNull() }
        return ParsedContentIds(tmdb = tmdbNum)
    }

    if (raw.startsWith("trakt:", ignoreCase = true)) {
        return ParsedContentIds(trakt = raw.substringAfter(':').toIntOrNull())
    }

    val numeric = raw.substringBefore(':').toIntOrNull()
    return if (numeric != null) {
        ParsedContentIds(trakt = numeric)
    } else {
        ParsedContentIds()
    }
}

internal fun normalizeContentId(ids: TraktIdsDto?, fallback: String? = null): String {
    val imdb = ids?.imdb?.takeIf { it.isNotBlank() }
    if (!imdb.isNullOrBlank()) return imdb

    val tmdb = ids?.tmdb
    if (tmdb != null) return "tmdb:$tmdb"

    val trakt = ids?.trakt
    if (trakt != null) return "trakt:$trakt"

    return fallback?.takeIf { it.isNotBlank() } ?: ""
}

internal fun toTraktPathId(contentId: String): String {
    val parsed = parseContentIds(contentId)
    return when {
        !parsed.imdb.isNullOrBlank() -> parsed.imdb
        parsed.tmdb != null -> "tmdb:${parsed.tmdb}"
        parsed.trakt != null -> parsed.trakt.toString()
        else -> contentId
    }
}

internal fun extractYear(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    return Regex("(\\d{4})").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

internal fun parseIsoToMillis(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(value).toEpochMilli() }
        .getOrElse { 0L }
}

internal fun toTraktIds(ids: ParsedContentIds): TraktIdsDto {
    return TraktIdsDto(
        trakt = ids.trakt,
        imdb = ids.imdb,
        tmdb = ids.tmdb
    )
}

internal fun TraktIdsDto.hasAnyId(): Boolean {
    return trakt != null || !imdb.isNullOrBlank() || tmdb != null || tvdb != null || !slug.isNullOrBlank()
}
