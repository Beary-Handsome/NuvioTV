package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingMovieItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingRemoveItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingShowItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsRemoveRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsRequestDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Push/read the user's Trakt ratings. The UI uses a 5-star control; Trakt
 * stores a 1..10 value, so a star maps to star * 2 (5★ = 10 … 1★ = 2).
 */
@Singleton
class TraktRatingsService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    companion object {
        private const val TAG = "TraktRatingsSvc"
    }

    /** Apply a 1..5 star rating to the item. Returns true on success. */
    suspend fun rate(itemId: String, imdbId: String?, apiType: String, stars: Int): Boolean {
        val ids = buildIds(itemId, imdbId)
        if (!ids.hasAnyId()) return false
        val rating = stars.coerceIn(1, 5) * 2
        val body = if (isMovie(apiType)) {
            TraktRatingsRequestDto(movies = listOf(TraktRatingMovieItemDto(rating = rating, ids = ids)))
        } else {
            TraktRatingsRequestDto(shows = listOf(TraktRatingShowItemDto(rating = rating, ids = ids)))
        }
        val response = try {
            traktAuthService.executeAuthorizedWriteRequest { authHeader -> traktApi.addRatings(authHeader, body) }
        } catch (e: Exception) {
            Log.w(TAG, "rate failed", e)
            null
        }
        return response?.isSuccessful == true
    }

    /** Remove any rating on the item. Returns true on success. */
    suspend fun clear(itemId: String, imdbId: String?, apiType: String): Boolean {
        val ids = buildIds(itemId, imdbId)
        if (!ids.hasAnyId()) return false
        val item = TraktRatingRemoveItemDto(ids = ids)
        val body = if (isMovie(apiType)) {
            TraktRatingsRemoveRequestDto(movies = listOf(item))
        } else {
            TraktRatingsRemoveRequestDto(shows = listOf(item))
        }
        val response = try {
            traktAuthService.executeAuthorizedWriteRequest { authHeader -> traktApi.removeRatings(authHeader, body) }
        } catch (e: Exception) {
            Log.w(TAG, "clear rating failed", e)
            null
        }
        return response?.isSuccessful == true
    }

    /** The item's current Trakt rating (1..10), or null if unrated / unavailable. */
    suspend fun currentRating(itemId: String, imdbId: String?, apiType: String): Int? {
        val ids = buildIds(itemId, imdbId)
        if (!ids.hasAnyId()) return null
        val type = if (isMovie(apiType)) "movies" else "shows"
        val response = try {
            traktAuthService.executeAuthorizedRequest { authHeader -> traktApi.getRatings(authHeader, type) }
        } catch (e: Exception) {
            Log.w(TAG, "currentRating fetch failed", e)
            null
        }
        if (response?.isSuccessful != true) return null
        return response.body().orEmpty().firstOrNull { entry ->
            val entryIds = entry.movie?.ids ?: entry.show?.ids
            (ids.imdb != null && entryIds?.imdb == ids.imdb) ||
                (ids.tmdb != null && entryIds?.tmdb == ids.tmdb) ||
                (ids.trakt != null && entryIds?.trakt == ids.trakt)
        }?.rating
    }

    private fun buildIds(itemId: String, imdbId: String?) =
        toTraktIds(
            parseContentIds(itemId).let { parsed ->
                if (parsed.imdb == null && !imdbId.isNullOrBlank()) parsed.copy(imdb = imdbId) else parsed
            }
        )

    private fun isMovie(apiType: String) = apiType.equals("movie", ignoreCase = true)
}
