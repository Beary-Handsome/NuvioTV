package com.nuvio.tv.data.repository.sources

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.trakt.traktBestBackdropUrl
import com.nuvio.tv.core.trakt.traktBestLogoUrl
import com.nuvio.tv.core.trakt.traktBestPosterUrl
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TraktCatalogs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catalog source backed by the Trakt API.
 *
 * Resolves a [BuiltinTraktAddon]-backed [CatalogDescriptor] to the matching
 * Trakt endpoint (recommendations or watchlist), authenticates via
 * [TraktAuthService.executeAuthorizedRequest] (which handles token refresh
 * + retries + the existing circuit breaker), and maps the response into a
 * [CatalogRow].
 *
 * If the user is not logged in, the source returns an empty row instead
 * of an error so the home pipeline silently no-ops.
 */
@Singleton
class TraktCatalogSource @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val traktAuthDataStore: TraktAuthDataStore,
) {
    companion object {
        private const val TAG = "TraktCatalogSource"
    }

    fun fetch(
        addon: Addon,
        catalog: CatalogDescriptor,
        @Suppress("UNUSED_PARAMETER") skip: Int,
        @Suppress("UNUSED_PARAMETER") skipStep: Int,
        @Suppress("UNUSED_PARAMETER") extraArgs: Map<String, String>,
        @Suppress("UNUSED_PARAMETER") supportsSkip: Boolean,
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            Log.d(TAG, "fetch catalog=${catalog.id}: trakt not authenticated, emitting empty row")
            emit(NetworkResult.Success(emptyRow(addon, catalog)))
            return@flow
        }

        Log.d(TAG, "fetch catalog=${catalog.id}")

        val items: List<MetaPreview> = when (catalog.id) {
            TraktCatalogs.WATCHLIST -> fetchWatchlist()
            TraktCatalogs.RECOMMENDED_MOVIES -> fetchRecommendedMovies()
            TraktCatalogs.RECOMMENDED_SHOWS -> fetchRecommendedShows()
            else -> {
                Log.w(TAG, "unknown Trakt catalog id=${catalog.id}")
                emit(NetworkResult.Error(message = "Unknown Trakt catalog: ${catalog.id}"))
                return@flow
            }
        }

        emit(
            NetworkResult.Success(
                CatalogRow(
                    addonId = addon.id,
                    addonName = addon.name,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.type,
                    rawType = catalog.rawType,
                    items = items,
                    isLoading = false,
                    hasMore = false,
                    currentPage = 0,
                    supportsSkip = false,
                    skipStep = TraktCatalogs.TRAKT_PAGE_SIZE,
                    extraArgs = emptyMap(),
                    // Watchlist carries movies + shows merged by listed_at;
                    // the catalog descriptor's type is MOVIE only because
                    // CatalogDescriptor requires a single type. Mark mixed
                    // so See-All / chip-label code knows to special-case.
                    mixedType = catalog.id == TraktCatalogs.WATCHLIST,
                )
            )
        )
    }

    private suspend fun fetchRecommendedMovies(): List<MetaPreview> {
        val response = traktAuthService.executeAuthorizedRequest { auth ->
            traktApi.getRecommendedMovies(authorization = auth)
        } ?: return emptyList()
        if (!response.isSuccessful) {
            Log.w(TAG, "recommended movies failed code=${response.code()}")
            return emptyList()
        }
        return response.body().orEmpty().mapNotNull { it.toMetaPreview(ContentType.MOVIE) }
    }

    private suspend fun fetchRecommendedShows(): List<MetaPreview> {
        val response = traktAuthService.executeAuthorizedRequest { auth ->
            traktApi.getRecommendedShows(authorization = auth)
        } ?: return emptyList()
        if (!response.isSuccessful) {
            Log.w(TAG, "recommended shows failed code=${response.code()}")
            return emptyList()
        }
        return response.body().orEmpty().mapNotNull { it.toMetaPreview(ContentType.SERIES) }
    }

    /**
     * The watchlist row is mixed-type. Trakt returns separate movies + shows
     * lists (one HTTP call each), and we merge them by `listed_at` desc so
     * the "most recently added to my list" item is first.
     */
    private suspend fun fetchWatchlist(): List<MetaPreview> = coroutineScope {
        val moviesDeferred = async { fetchWatchlistType("movies") }
        val showsDeferred = async { fetchWatchlistType("shows") }
        val movies = moviesDeferred.await()
        val shows = showsDeferred.await()
        (movies + shows)
            .sortedByDescending { (_, listedAtMs) -> listedAtMs }
            .map { it.first }
            .distinctBy { it.id }
            .take(TraktCatalogs.TRAKT_PAGE_SIZE)
    }

    private suspend fun fetchWatchlistType(type: String): List<Pair<MetaPreview, Long>> {
        val response = traktAuthService.executeAuthorizedRequest { auth ->
            traktApi.getWatchlistByAdded(authorization = auth, type = type)
        } ?: return emptyList()
        if (!response.isSuccessful) {
            Log.w(TAG, "watchlist $type failed code=${response.code()}")
            return emptyList()
        }
        return response.body().orEmpty().mapNotNull { dto -> dto.toMetaPreviewWithListedAt() }
    }

    private fun emptyRow(addon: Addon, catalog: CatalogDescriptor): CatalogRow =
        CatalogRow(
            addonId = addon.id,
            addonName = addon.name,
            addonBaseUrl = addon.baseUrl,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.type,
            rawType = catalog.rawType,
            items = emptyList(),
            isLoading = false,
            hasMore = false,
            currentPage = 0,
            supportsSkip = false,
            skipStep = TraktCatalogs.TRAKT_PAGE_SIZE,
        )
}

