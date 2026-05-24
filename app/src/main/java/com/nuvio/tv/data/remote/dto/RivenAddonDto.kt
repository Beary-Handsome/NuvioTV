package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RivenLibraryStatusDto(
    @Json(name = "inRiven") val inRiven: Boolean = false,
    @Json(name = "state") val state: String = "",
    @Json(name = "ready") val ready: Boolean = false
)

@JsonClass(generateAdapter = true)
data class RivenRequestResultDto(
    @Json(name = "ok") val ok: Boolean = false,
    @Json(name = "message") val message: String = ""
)
