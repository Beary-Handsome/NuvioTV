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

        // Collect playable streams into two buckets:
        //  - debridStreams: debrid / torrent streams, subject to the user's
        //    quality/HDR/codec/size preferences.
        //  - directStreams: direct-URL sources (EasyNews / Usenet). These are
        //    the reliable fallback for obscure content, so they ALWAYS show
        //    if present — they bypass the preference filters entirely and are
        //    listed right alongside the cached debrid streams.
        val debridStreams = mutableListOf<Stream>()
        val directStreams = mutableListOf<Stream>()

        for (group in groups) {
            for (stream in group.streams) {
                if (stream.isInactiveResolverStream(settings)) continue

                when {
                    // Direct debrid stream (clientResolve with isCached=true)
                    stream.isDirectDebrid() -> debridStreams.add(stream)

                    // Cached torrent confirmed on a provider — clean up name
                    stream.needsLocalDebridResolve() &&
                        stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED ->
                        debridStreams.add(cleanupCachedTorrentName(stream))

                    // Direct playable URL that is NOT a debrid/torrent stream
                    // (EasyNews, etc.) — always shown, never filtered.
                    !stream.getStreamUrl().isNullOrBlank() -> directStreams.add(stream)

                    // Uncached or still checking — show all torrent streams
                    // Cached will sort first, uncached are still playable via debrid
                    stream.needsLocalDebridResolve() ->
                        debridStreams.add(cleanupCachedTorrentName(stream))
                }
            }
        }

        if (debridStreams.isEmpty() && directStreams.isEmpty()) return groups

        // Format all managed debrid streams
        val formatted = debridStreams.map { stream ->
            if (stream.isManagedDebridStream()) {
                formatter.format(stream, settings)
            } else {
                stream
            }
        }

        // Apply user's stream preferences (HDR/DV exclusion, minimum quality,
        // codec filter, etc.) to debrid/torrent streams only.
        val filtered = DirectDebridStreamFilter.applyPreferences(formatted, settings)

        // Order: cached debrid first, then EasyNews/direct (always present),
        // then uncached debrid. EasyNews sits alongside the cached streams so
        // it's the visible fallback whenever nothing playable is cached.
        val cachedDebrid = filtered.filter {
            it.debridCacheStatus?.state == StreamDebridCacheState.CACHED
        }
        val uncachedDebrid = filtered.filter {
            it.debridCacheStatus?.state != StreamDebridCacheState.CACHED
        }
        val sorted = cachedDebrid + directStreams + uncachedDebrid

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