private fun TraktListItemDto.toMetaPreviewWithListedAt(): Pair<MetaPreview, Long>? {
    val listedAtMs = parseIsoMillis(listedAt) ?: 0L
    movie?.let { return it.toMetaPreview(ContentType.MOVIE)?.let { mp -> mp to listedAtMs } }
    show?.let { return it.toMetaPreview(ContentType.SERIES)?.let { mp -> mp to listedAtMs } }
    return null
}

private fun TraktMovieDto.toMetaPreview(type: ContentType): MetaPreview? =
    buildMetaPreview(
        type = type,
        title = title ?: originalTitle,
        ids = ids,
        overview = overview,
        releaseDate = released,
        year = year,
        rating = rating,
        genres = genres,
        runtimeMinutes = runtime,
        images = images,
    )

private fun TraktShowDto.toMetaPreview(type: ContentType): MetaPreview? =
    buildMetaPreview(
        type = type,
        title = title ?: originalTitle,
        ids = ids,
        overview = overview,
        releaseDate = firstAired,
        year = year,
        rating = rating,
        genres = genres,
        runtimeMinutes = runtime,
        images = images,
    )

private fun buildMetaPreview(
    type: ContentType,
    title: String?,
    ids: TraktIdsDto?,
    overview: String?,
    releaseDate: String?,
    year: Int?,
    rating: Double?,
    genres: List<String>?,
    runtimeMinutes: Int?,
    images: TraktImagesDto?,
): MetaPreview? {
    val normalizedTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val contentId = canonicalContentId(type, ids) ?: return null

    val poster = images.traktBestPosterUrl()
    val backdrop = images.traktBestBackdropUrl()
    val logo = images.traktBestLogoUrl()
    val releaseInfo = year?.toString() ?: releaseDate?.takeIf { it.length >= 4 }?.substring(0, 4)

    return MetaPreview(
        id = contentId,
        type = type,
        rawType = type.toApiString(),
        name = normalizedTitle,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = backdrop,
        logo = logo,
        description = overview?.trim()?.takeIf { it.isNotBlank() },
        releaseInfo = releaseInfo,
        imdbRating = rating?.takeIf { it > 0 }?.toFloat(),
        genres = genres.orEmpty(),
        runtime = runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
        released = releaseDate?.takeIf { it.isNotBlank() },
        imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
        slug = ids?.slug?.takeIf { it.isNotBlank() },
        rawPosterUrl = poster,
        landscapePoster = backdrop,
    )
}

/**
 * Produce a content id compatible with the rest of the Nuvio pipeline.
 *
 * Prefer `tmdb:<type>:<id>` (matches the TMDB-native catalog source's id
 * shape, so the stream/meta downstreams resolve uniformly), falling back
 * to the IMDb `tt…` id Stremio addons use natively. Items without either
 * are skipped — without a routable id the row entry would 404 on click.
 */
private fun canonicalContentId(type: ContentType, ids: TraktIdsDto?): String? {
    if (ids == null) return null
    ids.tmdb?.let { return "tmdb:${type.toApiString().lowercase()}:$it" }
    ids.imdb?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

private fun parseIsoMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}
