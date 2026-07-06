package com.nuvio.tv.core.trakt

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.normalizeContentId
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personalized Trakt recommendation rows for the home screen. Requires the
 * user's Trakt token (via [TraktAuthService.executeAuthorizedRequest]); returns
 * empty rows when not logged in. Trakt recommendation payloads carry only ids
 * (artwork is VIP-gated), so posters are backfilled from TMDB.
 */
@Singleton
class TraktRecommendationsService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService
) {
    companion object {
        private const val TAG = "TraktRecsSvc"
        const val MOVIES_CATALOG_ID = "trakt_recommended_movies"
        const val SHOWS_CATALOG_ID = "trakt_recommended_shows"
    }

    suspend fun recommendedMoviesRow(language: String): CatalogRow? {
        val response = try {
            traktAuthService.executeAuthorizedRequest { authHeader -> traktApi.getRecommendedMovies(authHeader) }
        } catch (e: Exception) {
            Log.w(TAG, "recommended movies fetch failed", e)
            null
        } ?: return null
        if (!response.isSuccessful) return null
        val dtos = response.body().orEmpty()
        if (dtos.isEmpty()) return null
        val items = coroutineScope {
            dtos.map { dto -> async { dto.toPreview(language) } }.awaitAll().filterNotNull()
        }
        if (items.isEmpty()) return null
        return row(MOVIES_CATALOG_ID, "Recommended Movies", ContentType.MOVIE, "movie", items)
    }

    suspend fun recommendedShowsRow(language: String): CatalogRow? {
        val response = try {
            traktAuthService.executeAuthorizedRequest { authHeader -> traktApi.getRecommendedShows(authHeader) }
        } catch (e: Exception) {
            Log.w(TAG, "recommended shows fetch failed", e)
            null
        } ?: return null
        if (!response.isSuccessful) return null
        val dtos = response.body().orEmpty()
        if (dtos.isEmpty()) return null
        val items = coroutineScope {
            dtos.map { dto -> async { dto.toPreview(language) } }.awaitAll().filterNotNull()
        }
        if (items.isEmpty()) return null
        return row(SHOWS_CATALOG_ID, "Recommended Shows", ContentType.SERIES, "series", items)
    }

    private fun row(
        catalogId: String,
        catalogName: String,
        type: ContentType,
        rawType: String,
        items: List<MetaPreview>
    ): CatalogRow = CatalogRow(
        addonId = "trakt",
        addonName = "Trakt",
        addonBaseUrl = "",
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        rawType = rawType,
        items = items,
        isLoading = false,
        hasMore = false,
        supportsSkip = false
    )

    private suspend fun TraktMovieDto.toPreview(language: String): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val contentId = normalizeContentId(ids, ids?.trakt?.let { "trakt:$it" })
        if (contentId.isBlank()) return null
        var poster: String? = null
        var backdrop: String? = null
        var logo: String? = null
        val tmdbId = runCatching { tmdbService.ensureTmdbId(contentId, "movie") }.getOrNull()
        if (tmdbId != null) {
            val enrichment = runCatching {
                tmdbMetadataService.fetchEnrichment(tmdbId, ContentType.MOVIE, language)
            }.getOrNull()
            poster = enrichment?.poster
            backdrop = enrichment?.backdrop
            logo = enrichment?.logo
        }
        return MetaPreview(
            id = contentId,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = title,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = backdrop,
            logo = logo,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = year?.toString() ?: released?.take(4),
            imdbRating = rating?.toFloat(),
            genres = genres.orEmpty(),
            imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
            slug = ids?.slug?.takeIf { it.isNotBlank() }
        )
    }

    private suspend fun TraktShowDto.toPreview(language: String): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val contentId = normalizeContentId(ids, ids?.trakt?.let { "trakt:$it" })
        if (contentId.isBlank()) return null
        var poster: String? = null
        var backdrop: String? = null
        var logo: String? = null
        val tmdbId = runCatching { tmdbService.ensureTmdbId(contentId, "series") }.getOrNull()
        if (tmdbId != null) {
            val enrichment = runCatching {
                tmdbMetadataService.fetchEnrichment(tmdbId, ContentType.SERIES, language)
            }.getOrNull()
            poster = enrichment?.poster
            backdrop = enrichment?.backdrop
            logo = enrichment?.logo
        }
        return MetaPreview(
            id = contentId,
            type = ContentType.SERIES,
            rawType = "series",
            name = title,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = backdrop,
            logo = logo,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = year?.toString() ?: firstAired?.take(4),
            imdbRating = rating?.toFloat(),
            genres = genres.orEmpty(),
            imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
            slug = ids?.slug?.takeIf { it.isNotBlank() }
        )
    }
}
