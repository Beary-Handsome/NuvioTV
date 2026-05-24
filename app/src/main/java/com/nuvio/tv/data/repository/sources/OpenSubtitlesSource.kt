package com.nuvio.tv.data.repository.sources

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.remote.api.OpenSubtitlesApi
import com.nuvio.tv.data.remote.api.OsSubtitleItem
import com.nuvio.tv.domain.model.BuiltinOpenSubtitlesAddon
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.parseTmdbContentId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subtitle source backed by direct OpenSubtitles.com REST API calls.
 *
 * Search:
 *   GET /api/v1/subtitles?imdb_id=tt123&languages=en[&season_number=N&episode_number=N]
 *
 * The API returns a list of subtitles with `file_id` per file. The actual
 * subtitle bytes are minted on demand via POST /api/v1/download, but that
 * endpoint counts against a per-IP daily quota and the returned link
 * expires after a few hours.
 *
 * To avoid burning a quota credit on every subtitle Nuvio displays, this
 * source returns a `subscene://` sentinel URL carrying the file_id. The
 * sentinel is resolved at playback time by [resolveDownloadUrl], which
 * happens only when the user actually selects that subtitle to display.
 */
@Singleton
class OpenSubtitlesSource @Inject constructor(
    private val api: OpenSubtitlesApi,
) {
    companion object {
        private const val TAG = "OpenSubtitlesSource"
        const val SENTINEL_PREFIX = "opensubtitles://"

        // OS requires a branded User-Agent. Format per docs: `<App> v<Version>`.
        // Includes the app name so admins can see Nuvio traffic in OS metrics.
        private val USER_AGENT: String =
            "Nuvio TV v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
    }

    private val apiKey: String? = BuildConfig.OPENSUBTITLES_API_KEY.takeIf { it.isNotBlank() }

    suspend fun search(
        type: String,
        id: String,
        videoId: String?,
        movieHash: String?,
        languages: List<String>,
    ): List<Subtitle> {
        val normalizedType = if (type.equals("series", true) || type.equals("tv", true)) "series" else "movie"
        val (imdbId, tmdbId, season, episode) = decodeIds(id, videoId, normalizedType)
        val languageQuery = languages.takeIf { it.isNotEmpty() }?.joinToString(",")

        val resp = safeApiCall {
            api.searchSubtitles(
                userAgent = USER_AGENT,
                apiKey = apiKey,
                imdbId = imdbId,
                tmdbId = tmdbId,
                season = season,
                episode = episode,
                languages = languageQuery,
                movieHash = movieHash,
                type = if (normalizedType == "series") "episode" else "movie",
            )
        }

        return when (resp) {
            is NetworkResult.Success -> {
                val items = resp.data.data.orEmpty()
                Log.d(TAG, "search ok type=$normalizedType id=$id imdb=$imdbId tmdb=$tmdbId season=$season episode=$episode results=${items.size}")
                items.mapNotNull { it.toDomain() }
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "search failed code=${resp.code} message=${resp.message}")
                emptyList()
            }
            NetworkResult.Loading -> emptyList()
        }
    }

    /**
     * Resolve a sentinel URL like `opensubtitles://<fileId>` to the actual
     * downloadable subtitle URL. Burns one download credit per call (OS
     * quotas this per IP). Returns null on failure.
     */
    suspend fun resolveDownloadUrl(sentinelUrl: String): String? {
        val fileId = sentinelUrl.removePrefix(SENTINEL_PREFIX).toLongOrNull() ?: return null
        val resp = safeApiCall {
            api.requestDownload(
                userAgent = USER_AGENT,
                apiKey = apiKey,
                body = com.nuvio.tv.data.remote.api.OsDownloadRequest(fileId = fileId),
            )
        }
        return when (resp) {
            is NetworkResult.Success -> {
                val link = resp.data.link?.takeIf { it.isNotBlank() }
                if (link == null) {
                    Log.w(TAG, "resolve: no link in response (message=${resp.data.message} remaining=${resp.data.remaining})")
                }
                link
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "resolve: download request failed code=${resp.code} message=${resp.message}")
                null
            }
            NetworkResult.Loading -> null
        }
    }

    private data class IdParts(
        val imdbId: String?,
        val tmdbId: String?,
        val season: Int?,
        val episode: Int?,
    )

    /**
     * Decode the (potentially TMDB-prefixed) content id + the optional
     * Stremio-style `videoId` (which carries season/episode for series).
     *
     * Inputs we need to handle:
     *   - id = "tt12345"                          movie / show
     *   - id = "tmdb:movie:123"                   TMDB movie
     *   - id = "tmdb:series:123"                  TMDB show
     *   - videoId = "tt12345:1:3"                 episode (Stremio convention)
     *   - videoId = "tmdb:series:123:1:3"         episode (Nuvio-TMDB convention)
     */
    private fun decodeIds(id: String, videoId: String?, normalizedType: String): IdParts {
        var imdb: String? = null
        var tmdb: String? = null
        var season: Int? = null
        var episode: Int? = null

        // Prefer episode info from videoId when present (series flow).
        val sourceId = videoId?.takeIf { normalizedType == "series" } ?: id

        if (sourceId.startsWith("tmdb:")) {
            // Parse the bare-movie/series form first. For episodes, split off s/e.
            val parts = sourceId.split(":")
            // tmdb:movie:NNN or tmdb:series:NNN[:S:E]
            if (parts.size >= 3) {
                parseTmdbContentId("${parts[0]}:${parts[1]}:${parts[2]}")?.let {
                    tmdb = it.tmdbId.toString()
                }
                if (parts.size == 5) {
                    season = parts[3].toIntOrNull()
                    episode = parts[4].toIntOrNull()
                }
            }
        } else if (sourceId.startsWith("tt")) {
            // Standard Stremio convention: tt1234567[:S:E]
            val parts = sourceId.split(":")
            imdb = parts[0].removePrefix("tt").takeIf { it.isNotEmpty() }
            if (parts.size == 3) {
                season = parts[1].toIntOrNull()
                episode = parts[2].toIntOrNull()
            }
        }

        return IdParts(imdbId = imdb, tmdbId = tmdb, season = season, episode = episode)
    }

    private fun OsSubtitleItem.toDomain(): Subtitle? {
        val attrs = attributes ?: return null
        val file = attrs.files?.firstOrNull { it.fileId != null } ?: return null
        val fileId = file.fileId ?: return null
        val lang = attrs.language ?: return null

        return Subtitle(
            id = "opensubtitles_$fileId",
            url = "$SENTINEL_PREFIX$fileId",
            lang = lang,
            addonName = BuiltinOpenSubtitlesAddon.DISPLAY_NAME,
            addonLogo = null,
        )
    }
}
