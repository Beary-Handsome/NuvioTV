package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RivenAvailabilityRequestDto(
    @Json(name = "ids") val ids: List<String>
)

@JsonClass(generateAdapter = true)
data class RivenAvailabilityResponseDto(
    @Json(name = "available") val available: Map<String, Boolean> = emptyMap()
)
