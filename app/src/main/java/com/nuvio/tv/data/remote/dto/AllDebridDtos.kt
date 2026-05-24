package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * AllDebrid API response envelope. All AD responses wrap data in:
 * { "status": "success", "data": { ... } }
 * Error: { "status": "error", "error": { "code": "...", "message": "..." } }
 */
@JsonClass(generateAdapter = true)
data class AllDebridEnvelopeDto<T>(
    @Json(name = "status") val status: String?,
    @Json(name = "data") val data: T?,
    @Json(name = "error") val error: AllDebridErrorDto?
)

@JsonClass(generateAdapter = true)
data class AllDebridErrorDto(
    @Json(name = "code") val code: String?,
    @Json(name = "message") val message: String?
)

// /magnet/instant response
@JsonClass(generateAdapter = true)
data class AllDebridInstantDto(
    @Json(name = "magnets") val magnets: List<AllDebridInstantMagnetDto>?
)

@JsonClass(generateAdapter = true)
data class AllDebridInstantMagnetDto(
    @Json(name = "magnet") val magnet: String?,
    @Json(name = "hash") val hash: String?,
    @Json(name = "instant") val instant: Boolean?
)

// /magnet/upload response
@JsonClass(generateAdapter = true)
data class AllDebridMagnetUploadDto(
    @Json(name = "magnets") val magnets: List<AllDebridMagnetUploadItemDto>?
)

@JsonClass(generateAdapter = true)
data class AllDebridMagnetUploadItemDto(
    @Json(name = "id") val id: Long?,
    @Json(name = "hash") val hash: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "ready") val ready: Boolean?
)

// /magnet/status response
@JsonClass(generateAdapter = true)
data class AllDebridMagnetStatusDto(
    @Json(name = "magnets") val magnets: AllDebridMagnetInfoDto?
)

@JsonClass(generateAdapter = true)
data class AllDebridMagnetInfoDto(
    @Json(name = "id") val id: Long?,
    @Json(name = "filename") val filename: String?,
    @Json(name = "size") val size: Long?,
    @Json(name = "hash") val hash: String?,
    @Json(name = "status") val status: String?,
    @Json(name = "statusCode") val statusCode: Int?,
    @Json(name = "links") val links: List<AllDebridLinkDto>?,
    @Json(name = "files") val files: List<AllDebridFileDto>?
)

@JsonClass(generateAdapter = true)
data class AllDebridLinkDto(
    @Json(name = "link") val link: String?,
    @Json(name = "filename") val filename: String?,
    @Json(name = "size") val size: Long?
)

@JsonClass(generateAdapter = true)
data class AllDebridFileDto(
    @Json(name = "n") val name: String?,
    @Json(name = "s") val size: Long?,
    @Json(name = "l") val link: String?,
    @Json(name = "e") val entries: List<AllDebridFileDto>?  // nested directory entries
)

// /link/unlock response
@JsonClass(generateAdapter = true)
data class AllDebridUnlockDto(
    @Json(name = "link") val link: String?,
    @Json(name = "host") val host: String?,
    @Json(name = "filename") val filename: String?,
    @Json(name = "filesize") val filesize: Long?,
    @Json(name = "streaming") val streaming: List<AllDebridStreamDto>?,
    @Json(name = "download") val download: String?
)

@JsonClass(generateAdapter = true)
data class AllDebridStreamDto(
    @Json(name = "quality") val quality: String?,
    @Json(name = "link") val link: String?
)
