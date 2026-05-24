package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * EasyNews search response.
 *
 * EN returns a custom JSON envelope where each item in `data` is a map
 * with numeric string keys:
 *   "0"  = content hash (38-42 char hex — used as the file identifier)
 *   "2"  = file extension (e.g. ".mkv")
 *   "4"  = human-readable size (e.g. "939.5 MB")
 *   "10" = filename without extension
 *   "11" = extension (duplicate of "2")
 *
 * The content hash + filename together form the download URL:
 *   https://members.easynews.com/dl/{hash[:2]}/{hash}/{filename}
 */
@JsonClass(generateAdapter = true)
data class EasynewsSearchResponseDto(
    @Json(name = "data") val data: List<Map<String, Any?>>?
)

/**
 * Parsed EasyNews search result — extracted from the raw map.
 */
data class EasynewsSearchResult(
    val hash: String,
    val filename: String,
    val sizeText: String,
    val sizeBytes: Long
) {
    /**
     * Build the direct download URL. Requires basic auth header.
     */
    fun downloadUrl(): String {
        val prefix = hash.take(2)
        val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8")
            .replace("+", "%20")
        return "https://members.easynews.com/dl/$prefix/$hash/$encodedFilename"
    }

    companion object {
        private val VIDEO_EXTS = setOf(".mkv", ".mp4", ".avi", ".m4v", ".ts", ".wmv", ".mov")
        private val SAMPLE_RE = Regex("(^|[\\s._\\-\\[])(?:sample|preview|trailer|teaser)([\\s._\\-\\].]|\$)", RegexOption.IGNORE_CASE)
        private val AD_RE = Regex("(?:with[\\s._]+audio[\\s._]+description|audio[\\s._]+described|with[\\s._\\-]*ad[\\s._\\-])", RegexOption.IGNORE_CASE)

        fun fromRawMap(item: Map<String, Any?>): EasynewsSearchResult? {
            val hash = (item["0"] as? String)?.trim() ?: return null
            val stem = (item["10"] as? String)?.trim() ?: return null
            val ext = (item["11"] as? String ?: item["2"] as? String)?.trim() ?: ""
            if (hash.isBlank() || stem.isBlank()) return null

            val filename = if (ext.isNotBlank()) "$stem$ext" else stem
            // Filter non-video
            if (VIDEO_EXTS.none { filename.lowercase().endsWith(it) }) return null
            // Filter samples + audio description
            if (SAMPLE_RE.containsMatchIn(filename)) return null
            if (AD_RE.containsMatchIn(filename)) return null

            val sizeText = (item["4"] as? String) ?: ""
            val sizeBytes = parseSizeText(sizeText)
            return EasynewsSearchResult(hash, filename, sizeText, sizeBytes)
        }

        private fun parseSizeText(text: String): Long {
            val normalized = text.trim().uppercase()
            val match = Regex("([\\d.]+)\\s*(GB|MB|KB|TB)").find(normalized) ?: return 0
            val value = match.groupValues[1].toDoubleOrNull() ?: return 0
            return when (match.groupValues[2]) {
                "TB" -> (value * 1_099_511_627_776).toLong()
                "GB" -> (value * 1_073_741_824).toLong()
                "MB" -> (value * 1_048_576).toLong()
                "KB" -> (value * 1_024).toLong()
                else -> 0
            }
        }
    }
}
