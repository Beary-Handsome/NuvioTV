package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.tmdbContentId

/**
 * TMDB image path → CDN URL. TMDB ships path stubs (e.g. `/abc.jpg`) and
 * expects clients to prepend a configured CDN host + size. `w500` is a
 * good balance for poster art at the TV resolutions Nuvio renders at
 * (rows are roughly 160dp wide so 500px is enough headroom).
 */
private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/"
private const val POSTER_SIZE = "w500"
private const val BACKDROP_SIZE = "w1280"

private fun posterUrl(path: String?): String? =
    path?.takeIf { it.isNotBlank() }?.let { "$TMDB_IMAGE_BASE$POSTER_SIZE$it" }

private fun backdropUrl(path: String?): String? =
    path?.takeIf { it.isNotBlank() }?.let { "$TMDB_IMAGE_BASE$BACKDROP_SIZE$it" }

/**
 * Map a TMDB list item into Nuvio's catalog [MetaPreview] shape.
 *
 * The [id] field carries a `tmdb:movie:<n>` or `tmdb:series:<n>` sentinel
 * so the downstream stream/meta endpoints know to look the title up by
 * TMDB id (against the Riven addon's MediaItem.tmdb_id column) rather
 * than the traditional IMDb id used by Stremio meta addons.
 *
 * `genres` is populated from a pre-resolved name list — callers that
 * already have a `TmdbGenreCache` mapping pass the names in; callers that
 * don't (legacy paths) leave it empty.
 */
fun TmdbDiscoverResult.toCatalogMetaPreview(
    type: ContentType,
    genres: List<String> = emptyList(),
): MetaPreview {
    val title = title ?: name ?: originalTitle ?: originalName ?: "Untitled"
    val release = releaseDate ?: firstAirDate
    return MetaPreview(
        id = tmdbContentId(id.toLong(), type),
        type = type,
        rawType = type.toApiString(),
        name = title,
        poster = posterUrl(posterPath),
        posterShape = PosterShape.POSTER,
        background = backdropUrl(backdropPath),
        logo = null,
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = release?.takeIf { it.length >= 4 }?.substring(0, 4),
        imdbRating = voteAverage?.takeIf { it > 0 }?.toFloat(),
        genres = genres,
        released = release,
        rawPosterUrl = posterUrl(posterPath)
    )
}
