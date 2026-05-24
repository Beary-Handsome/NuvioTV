package com.nuvio.tv.data.repository.sources

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.tmdb.TmdbGenreCache
import com.nuvio.tv.data.mapper.toCatalogMetaPreview
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.TmdbCatalogs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catalog source backed by direct TMDB API calls.
 *
 * Resolves a [BuiltinTmdbAddon]-backed [CatalogDescriptor] (whose `id` is one
 * of the constants in [TmdbCatalogs]) to the matching TMDB endpoint and
 * maps the response into the same [CatalogRow] shape that the Stremio
 * source emits, so the home pipeline can't tell the difference.
 *
 * Pagination: TMDB returns 20 results per page. Nuvio's `skip` is a global
 * offset in items, so we convert it to a TMDB page index by integer
 * division. `hasMore` mirrors `page < totalPages` from the upstream API.
 */
@Singleton
class TmdbCatalogSource @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val genreCache: TmdbGenreCache,
    private val tmdbSettingsDataStore: com.nuvio.tv.data.local.TmdbSettingsDataStore,
) {
    companion object {
        private const val TAG = "TmdbCatalogSource"
        private const val PAGE_SIZE = TmdbCatalogs.TMDB_PAGE_SIZE
    }

    fun fetch(
        addon: Addon,
        catalog: CatalogDescriptor,
        skip: Int,
        skipStep: Int,
        @Suppress("UNUSED_PARAMETER") extraArgs: Map<String, String>,
        @Suppress("UNUSED_PARAMETER") supportsSkip: Boolean,
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        val page = (skip / PAGE_SIZE) + 1
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank()) {
            Log.e(TAG, "TMDB_API_KEY missing — built-in TMDB catalogs cannot fetch")
            emit(NetworkResult.Error(message = "TMDB API key missing"))
            return@flow
        }

        Log.d(TAG, "fetch catalog=${catalog.id} skip=$skip page=$page")

        val providerSelector = TmdbCatalogs.parseProviderCatalog(catalog.id)
        val genreSelector = TmdbCatalogs.parseGenreCatalog(catalog.id)
        val searchQuery = extraArgs["search"]?.trim()?.takeIf { it.isNotBlank() }
        // Discover-dropdown "Genre" selection. Maps the user-visible name
        // (e.g. "Action") to TMDB's numeric genre id, type-aware so the
        // movie/TV namespaces don't collide. When set, every non-search
        // catalog routes through discover with `with_genres` so the user's
        // pick filters whatever base catalog they're on (Trending → top
        // trending action; Netflix → action shows currently on Netflix).
        val genreNameArg = extraArgs["genre"]?.trim()?.takeIf { it.isNotBlank() }
        val genreOverrideId: Int? = genreNameArg?.let { name ->
            val spec = TmdbCatalogs.GENRES.firstOrNull { it.label.equals(name, ignoreCase = true) }
            if (catalog.type == ContentType.MOVIE) spec?.movieGenreId else spec?.tvGenreId
        }
        val (contentType, response) = when {
            // Search routes through TMDB's dedicated /search endpoints; the
            // catalog id only tells us whether to hit movies or tv. An empty
            // query falls through to an error (we never want to load a
            // catalog row from /search without a query — TMDB returns
            // unrelated popularity-sorted results).
            catalog.id == TmdbCatalogs.SEARCH_MOVIE -> {
                if (searchQuery.isNullOrBlank()) {
                    emit(NetworkResult.Success(emptyCatalogRow(addon, catalog, ContentType.MOVIE)))
                    return@flow
                }
                ContentType.MOVIE to safeApiCall {
                    tmdbApi.searchMovies(query = searchQuery, apiKey = apiKey, page = page)
                }
            }
            catalog.id == TmdbCatalogs.SEARCH_TV -> {
                if (searchQuery.isNullOrBlank()) {
                    emit(NetworkResult.Success(emptyCatalogRow(addon, catalog, ContentType.SERIES)))
                    return@flow
                }
                ContentType.SERIES to safeApiCall {
                    tmdbApi.searchTv(query = searchQuery, apiKey = apiKey, page = page)
                }
            }
            genreSelector != null -> {
                val (genreId, isMovie) = genreSelector
                val type = if (isMovie) ContentType.MOVIE else ContentType.SERIES
                val resp = if (isMovie) {
                    safeApiCall {
                        tmdbApi.discoverMovies(
                            apiKey = apiKey,
                            page = page,
                            sortBy = "popularity.desc",
                            withGenres = genreId.toString(),
                            voteCountGte = 50,
                        )
                    }
                } else {
                    safeApiCall {
                        tmdbApi.discoverTv(
                            apiKey = apiKey,
                            page = page,
                            sortBy = "popularity.desc",
                            withGenres = genreId.toString(),
                            voteCountGte = 50,
                        )
                    }
                }
                type to resp
            }
            providerSelector != null -> {
                val (providerId, isMovie) = providerSelector
                val type = if (isMovie) ContentType.MOVIE else ContentType.SERIES
                // User-configurable region (default US). Provider availability
                // varies by country so this has to round-trip to the prefs
                // store on every fetch — settings are tiny + DataStore caches
                // in memory, so the cost is negligible.
                val watchRegion = tmdbSettingsDataStore.settings.first().watchRegion
                // `flatrate` filters to subscription-streamed titles (the
                // monetization mode that matches "what's on Netflix" intent).
                // `popularity.desc` because TMDB's default sort returns a
                // mess of obscure titles otherwise.
                val resp = if (isMovie) {
                    safeApiCall {
                        tmdbApi.discoverMovies(
                            apiKey = apiKey,
                            page = page,
                            sortBy = "popularity.desc",
                            withGenres = genreOverrideId?.toString(),
                            withWatchProviders = providerId.toString(),
                            watchRegion = watchRegion,
                            watchMonetizationTypes = "flatrate",
                        )
                    }
                } else {
                    safeApiCall {
                        tmdbApi.discoverTv(
                            apiKey = apiKey,
                            page = page,
                            sortBy = "popularity.desc",
                            withGenres = genreOverrideId?.toString(),
                            withWatchProviders = providerId.toString(),
                            watchRegion = watchRegion,
                            watchMonetizationTypes = "flatrate",
                        )
                    }
                }
                type to resp
            }
            // Genre dropdown set but no provider/genre catalog selector —
            // override regular Trending/Popular/Top Rated/etc. endpoints
            // with a discover call so the user's genre actually filters
            // what they see. TMDB's curated lists don't accept genre.
            genreOverrideId != null -> {
                val isMovie = catalog.type == ContentType.MOVIE
                val type = if (isMovie) ContentType.MOVIE else ContentType.SERIES
                val resp = if (isMovie) {
                    safeApiCall {
                        tmdbApi.discoverMovies(
                            apiKey = apiKey,
                            page = page,
                            sortBy = "popularity.desc",
                            withGenres = genreOverrideId.toString(),
                            voteCountGte = 50,
                        )
                    }
                } else {
                    safeApiCall {
                        tmdbApi.discoverTv(
                            apiKey = apiKey,
                            page = page,
                            sortBy = "popularity.desc",
                            withGenres = genreOverrideId.toString(),
                            voteCountGte = 50,
                        )
                    }
                }
                type to resp
            }
            catalog.id == TmdbCatalogs.MOVIE_TRENDING ->
                ContentType.MOVIE to safeApiCall { tmdbApi.getTrendingMovies("week", apiKey, page = page) }
            catalog.id == TmdbCatalogs.MOVIE_POPULAR ->
                ContentType.MOVIE to safeApiCall { tmdbApi.getPopularMovies(apiKey, page = page) }
            catalog.id == TmdbCatalogs.MOVIE_TOP_RATED ->
                ContentType.MOVIE to safeApiCall { tmdbApi.getTopRatedMovies(apiKey, page = page) }
            catalog.id == TmdbCatalogs.MOVIE_NOW_PLAYING ->
                ContentType.MOVIE to safeApiCall { tmdbApi.getNowPlayingMovies(apiKey, page = page) }
            catalog.id == TmdbCatalogs.MOVIE_UPCOMING ->
                ContentType.MOVIE to safeApiCall { tmdbApi.getUpcomingMovies(apiKey, page = page) }
            catalog.id == TmdbCatalogs.TV_TRENDING ->
                ContentType.SERIES to safeApiCall { tmdbApi.getTrendingTv("week", apiKey, page = page) }
            catalog.id == TmdbCatalogs.TV_POPULAR ->
                ContentType.SERIES to safeApiCall { tmdbApi.getPopularTv(apiKey, page = page) }
            catalog.id == TmdbCatalogs.TV_TOP_RATED ->
                ContentType.SERIES to safeApiCall { tmdbApi.getTopRatedTv(apiKey, page = page) }
            catalog.id == TmdbCatalogs.TV_ON_THE_AIR ->
                ContentType.SERIES to safeApiCall { tmdbApi.getOnTheAirTv(apiKey, page = page) }
            catalog.id == TmdbCatalogs.TV_AIRING_TODAY ->
                ContentType.SERIES to safeApiCall { tmdbApi.getAiringTodayTv(apiKey, page = page) }
            else -> {
                Log.w(TAG, "unknown TMDB catalog id=${catalog.id}")
                emit(NetworkResult.Error(message = "Unknown TMDB catalog: ${catalog.id}"))
                return@flow
            }
        }

        when (response) {
            is NetworkResult.Success<TmdbDiscoverResponse> -> {
                val results = response.data.results.orEmpty()
                val items = results
                    .map { result ->
                        val names = if (contentType == ContentType.MOVIE) {
                            genreCache.namesForMovie(result.genreIds)
                        } else {
                            genreCache.namesForTv(result.genreIds)
                        }
                        result.toCatalogMetaPreview(contentType, genres = names)
                    }
                    .distinctBy { it.id }
                val totalPages = response.data.totalPages ?: 1
                val currentPage = response.data.page ?: page
                val hasMore = currentPage < totalPages

                Log.d(
                    TAG,
                    "success catalog=${catalog.id} page=$currentPage/$totalPages items=${items.size}"
                )

                emit(
                    NetworkResult.Success(
                        CatalogRow(
                            addonId = addon.id,
                            addonName = addon.name,
                            addonBaseUrl = addon.baseUrl,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = contentType,
                            rawType = contentType.toApiString(),
                            items = items,
                            isLoading = false,
                            hasMore = hasMore,
                            currentPage = currentPage - 1, // Nuvio uses 0-based page index
                            supportsSkip = true,
                            skipStep = PAGE_SIZE,
                            extraArgs = emptyMap()
                        )
                    )
                )
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "fetch failed catalog=${catalog.id} code=${response.code} message=${response.message}"
                )
                emit(response)
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    /**
     * Empty-row factory used by the search catalogs when the user hasn't
     * typed a query yet. Returning an empty Success row (rather than
     * Loading) keeps the search screen layout stable while waiting.
     */
    private fun emptyCatalogRow(addon: Addon, catalog: CatalogDescriptor, type: ContentType): CatalogRow =
        CatalogRow(
            addonId = addon.id,
            addonName = addon.name,
            addonBaseUrl = addon.baseUrl,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = type,
            rawType = type.toApiString(),
            items = emptyList(),
            isLoading = false,
            hasMore = false,
            currentPage = 0,
            supportsSkip = true,
            skipStep = PAGE_SIZE,
        )
}
