package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridFileDto
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AllDebrid resolver — resolves a torrent infohash to a streamable download URL.
 *
 * Flow:
 *  1. Build magnet URI from infohash
 *  2. POST /magnet/upload to add the torrent
 *  3. GET /magnet/status to check if ready (statusCode == 4)
 *  4. Extract the file link from the nested files tree
 *  5. POST /link/unlock to get the final streamable URL
 *
 * AllDebrid supports instant availability — if the torrent is already cached,
 * step 2 returns ready=true immediately and the status will be 4.
 */
@Singleton
class AllDebridDirectDebridResolver @Inject constructor(
    private val allDebridApi: AllDebridApi
) {
    companion object {
        private const val TAG = "AllDebridResolver"
    }

    /**
     * Resolve an infohash to a streamable URL.
     *
     * @param apiKey User's AllDebrid API key
     * @param infoHash 40-char torrent infohash
     * @param fileIdx Optional file index within the torrent (for multi-file packs)
     * @return Streamable HTTPS URL, or null on failure
     */
    suspend fun resolve(apiKey: String, infoHash: String, fileIdx: Int? = null): String? {
        try {
            val magnet = "magnet:?xt=urn:btih:$infoHash"

            // 1. Upload magnet
            val uploadResponse = allDebridApi.uploadMagnet(
                apiKey = apiKey,
                magnets = listOf(magnet)
            )
            if (!uploadResponse.isSuccessful) {
                Log.w(TAG, "Upload failed: ${uploadResponse.code()}")
                return null
            }
            val uploadData = uploadResponse.body()?.data
            val magnetItem = uploadData?.magnets?.firstOrNull()
            val magnetId = magnetItem?.id ?: run {
                Log.w(TAG, "No magnet ID returned")
                return null
            }

            var resolved = false
            try {
                return withTimeout(30_000L) {
                    // 2. Poll for status — AD may need time to process
                    var magnetInfo: com.nuvio.tv.data.remote.dto.AllDebridMagnetInfoDto? = null
                    for (attempt in 1..15) {
                        val statusResponse = allDebridApi.magnetStatus(
                            apiKey = apiKey,
                            magnetId = magnetId.toString()
                        )
                        if (!statusResponse.isSuccessful) {
                            Log.w(TAG, "Status check failed: ${statusResponse.code()}")
                            return@withTimeout null
                        }
                        magnetInfo = statusResponse.body()?.data?.magnets
                        if (magnetInfo?.statusCode == 4) break
                        // Give up on terminal errors
                        val code = magnetInfo?.statusCode ?: -1
                        if (code in listOf(5, 6, 7, 8, 9, 10, 11)) {
                            Log.w(TAG, "Torrent failed: status=${magnetInfo?.status} code=$code")
                            return@withTimeout null
                        }
                        if (attempt < 15) {
                            kotlinx.coroutines.delay(when {
                                attempt <= 5 -> 1000L
                                else -> 2000L
                            })
                        }
                    }
                    if (magnetInfo == null || magnetInfo!!.statusCode != 4) {
                        Log.w(TAG, "Torrent not ready after polling: status=${magnetInfo?.status}")
                        return@withTimeout null
                    }

                    // 3. Find the right file link from the nested files tree
                    var allLinks = flattenFileLinks(magnetInfo.files ?: emptyList())

                    // Some AD torrents have links at top level, not nested in files tree
                    if (allLinks.isEmpty()) {
                        allLinks = (magnetInfo.links ?: emptyList()).mapNotNull { link ->
                            val l = link.link ?: return@mapNotNull null
                            val name = link.filename?.lowercase() ?: ""
                            val isVideo = name.endsWith(".mkv") || name.endsWith(".mp4") || name.endsWith(".avi") ||
                                name.endsWith(".m4v") || name.endsWith(".ts") || name.endsWith(".wmv")
                            if (isVideo || name.isBlank()) l to (link.size ?: 0L) else null
                        }
                    }

                    if (allLinks.isEmpty()) {
                        Log.w(TAG, "No file links found")
                        return@withTimeout null
                    }

                    // Pick file: try matching by filename from the full files list first,
                    // then fall back to largest video file. Using fileIdx directly is unreliable
                    // because allLinks is filtered to video-only files.
                    val targetLink = if (fileIdx != null) {
                        // Try to find the original filename at fileIdx from the unfiltered tree
                        val originalName = findFileNameByIndex(magnetInfo.files ?: emptyList(), fileIdx)
                        if (originalName != null) {
                            allLinks.firstOrNull { pair ->
                                pair.first.lowercase().contains(originalName.lowercase())
                            } ?: allLinks.maxByOrNull { it.second }
                        } else {
                            // fileIdx doesn't resolve to a name; pick largest
                            allLinks.maxByOrNull { it.second }
                        }
                    } else {
                        allLinks.maxByOrNull { it.second }  // largest file
                    }

                    if (targetLink == null) {
                        Log.w(TAG, "No suitable file found")
                        return@withTimeout null
                    }

                    // 4. Unlock the link to get the streamable URL
                    val unlockResponse = allDebridApi.unlockLink(
                        apiKey = apiKey,
                        link = targetLink.first
                    )
                    if (!unlockResponse.isSuccessful) {
                        Log.w(TAG, "Unlock failed: ${unlockResponse.code()}")
                        return@withTimeout null
                    }

                    val downloadUrl = unlockResponse.body()?.data?.link
                        ?: unlockResponse.body()?.data?.download

                    if (downloadUrl.isNullOrBlank()) {
                        Log.w(TAG, "No download URL from unlock")
                        return@withTimeout null
                    }

                    Log.d(TAG, "Resolved $infoHash → $downloadUrl")
                    resolved = true
                    downloadUrl
                }
            } finally {
                if (!resolved) {
                    runCatching {
                        allDebridApi.deleteMagnet(apiKey = apiKey, magnetId = magnetId.toString())
                    }.onFailure { e ->
                        Log.w(TAG, "Failed to delete orphan magnet $magnetId: ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Resolution failed for $infoHash: ${e.message}")
            return null
        }
    }

    /**
     * Check if hashes are instantly available on AllDebrid.
     */
    suspend fun checkCached(apiKey: String, hashes: List<String>): Map<String, Boolean> {
        return try {
            val response = allDebridApi.checkInstant(
                apiKey = apiKey,
                magnets = hashes
            )
            if (!response.isSuccessful) return emptyMap()
            val magnets = response.body()?.data?.magnets ?: return emptyMap()
            magnets.associate { (it.hash?.lowercase() ?: "") to (it.instant == true) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Cache check failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Find the filename at a given flat index in AD's nested file tree.
     * The index corresponds to the torrent's file numbering (all files, not just video).
     */
    private fun findFileNameByIndex(files: List<AllDebridFileDto>, targetIdx: Int): String? {
        var currentIdx = 0
        fun walk(nodes: List<AllDebridFileDto>): String? {
            for (node in nodes) {
                if (node.link != null || (node.entries == null && node.name != null)) {
                    if (currentIdx == targetIdx) return node.name
                    currentIdx++
                }
                node.entries?.let { children ->
                    val found = walk(children)
                    if (found != null) return found
                }
            }
            return null
        }
        return walk(files)
    }

    /**
     * Flatten AD's nested file tree into a list of (link, size) pairs.
     * AD nests files as: files[].e[].{n, s, l} where e[] can also contain
     * nested directories.
     */
    private fun flattenFileLinks(files: List<AllDebridFileDto>): List<Pair<String, Long>> {
        val result = mutableListOf<Pair<String, Long>>()
        for (f in files) {
            if (!f.link.isNullOrBlank()) {
                val isVideo = f.name?.lowercase()?.let { n ->
                    n.endsWith(".mkv") || n.endsWith(".mp4") || n.endsWith(".avi") ||
                        n.endsWith(".m4v") || n.endsWith(".ts") || n.endsWith(".wmv")
                } == true
                if (isVideo) {
                    result.add(f.link to (f.size ?: 0))
                }
            }
            // Recurse into subdirectories
            f.entries?.let { result.addAll(flattenFileLinks(it)) }
        }
        return result
    }
}
