package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RivenRecoverRequestDto(
    @Json(name = "imdb_id") val imdbId: String,
    @Json(name = "provider_download_id") val providerDownloadId: String? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "episode") val episode: Int? = null
)

@JsonClass(generateAdapter = true)
data class RivenRecoverResponseDto(
    @Json(name = "ok") val ok: Boolean = false,
    @Json(name = "url") val url: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "providerDownloadId") val providerDownloadId: String? = null,
    @Json(name = "reason") val reason: String? = null
)
