package com.nuvio.tv.core.debrid

import android.util.Base64
import android.util.Log
import com.nuvio.tv.data.remote.api.EasynewsApi
import com.nuvio.tv.data.remote.dto.EasynewsSearchResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EasyNews resolver — keyword search-based, NOT infohash-based.
 *
 * Unlike torrent debrid providers, EN uses title-based keyword search to find
 * content on Usenet, then returns direct download URLs with basic auth.
 *
 * Flow:
 *  1. Build search query from title + S/E
 *  2. Call EN search API with basic auth
 *  3. Parse results, filter (samples, AD, foreign)
 *  4. Build direct download URL from content hash
 *  5. Return URL with auth header for the player
 *
 * The download URL is permanent (content-hash-addressed) — no minting
 * or expiry like torrent debrid providers.
 */
@Singleton
class EasynewsDirectDebridResolver @Inject constructor(
    private val easynewsApi: EasynewsApi
) {
    companion object {
        private const val TAG = "EasynewsResolver"
        private val FOREIGN_RE = Regex(
            "\\b(german|french|italian|spanish|portuguese|polish|russian|dutch|" +
                "swedish|norwegian|finnish|hungarian|czech|turkish|hindi|tamil|telugu|" +
                "korean|japanese|chinese|thai|vietnamese|indonesian|arabic|hebrew|" +
                "dubbed|dublado|vff|vfq|vostfr|truefrench)\\b",
            RegexOption.IGNORE_CASE
        )
        private val TITLE_CLEANUP_RE = Regex("[':!?,;\\[\\](){}\"']")
    }

    /**
     * Search EasyNews for content matching the given title + episode info.
     *
     * @param username EN username
     * @param password EN password
     * @param title Show/movie title
     * @param season Season number (null for movies)
     * @param episode Episode number (null for movies)
     * @param year Release year (optional, helps disambiguation)
     * @param limit Max results to return
     * @return List of search results with download URLs, sorted by quality
     */
    suspend fun search(
        username: String,
        password: String,
        title: String,
        season: Int? = null,
        episode: Int? = null,
        year: Int? = null,
        limit: Int = 5
    ): List<EasynewsSearchResult> {
        try {
            val auth = "Basic " + Base64.encodeToString(
                "$username:$password".toByteArray(), Base64.NO_WRAP
            )
            val query = buildQuery(title, season, episode, year)
            val titleRegex = buildTitleRegex(title)

            val response = easynewsApi.search(auth = auth, query = query)
            if (!response.isSuccessful) {
                Log.w(TAG, "Search failed: ${response.code()}")
                return emptyList()
            }

            val rawData = response.body()?.data ?: return emptyList()
            return rawData
                .mapNotNull { EasynewsSearchResult.fromRawMap(it) }
                .filter { result ->
                    // Title must appear at start of filename
                    titleRegex.containsMatchIn(result.filename.lowercase()) &&
                        // Season/episode must match if specified
                        matchesEpisode(result.filename, season, episode) &&
                        // No foreign dubs
                        !FOREIGN_RE.containsMatchIn(result.filename)
                }
                .sortedByDescending { scoreResult(it) }
                .take(limit)

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Search failed for '$title': ${e.message}")
            return emptyList()
        }
    }

    /**
     * Resolve a specific EasyNews content hash to a playable URL.
     * Returns the download URL + the auth header the player needs.
     */
    fun buildStreamUrl(
        username: String,
        password: String,
        hash: String,
        filename: String
    ): Pair<String, String> {
        val auth = "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(), Base64.NO_WRAP
        )
        val prefix = hash.take(2)
        val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8")
            .replace("+", "%20")
        val url = "https://members.easynews.com/dl/$prefix/$hash/$encodedFilename"
        return url to auth
    }

    private fun buildQuery(title: String, season: Int?, episode: Int?, year: Int?): String {
        // Strip apostrophes + punctuation (EN search rejects them)
        val cleaned = title
            .replace("'", "").replace("'", "")
            .replace(TITLE_CLEANUP_RE, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val parts = mutableListOf(cleaned)
        if (season != null && episode != null) {
            parts.add("S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}")
        } else if (year != null) {
            parts.add(year.toString())
        }
        return parts.joinToString(" ")
    }

    private fun buildTitleRegex(title: String): Regex {
        val cleaned = title
            .replace("'", "").replace("'", "")
            .replace(TITLE_CLEANUP_RE, " ")
            .trim()
        val pattern = Regex.escape(cleaned.lowercase()).replace("\\ ", "[\\s._\\-]+")
        return Regex("^(\\[[^]]+][\\s._\\-]*|\\([^)]+\\)[\\s._\\-]*)?$pattern([\\s._\\-]|\$)", RegexOption.IGNORE_CASE)
    }

    private fun matchesEpisode(filename: String, season: Int?, episode: Int?): Boolean {
        if (season == null || episode == null) return true
        val pattern = Regex(
            "s${season.toString().padStart(2, '0')}e${episode.toString().padStart(2, '0')}\\b|" +
                "${season}x${episode.toString().padStart(2, '0')}\\b",
            RegexOption.IGNORE_CASE
        )
        return pattern.containsMatchIn(filename)
    }

    private fun scoreResult(result: EasynewsSearchResult): Int {
        var score = 0
        val fn = result.filename.lowercase()
        if ("2160p" in fn || "4k" in fn) score += 50
        else if ("1080p" in fn) score += 30
        else if ("720p" in fn) score += 10
        if ("x265" in fn || "hevc" in fn) score += 5
        if ("web-dl" in fn || "webdl" in fn) score += 3
        else if ("bluray" in fn || "remux" in fn) score += 4
        score += (result.sizeBytes / (200 * 1024 * 1024)).toInt() // +1 per 200MB
        return score
    }
}
