package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazily-populated TMDB genre-id → name map.
 *
 * TMDB list endpoints (discover, trending, popular, search) ship items
 * with only `genre_ids: [28, 35, …]`; resolving to human-readable names
 * needs a separate call to `/genre/movie/list` + `/genre/tv/list`. The
 * lists are tiny (~20 entries each) and stable across the API's lifetime,
 * so we fetch once on first use and reuse forever in-process.
 *
 * Movie and TV namespaces share most ids but TV has a few extras
 * (Sci-Fi & Fantasy = 10765, War & Politics = 10768) — store them in
 * separate maps so a movie-mode card with id 10765 doesn't accidentally
 * resolve to a TV genre name.
 */
@Singleton
class TmdbGenreCache @Inject constructor(
    private val tmdbApi: TmdbApi,
) {
    companion object {
        private const val TAG = "TmdbGenreCache"
    }

    private val mutex = Mutex()
    @Volatile private var movieMap: Map<Int, String>? = null
    @Volatile private var tvMap: Map<Int, String>? = null

    suspend fun namesForMovie(ids: List<Int>?): List<String> {
        if (ids.isNullOrEmpty()) return emptyList()
        val map = ensureMovie() ?: return emptyList()
        return ids.mapNotNull { map[it] }
    }

    suspend fun namesForTv(ids: List<Int>?): List<String> {
        if (ids.isNullOrEmpty()) return emptyList()
        val map = ensureTv() ?: return emptyList()
        return ids.mapNotNull { map[it] }
    }

    private suspend fun ensureMovie(): Map<Int, String>? {
        movieMap?.let { return it }
        return mutex.withLock {
            movieMap ?: loadMovie().also { movieMap = it }
        }
    }

    private suspend fun ensureTv(): Map<Int, String>? {
        tvMap?.let { return it }
        return mutex.withLock {
            tvMap ?: loadTv().also { tvMap = it }
        }
    }

    private suspend fun loadMovie(): Map<Int, String>? = withContext(Dispatchers.IO) {
        val key = BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return@withContext null
        runCatching {
            val resp = tmdbApi.getMovieGenres(key)
            resp.body()?.genres.orEmpty().associate { it.id to it.name }
        }.onFailure { Log.w(TAG, "movie genre fetch failed: ${it.message}") }
            .getOrNull()
    }

    private suspend fun loadTv(): Map<Int, String>? = withContext(Dispatchers.IO) {
        val key = BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return@withContext null
        runCatching {
            val resp = tmdbApi.getTvGenres(key)
            resp.body()?.genres.orEmpty().associate { it.id to it.name }
        }.onFailure { Log.w(TAG, "tv genre fetch failed: ${it.message}") }
            .getOrNull()
    }
}
