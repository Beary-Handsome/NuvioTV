package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktRatingsRequestDto(
    @Json(name = "movies") val movies: List<TraktRatingMovieItemDto>? = null,
    @Json(name = "shows") val shows: List<TraktRatingShowItemDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingMovieItemDto(
    @Json(name = "rating") val rating: Int,
    @Json(name = "rated_at") val ratedAt: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingShowItemDto(
    @Json(name = "rating") val rating: Int,
    @Json(name = "rated_at") val ratedAt: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingsRemoveRequestDto(
    @Json(name = "movies") val movies: List<TraktRatingRemoveItemDto>? = null,
    @Json(name = "shows") val shows: List<TraktRatingRemoveItemDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingRemoveItemDto(
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingItemDto(
    @Json(name = "rated_at") val ratedAt: String? = null,
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingsResponseDto(
    @Json(name = "added") val added: TraktRatingsCountDto? = null,
    @Json(name = "deleted") val deleted: TraktRatingsCountDto? = null,
    @Json(name = "not_found") val notFound: TraktRatingsNotFoundDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingsCountDto(
    @Json(name = "movies") val movies: Int? = null,
    @Json(name = "shows") val shows: Int? = null,
    @Json(name = "seasons") val seasons: Int? = null,
    @Json(name = "episodes") val episodes: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingsNotFoundDto(
    @Json(name = "movies") val movies: List<TraktMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktShowDto>? = null
)
