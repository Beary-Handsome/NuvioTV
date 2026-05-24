package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit interface for OpenSubtitles.com REST API v1.
 *
 * Auth: requires a User-Agent header on every call (the API rejects
 * unbranded ones). An Api-Key header is optional but increases the per-IP
 * rate limit from a handful of searches per 15 minutes to a usable rate.
 *
 * The base URL is `https://api.opensubtitles.com/api/v1/`.
 */
interface OpenSubtitlesApi {

    @GET("subtitles")
    suspend fun searchSubtitles(
        @Header("User-Agent") userAgent: String,
        @Header("Api-Key") apiKey: String?,
        @Query("imdb_id") imdbId: String? = null,
        @Query("tmdb_id") tmdbId: String? = null,
        @Query("query") query: String? = null,
        @Query("season_number") season: Int? = null,
        @Query("episode_number") episode: Int? = null,
        @Query("languages") languages: String? = null,
        @Query("moviehash") movieHash: String? = null,
        @Query("type") type: String? = null,
    ): Response<OsSearchResponse>

    @POST("download")
    suspend fun requestDownload(
        @Header("User-Agent") userAgent: String,
        @Header("Api-Key") apiKey: String?,
        @Header("Content-Type") contentType: String = "application/json",
        @Body body: OsDownloadRequest,
    ): Response<OsDownloadResponse>
}

@JsonClass(generateAdapter = true)
data class OsSearchResponse(
    @Json(name = "total_pages") val totalPages: Int? = null,
    @Json(name = "total_count") val totalCount: Int? = null,
    @Json(name = "page") val page: Int? = null,
    @Json(name = "data") val data: List<OsSubtitleItem>? = null,
)

@JsonClass(generateAdapter = true)
data class OsSubtitleItem(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: OsSubtitleAttributes? = null,
)

@JsonClass(generateAdapter = true)
data class OsSubtitleAttributes(
    @Json(name = "subtitle_id") val subtitleId: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "release") val release: String? = null,
    @Json(name = "download_count") val downloadCount: Int? = null,
    @Json(name = "ratings") val ratings: Double? = null,
    @Json(name = "from_trusted") val fromTrusted: Boolean? = null,
    @Json(name = "ai_translated") val aiTranslated: Boolean? = null,
    @Json(name = "machine_translated") val machineTranslated: Boolean? = null,
    @Json(name = "hd") val hd: Boolean? = null,
    @Json(name = "fps") val fps: Double? = null,
    @Json(name = "files") val files: List<OsSubtitleFile>? = null,
)

@JsonClass(generateAdapter = true)
data class OsSubtitleFile(
    @Json(name = "file_id") val fileId: Long? = null,
    @Json(name = "cd_number") val cdNumber: Int? = null,
    @Json(name = "file_name") val fileName: String? = null,
)

@JsonClass(generateAdapter = true)
data class OsDownloadRequest(
    @Json(name = "file_id") val fileId: Long,
)

@JsonClass(generateAdapter = true)
data class OsDownloadResponse(
    @Json(name = "link") val link: String? = null,
    @Json(name = "file_name") val fileName: String? = null,
    @Json(name = "requests") val requests: Int? = null,
    @Json(name = "remaining") val remaining: Int? = null,
    @Json(name = "reset_time") val resetTime: String? = null,
    @Json(name = "message") val message: String? = null,
)
