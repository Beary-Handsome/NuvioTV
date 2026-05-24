package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktFavoriteItemAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktFavoritesAddRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktFavoritesRemoveRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingIdsItemDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the user's Trakt favorites — the heart icon ❤️.
 *
 * Structure mirrors [TraktRatingsService] (same multi-key cache pattern, same
 * optimistic flow, same refresh contract). Favorites are a boolean per item
 * rather than a numeric value; otherwise the lifecycle is identical.
 *
 * The Trakt favorites surface drives the "Recommended from Favorites" track
 * on trakt.tv and is separate from ratings (you can favorite without rating).
 */
@Singleton
class TraktFavoritesService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "TraktFavorites"
        private const val CACHE_TTL_MS: Long = 60_000L
    }

    private data class Snapshot(
        val favoritedKeys: Set<String> = emptySet(),
        val fetchedAtMs: Long = 0L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshotState = MutableStateFlow(Snapshot())
    private val refreshMutex = Mutex()

    init {
        // Per-profile state: reset + re-fetch on profile switch.
        scope.launch {
            profileManager.activeProfileId.collectLatest {
                snapshotState.value = Snapshot()
                refresh(force = true)
            }
        }
    }

    fun observeFavorited(itemId: String, itemType: String): Flow<Boolean> {
        return snapshotState
            .map { snap ->
                candidateKeys(itemId, itemType).any { it in snap.favoritedKeys }
            }
            .distinctUntilChanged()
            .onStart { ensureFresh() }
    }

    suspend fun setFavorited(itemId: String, itemType: String, favorited: Boolean) {
        val keys = candidateKeys(itemId, itemType)
        val previouslyFavorited = keys.any { it in snapshotState.value.favoritedKeys }
        if (previouslyFavorited == favorited) return

        // Optimistic local update across all key variants.
        snapshotState.value = if (favorited) {
            snapshotState.value.copy(favoritedKeys = snapshotState.value.favoritedKeys + keys)
        } else {
            snapshotState.value.copy(favoritedKeys = snapshotState.value.favoritedKeys - keys.toSet())
        }

        try {
            val ids = idsForItem(itemId)
            val ok = if (favorited) {
                val body = when (normalizeType(itemType)) {
                    "movie" -> TraktFavoritesAddRequestDto(
                        movies = listOf(TraktFavoriteItemAddDto(ids = ids))
                    )
                    "show" -> TraktFavoritesAddRequestDto(
                        shows = listOf(TraktFavoriteItemAddDto(ids = ids))
                    )
                    else -> throw IllegalArgumentException("unsupported item type: $itemType")
                }
                val resp = traktAuthService.executeAuthorizedRequest { auth ->
                    traktApi.addFavorites(authorization = auth, body = body)
                } ?: throw IllegalStateException("Trakt request failed")
                resp.isSuccessful
            } else {
                val item = TraktRatingIdsItemDto(ids = ids)
                val body = when (normalizeType(itemType)) {
                    "movie" -> TraktFavoritesRemoveRequestDto(movies = listOf(item))
                    "show" -> TraktFavoritesRemoveRequestDto(shows = listOf(item))
                    else -> throw IllegalArgumentException("unsupported item type: $itemType")
                }
                val resp = traktAuthService.executeAuthorizedRequest { auth ->
                    traktApi.removeFavorites(authorization = auth, body = body)
                } ?: throw IllegalStateException("Trakt request failed")
                resp.isSuccessful
            }
            if (!ok) throw IllegalStateException("trakt favorites response non-2xx")
        } catch (t: Throwable) {
            // Roll back optimistic state.
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "setFavorited($itemId, $itemType, $favorited) failed: ${t.message}")
            snapshotState.value = if (previouslyFavorited) {
                snapshotState.value.copy(favoritedKeys = snapshotState.value.favoritedKeys + keys)
            } else {
                snapshotState.value.copy(favoritedKeys = snapshotState.value.favoritedKeys - keys.toSet())
            }
            throw t
        }
    }

    suspend fun refresh(force: Boolean = false) {
        if (!force && !isStale()) return
        refreshMutex.withLock {
            if (!force && !isStale()) return@withLock
            val keys = mutableSetOf<String>()
            listOf("movies", "shows").forEach { type ->
                runCatching {
                    val response = traktAuthService.executeAuthorizedRequest { auth ->
                        traktApi.getFavorites(authorization = auth, type = type)
                    }
                    val items = response?.body().orEmpty()
                    items.forEach { item ->
                        val (ids, t) = when {
                            item.movie != null -> (item.movie.ids ?: return@forEach) to "movie"
                            item.show != null -> (item.show.ids ?: return@forEach) to "show"
                            else -> return@forEach
                        }
                        // Store under EVERY id variant for the item — matches
                        // the multi-key strategy in TraktRatingsService.
                        ids.imdb?.takeIf { it.isNotBlank() }?.let { keys += ratingKey(it, t) }
                        ids.trakt?.let { keys += ratingKey(it.toString(), t) }
                        ids.tmdb?.let {
                            keys += ratingKey(it.toString(), t)
                            keys += ratingKey("tmdb:$it", t)
                            val td = if (t == "show") "series" else "movie"
                            keys += ratingKey("tmdb:$td:$it", t)
                        }
                    }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "refresh($type) failed: ${it.message}")
                }
            }
            snapshotState.value = Snapshot(
                favoritedKeys = keys,
                fetchedAtMs = System.currentTimeMillis()
            )
        }
    }

    private suspend fun ensureFresh() {
        if (isStale()) refresh(force = false)
    }

    private fun isStale(): Boolean {
        val fetched = snapshotState.value.fetchedAtMs
        return System.currentTimeMillis() - fetched > CACHE_TTL_MS
    }

    private fun idsForItem(itemId: String): TraktIdsDto {
        val parsed = parseContentIds(itemId)
        return TraktIdsDto(imdb = parsed.imdb, tmdb = parsed.tmdb, trakt = parsed.trakt)
    }

    private fun normalizeType(itemType: String): String = when (itemType.lowercase()) {
        "movie", "movies" -> "movie"
        "show", "shows", "series", "tv" -> "show"
        else -> itemType.lowercase()
    }

    private fun ratingKey(itemId: String, itemType: String): String =
        "${normalizeType(itemType)}:${itemId.lowercase()}"

    private fun candidateKeys(itemId: String, itemType: String): List<String> {
        val keys = linkedSetOf<String>()
        keys += ratingKey(itemId, itemType)
        val parsed = parseContentIds(itemId)
        parsed.imdb?.takeIf { it.isNotBlank() }?.let { keys += ratingKey(it, itemType) }
        parsed.tmdb?.let {
            keys += ratingKey(it.toString(), itemType)
            keys += ratingKey("tmdb:$it", itemType)
            val td = if (normalizeType(itemType) == "show") "series" else "movie"
            keys += ratingKey("tmdb:$td:$it", itemType)
        }
        parsed.trakt?.let { keys += ratingKey(it.toString(), itemType) }
        return keys.toList()
    }
}
