package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingExternalIds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class SimklRatingsService @Inject constructor(
    private val client: SimklApiClient,
    private val authRepository: SimklAuthRepository,
    private val syncRepository: SimklSyncRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun isAuthenticated(): Boolean = authRepository.state.value.isAuthenticated

    suspend fun rate(media: TrackingMediaReference, rating: Int): Boolean {
        if (!isAuthenticated() || !media.hasResolvableIdentity) return false
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/ratings",
                body = buildSimklRatingBody(media, rating.coerceIn(1, 10)),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val applied = simklRatingApplied(response.body, json)
        if (applied) syncRepository.refreshAsync(com.nuvio.tv.core.tracking.TrackingRefreshIntent.INVALIDATED)
        return applied
    }

    suspend fun clear(media: TrackingMediaReference): Boolean {
        if (!isAuthenticated() || !media.hasResolvableIdentity) return false
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/ratings/remove",
                body = buildSimklRatingBody(media, null),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val key = if (media.kind == TrackingMediaKind.MOVIE) "movies" else "shows"
        val deleted = simklRatingCleared(response.body, key, json)
        if (deleted) syncRepository.refreshAsync(com.nuvio.tv.core.tracking.TrackingRefreshIntent.INVALIDATED)
        return deleted
    }

    suspend fun currentRating(media: TrackingMediaReference): Int? {
        if (!isAuthenticated()) return null
        syncRepository.ensureLoaded()
        return syncRepository.state.value.snapshot.entries.firstOrNull { entry ->
            entry.media?.toTrackingExternalIds()?.sharesIdentityWith(media.ids) == true
        }?.userRating
    }

}

internal fun buildSimklRatingBody(media: TrackingMediaReference, rating: Int?): String {
    val item = buildJsonObject {
        rating?.coerceIn(1, 10)?.let { put("rating", it) }
        media.title?.let { put("title", it) }
        media.year?.let { put("year", it) }
        media.ids.toSimklJsonObjectOrNull()?.let { put("ids", it) }
    }
    val key = if (media.kind == TrackingMediaKind.MOVIE) "movies" else "shows"
    return buildJsonObject { put(key, buildJsonArray { add(item) }) }.toString()
}

internal fun simklRatingApplied(body: String, json: Json = Json): Boolean = runCatching {
    json.parseToJsonElement(body).jsonObject["added"]
        ?.jsonObject?.get("statuses")?.jsonArray?.isNotEmpty() == true
}.getOrDefault(false)

internal fun simklRatingCleared(body: String, key: String, json: Json = Json): Boolean = runCatching {
    json.parseToJsonElement(body).jsonObject["deleted"]
        ?.jsonObject?.get(key)?.jsonPrimitive?.intOrNull ?: 0
}.getOrDefault(0) > 0

private fun TrackingExternalIds.sharesIdentityWith(other: TrackingExternalIds): Boolean =
    (simkl != null && simkl == other.simkl) ||
        (!imdb.isNullOrBlank() && imdb.equals(other.imdb, ignoreCase = true)) ||
        (tmdb != null && tmdb == other.tmdb) ||
        (!tvdb.isNullOrBlank() && tvdb == other.tvdb) ||
        (mal != null && mal == other.mal) ||
        (anidb != null && anidb == other.anidb) ||
        (anilist != null && anilist == other.anilist) ||
        (kitsu != null && kitsu == other.kitsu)
