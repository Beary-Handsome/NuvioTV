package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingIdsItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingMovieAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingShowAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsAddRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsRemoveRequestDto
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
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the user's Trakt ratings: rate (thumbs up/down), unrate, and
 * observe-by-content-id. The data feeds Trakt's recommendation engine —
 * 8-10 boosts similars, 1-4 excludes them, middle values are largely
 * ignored. We emit only 9 (up) and 3 (down) so the algorithm has a
 * strong signal in both directions.
 *
 * Optimistic-update flow: UI flips immediately, network call runs in
 * the background; on failure we roll back and log. Snapshot reuses
 * the same TTL contract as TraktLibraryService so successive screen
 * opens hit the local cache.
 */
@Singleton
class TraktRatingsService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "TraktRatings"
        const val THUMBS_UP_RATING: Int = 9
        const val THUMBS_DOWN_RATING: Int = 3
        private const val CACHE_TTL_MS: Long = 60_000L
    }

    /** Three-state user verdict on a piece of content. */
    enum class Rating { UP, DOWN, NONE }

    private data class Snapshot(
        /** Keyed by `type:id` where id is imdb (preferred), trakt, or tmdb. */
        val ratingsByKey: Map<String, Int> = emptyMap(),
        val fetchedAtMs: Long = 0L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshotState = MutableStateFlow(Snapshot())
    private val refreshMutex = Mutex()

    init {
        // Per-profile state: the active user's ratings get fetched on
        // login/profile switch; everyone else's stays out of memory.
        scope.launch {
            profileManager.activeProfileId.collectLatest {
                snapshotState.value = Snapshot()
                refresh(force = true)
            }
        }
    }

    /**
     * Observe the current verdict for one item. Resolves the moment the
     * cache is fresh; emits NONE if the user hasn't rated it.
     */
    fun observeRating(itemId: String, itemType: String): Flow<Rating> {
        val keys = candidateKeys(itemId, itemType)
        return snapshotState
            .map { snap ->
                // First non-null key wins. Refresh stores under every id
                // form; rate() does the same — so any of these candidates
                // should hit when the user has rated this item.
                val numeric = keys.firstNotNullOfOrNull { k -> snap.ratingsByKey[k] }
                    ?: return@map Rating.NONE
                bucketRating(numeric)
            }
            .distinctUntilChanged()
            .onStart { ensureFresh() }
    }

    /**
     * Observe the granular 1-10 rating for one item. Emits null when
     * unrated. This is the "real" surface — `observeRating` is kept for
     * legacy thumbs-up/down callers but new UI should use this.
     */
    fun observeRatingNumeric(itemId: String, itemType: String): Flow<Int?> {
        val keys = candidateKeys(itemId, itemType)
        return snapshotState
            .map { snap -> keys.firstNotNullOfOrNull { k -> snap.ratingsByKey[k] } }
            .distinctUntilChanged()
            .onStart { ensureFresh() }
    }

    /**
     * Apply a 1-10 rating directly. Pass `null` (or 0) to clear the rating.
     * Drives the star-picker UI; Trakt's recommendation engine uses these
     * numeric scores natively.
     */
    suspend fun rateNumeric(itemId: String, itemType: String, rating: Int?) {
        val numeric = rating?.takeIf { it in 1..10 }
        if (numeric == null) {
            unrate(itemId, itemType)
            return
        }
        val keys = candidateKeys(itemId, itemType)
        val previousMap: Map<String, Int> = keys.associateWith {
            snapshotState.value.ratingsByKey[it] ?: -1
        }
        snapshotState.value = snapshotState.value.copy(
            ratingsByKey = snapshotState.value.ratingsByKey + keys.map { it to numeric }
        )
        try {
            val ids = idsForItem(itemId)
            val body = when (normalizeType(itemType)) {
                "movie" -> TraktRatingsAddRequestDto(
                    movies = listOf(TraktRatingMovieAddDto(rating = numeric, ratedAt = isoNow(), ids = ids))
                )
                "show" -> TraktRatingsAddRequestDto(
                    shows = listOf(TraktRatingShowAddDto(rating = numeric, ratedAt = isoNow(), ids = ids))
                )
                else -> throw IllegalArgumentException("unsupported item type: $itemType")
            }
            val response = traktAuthService.executeAuthorizedRequest { auth ->
                traktApi.addRatings(authorization = auth, body = body)
            } ?: throw IllegalStateException("Trakt request failed")
            if (!response.isSuccessful) throw IllegalStateException("addRatings ${response.code()}")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "rateNumeric($itemId, $itemType, $rating) failed: ${t.message}")
            val restored = snapshotState.value.ratingsByKey.toMutableMap()
            previousMap.forEach { (k, v) -> if (v == -1) restored.remove(k) else restored[k] = v }
            snapshotState.value = snapshotState.value.copy(ratingsByKey = restored)
            throw t
        }
    }

    suspend fun rate(itemId: String, itemType: String, rating: Rating) {
        if (rating == Rating.NONE) {
            unrate(itemId, itemType)
            return
        }
        val numeric = when (rating) {
            Rating.UP -> THUMBS_UP_RATING
            Rating.DOWN -> THUMBS_DOWN_RATING
            else -> return
        }
        // PATCH: write under EVERY known key form for this content so the
        // background refresh from Trakt (which returns IMDb) doesn't appear
        // to overwrite our optimistic write with NONE. Detail screen passes
        // the nav-arg id (e.g. tmdb:movie:78); Trakt returns IMDb tt0083658.
        // Without multi-keying the cache, the UI flipped immediately then
        // reverted on the next observeRating() emit.
        val keys = candidateKeys(itemId, itemType)
        val previousMap: Map<String, Int> = keys.associateWith {
            snapshotState.value.ratingsByKey[it] ?: -1
        }
        snapshotState.value = snapshotState.value.copy(
            ratingsByKey = snapshotState.value.ratingsByKey + keys.map { it to numeric }
        )

        try {
            val ids = idsForItem(itemId)
            val body = when (normalizeType(itemType)) {
                "movie" -> TraktRatingsAddRequestDto(
                    movies = listOf(
                        TraktRatingMovieAddDto(
                            rating = numeric,
                            ratedAt = isoNow(),
                            ids = ids
                        )
                    )
                )
                "show" -> TraktRatingsAddRequestDto(
                    shows = listOf(
                        TraktRatingShowAddDto(
                            rating = numeric,
                            ratedAt = isoNow(),
                            ids = ids
                        )
                    )
                )
                else -> throw IllegalArgumentException("unsupported item type: $itemType")
            }
            val response = traktAuthService.executeAuthorizedRequest { auth ->
                traktApi.addRatings(authorization = auth, body = body)
            } ?: throw IllegalStateException("Trakt request failed")
            if (!response.isSuccessful) {
                throw IllegalStateException("addRatings ${response.code()}")
            }
        } catch (t: Throwable) {
            // Roll back every key we optimistically wrote.
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "rate($itemId, $itemType, $rating) failed: ${t.message}")
            val restored = snapshotState.value.ratingsByKey.toMutableMap()
            previousMap.forEach { (k, v) ->
                if (v == -1) restored.remove(k) else restored[k] = v
            }
            snapshotState.value = snapshotState.value.copy(ratingsByKey = restored)
            throw t
        }
    }

    suspend fun unrate(itemId: String, itemType: String) {
        val keys = candidateKeys(itemId, itemType)
        val previousMap: Map<String, Int> = keys.mapNotNull { k ->
            snapshotState.value.ratingsByKey[k]?.let { k to it }
        }.toMap()
        if (previousMap.isEmpty()) return  // nothing to remove locally

        snapshotState.value = snapshotState.value.copy(
            ratingsByKey = snapshotState.value.ratingsByKey - keys.toSet()
        )

        try {
            val ids = idsForItem(itemId)
            val item = TraktRatingIdsItemDto(ids = ids)
            val body = when (normalizeType(itemType)) {
                "movie" -> TraktRatingsRemoveRequestDto(movies = listOf(item))
                "show" -> TraktRatingsRemoveRequestDto(shows = listOf(item))
                else -> throw IllegalArgumentException("unsupported item type: $itemType")
            }
            val response = traktAuthService.executeAuthorizedRequest { auth ->
                traktApi.removeRatings(authorization = auth, body = body)
            } ?: throw IllegalStateException("Trakt request failed")
            if (!response.isSuccessful) {
                throw IllegalStateException("removeRatings ${response.code()}")
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "unrate($itemId, $itemType) failed: ${t.message}")
            snapshotState.value = snapshotState.value.copy(
                ratingsByKey = snapshotState.value.ratingsByKey + previousMap
            )
            throw t
        }
    }

    suspend fun refresh(force: Boolean = false) {
        if (!force && !isStale()) return
        refreshMutex.withLock {
            if (!force && !isStale()) return@withLock
            val map = mutableMapOf<String, Int>()
            listOf("movies", "shows").forEach { type ->
                runCatching {
                    val response = traktAuthService.executeAuthorizedRequest { auth ->
                        traktApi.getRatings(authorization = auth, type = type)
                    }
                    val items = response?.body().orEmpty()
                    items.forEach { item ->
                        val numeric = item.rating ?: return@forEach
                        // PATCH: store under every id form Trakt knows for
                        // this item — imdb, trakt, AND tmdb — so lookups by
                        // the detail-screen nav-arg (often `tmdb:movie:N`
                        // for TMDB-rooted catalogs) find the same record
                        // that the Trakt /sync/ratings refresh just wrote.
                        val (ids, t) = when {
                            item.movie != null -> (item.movie.ids ?: return@forEach) to "movie"
                            item.show != null -> (item.show.ids ?: return@forEach) to "show"
                            else -> return@forEach
                        }
                        val raw = mutableListOf<String>()
                        ids.imdb?.takeIf { it.isNotBlank() }?.let { raw += it }
                        ids.trakt?.let { raw += it.toString() }
                        ids.tmdb?.let {
                            raw += it.toString()
                            // Detail screens for TMDB-rooted catalogs use
                            // `tmdb:movie:N` / `tmdb:series:N` — write both
                            // bare-tmdb and prefixed forms.
                            raw += "tmdb:$it"
                            raw += "tmdb:${if (t == "movie") "movie" else "series"}:$it"
                        }
                        for (id in raw) {
                            map[ratingKey(id, t)] = numeric
                        }
                    }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "refresh($type) failed: ${it.message}")
                }
            }
            snapshotState.value = Snapshot(
                ratingsByKey = map,
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

    private fun bucketRating(rating: Int): Rating = when {
        rating >= 7 -> Rating.UP
        rating <= 4 -> Rating.DOWN
        else -> Rating.NONE
    }

    private fun isoNow(): String =
        OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** itemId may be IMDb (`tt12345`), bare tmdb (`78`), prefixed tmdb
     *  (`tmdb:78`, `tmdb:movie:78`, `tmdb:series:1399`), or trakt id.
     *  Decode via the shared parser so every form gets the right ids
     *  posted to Trakt — without this, `tmdb:movie:78` hit the `else`
     *  branch and sent `slug="tmdb:movie:78"` which Trakt 404s. */
    private fun idsForItem(itemId: String): TraktIdsDto {
        val parsed = parseContentIds(itemId)
        return TraktIdsDto(
            imdb = parsed.imdb,
            tmdb = parsed.tmdb,
            trakt = parsed.trakt,
        )
    }

    private fun normalizeType(itemType: String): String = when (itemType.lowercase()) {
        "movie", "movies" -> "movie"
        "show", "shows", "series", "tv" -> "show"
        else -> itemType.lowercase()
    }

    private fun ratingKey(itemId: String, itemType: String): String =
        "${normalizeType(itemType)}:${itemId.lowercase()}"

    /**
     * Every key form this content might be looked up under. We write under
     * all of them when the user rates, and refresh() also expands all known
     * cross-format ids when reading from Trakt — so a tap from the TMDB-rooted
     * detail screen survives a refresh that returned the IMDb id.
     *
     * Covers: the raw nav-arg AS-IS, plus the bare numeric/imdb extracted
     * via parseContentIds (which understands `tmdb:type:N`, `tmdb:N`,
     * `ttN`, `trakt:N`).
     */
    private fun candidateKeys(itemId: String, itemType: String): List<String> {
        val keys = linkedSetOf<String>()
        keys += ratingKey(itemId, itemType)

        val parsed = parseContentIds(itemId)
        parsed.imdb?.takeIf { it.isNotBlank() }?.let { keys += ratingKey(it, itemType) }
        parsed.tmdb?.let {
            keys += ratingKey(it.toString(), itemType)
            keys += ratingKey("tmdb:$it", itemType)
            // Detail screens may also store with the type discriminator
            // (`tmdb:movie:N` / `tmdb:series:N`).
            val td = if (normalizeType(itemType) == "show") "series" else "movie"
            keys += ratingKey("tmdb:$td:$it", itemType)
        }
        parsed.trakt?.let { keys += ratingKey(it.toString(), itemType) }
        return keys.toList()
    }
}
