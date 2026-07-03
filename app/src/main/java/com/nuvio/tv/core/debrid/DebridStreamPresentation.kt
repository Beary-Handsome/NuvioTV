package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridStreamPresentation @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val formatter: DebridStreamFormatter
) {
    suspend fun apply(groups: List<AddonStreams>): List<AddonStreams> {
        return apply(groups, dataStore.settings.first())
    }

    fun apply(groups: List<AddonStreams>, settings: DebridSettings): List<AddonStreams> {
        if (!settings.canResolvePlayableLinks) return groups

        // Collect ALL playable streams from ALL addons into one unified list.
        // Drop raw uncached P2P torrents — only keep debrid-resolvable streams.
        val allPlayable = mutableListOf<Stream>()

        for (group in groups) {
            for (stream in group.streams) {
                if (stream.isInactiveResolverStream(settings)) continue

                when {
                    // Direct debrid stream (clientResolve with isCached=true)
                    stream.isDirectDebrid() -> allPlayable.add(stream)

                    // Cached torrent confirmed on a provider — clean up name
                    stream.needsLocalDebridResolve() &&
                        stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED ->
                        allPlayable.add(cleanupCachedTorrentName(stream))

                    // Stream with a playable URL (EasyNews, etc.)
                    !stream.getStreamUrl().isNullOrBlank() -> allPlayable.add(stream)

                    // Uncached or still checking — show all torrent streams
                    // Cached will sort first, uncached are still playable via debrid
                    stream.needsLocalDebridResolve() ->
                        allPlayable.add(cleanupCachedTorrentName(stream))
                }
            }
        }

        if (allPlayable.isEmpty()) return groups

        // Format all managed debrid streams
        val formatted = allPlayable.map { stream ->
            if (stream.isManagedDebridStream()) {
                formatter.format(stream, settings)
            } else {
                stream
            }
        }

        // Apply user's stream preferences (HDR/DV exclusion, minimum quality,
        // codec filter, etc.).  Streams without quality metadata pass through
        // safely — matchesFilters only excludes when an excluded tag is
        // positively detected, not when metadata is absent.
        val filtered = DirectDebridStreamFilter.applyPreferences(formatted, settings)

        // Stable-sort so cached streams appear first while preserving the
        // quality-based ordering from applyPreferences.
        val sorted = filtered.sortedByDescending { stream ->
            stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED
        }

        return listOf(
            AddonStreams(
                addonName = "Streams",
                addonLogo = null,
                streams = sorted
            )
        )
    }

    /**
     * Clean up cached torrent names — strip raw addon labels like "[TORRENT🧲]"
     * and replace with the debrid provider name.
     */
    private fun cleanupCachedTorrentName(stream: Stream): Stream {
        val filename = stream.behaviorHints?.filename ?: stream.title ?: stream.name ?: ""
        val quality = extractQuality(filename)
        val cleanName = if (quality.isNotBlank()) {
            "Debrid · $quality"
        } else {
            "Debrid Stream"
        }

        return stream.copy(
            name = cleanName,
            description = filename.takeIf { it.isNotBlank() },
            addonName = "Streams"
        )
    }

    private fun extractQuality(filename: String): String {
        val fn = filename.lowercase()
        val parts = mutableListOf<String>()
        for (q in listOf("2160p", "1080p", "720p", "480p")) {
            if (q in fn) { parts.add(q); break }
        }
        for (c in listOf("HEVC", "x265", "x264", "AV1", "AVC", "H.264", "H.265")) {
            if (c.lowercase() in fn) { parts.add(c); break }
        }
        for (a in listOf("DTS-HD MA", "TrueHD", "Atmos", "DTS", "DDP5.1", "DDP2.0", "AAC", "EAC3")) {
            if (a.lowercase() in fn) { parts.add(a); break }
        }
        for (q in listOf("REMUX", "BluRay", "WEB-DL", "WEBRip", "BDRip", "HDRip")) {
            if (q.lowercase() in fn) { parts.add(q); break }
        }
        return parts.joinToString(" · ")
    }

    private fun Stream.isManagedDebridStream(): Boolean {
        val status = debridCacheStatus
        return isDirectDebrid() || (
            needsLocalDebridResolve() &&
                status != null &&
                DebridProviders.byId(status.providerId)?.supports(DebridProviderCapability.LocalTorrentCacheCheck) == true &&
                status.state != StreamDebridCacheState.CHECKING
            )
    }

    private fun Stream.isInactiveResolverStream(settings: DebridSettings): Boolean {
        val streamProviderId = DebridProviders.byId(clientResolve?.service)?.id ?: return false
        if (!isDirectDebrid()) return false
        return settings.apiKeyFor(streamProviderId).isBlank()
    }
}
