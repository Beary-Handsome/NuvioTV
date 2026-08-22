package com.nuvio.tv.domain.model

import java.util.Locale

data class CanonicalMediaIdentity(
    val type: String,
    val contentId: String,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null
) {
    val contentKey: String = "$type|$contentId"
    val videoKey: String = listOf(
        contentKey,
        videoId,
        season?.toString().orEmpty(),
        episode?.toString().orEmpty()
    ).joinToString("|")

    companion object {
        fun create(
            type: String,
            contentId: String?,
            videoId: String,
            season: Int? = null,
            episode: Int? = null
        ): CanonicalMediaIdentity {
            val normalizedType = when (type.trim().lowercase(Locale.US)) {
                "tv", "show", "series" -> "series"
                else -> "movie"
            }
            val normalizedVideoId = normalizeId(videoId)
            val normalizedContentId = normalizeId(contentId)
                .ifBlank { deriveContentId(normalizedVideoId, normalizedType) }
            val parsedCoordinates = parseSeasonEpisode(normalizedVideoId)
            return CanonicalMediaIdentity(
                type = normalizedType,
                contentId = normalizedContentId,
                videoId = normalizedVideoId,
                season = season ?: parsedCoordinates?.first,
                episode = episode ?: parsedCoordinates?.second
            )
        }

        private fun normalizeId(value: String?): String {
            val trimmed = value.orEmpty().trim()
            if (trimmed.isBlank()) return ""
            val parts = trimmed.split(':').map { it.trim() }
            val prefix = parts.first().lowercase(Locale.US)
            return if (parts.size == 1) {
                if (prefix.startsWith("tt") && prefix.drop(2).all(Char::isDigit)) prefix else trimmed
            } else {
                buildList {
                    add(prefix)
                    addAll(parts.drop(1))
                }.joinToString(":")
            }
        }

        private fun parseSeasonEpisode(videoId: String): Pair<Int, Int>? {
            val parts = videoId.split(':')
            if (parts.size < 3) return null
            val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
            val episode = parts.last().toIntOrNull() ?: return null
            return season to episode
        }

        private fun deriveContentId(videoId: String, type: String): String {
            val parts = videoId.split(':')
            return if (type == "series" && parts.size >= 3 &&
                parts[parts.lastIndex - 1].toIntOrNull() != null && parts.last().toIntOrNull() != null
            ) {
                parts.dropLast(2).joinToString(":")
            } else {
                videoId
            }
        }
    }
}
