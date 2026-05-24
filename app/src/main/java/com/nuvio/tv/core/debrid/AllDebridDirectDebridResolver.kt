package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridFileDto
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

            // 2. Get status
            val statusResponse = allDebridApi.magnetStatus(
                apiKey = apiKey,
                magnetId = magnetId.toString()
            )
            if (!statusResponse.isSuccessful) {
                Log.w(TAG, "Status check failed: ${statusResponse.code()}")
                return null
            }
            val magnetInfo = statusResponse.body()?.data?.magnets
            if (magnetInfo == null || magnetInfo.statusCode != 4) {
                Log.w(TAG, "Torrent not ready: status=${magnetInfo?.status} code=${magnetInfo?.statusCode}")
                return null
            }

            // 3. Find the right file link from the nested files tree
            val allLinks = flattenFileLinks(magnetInfo.files ?: emptyList())
            if (allLinks.isEmpty()) {
                Log.w(TAG, "No file links found")
                return null
            }

            // Pick file by index or largest video file
            val targetLink = if (fileIdx != null && fileIdx < allLinks.size) {
                allLinks[fileIdx]
            } else {
                allLinks.maxByOrNull { it.second }  // largest file
            }

            if (targetLink == null) {
                Log.w(TAG, "No suitable file found")
                return null
            }

            // 4. Unlock the link to get the streamable URL
            val unlockResponse = allDebridApi.unlockLink(
                apiKey = apiKey,
                link = targetLink.first
            )
            if (!unlockResponse.isSuccessful) {
                Log.w(TAG, "Unlock failed: ${unlockResponse.code()}")
                return null
            }

            val downloadUrl = unlockResponse.body()?.data?.link
                ?: unlockResponse.body()?.data?.download

            if (downloadUrl.isNullOrBlank()) {
                Log.w(TAG, "No download URL from unlock")
                return null
            }

            Log.d(TAG, "Resolved $infoHash → $downloadUrl")
            return downloadUrl

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
