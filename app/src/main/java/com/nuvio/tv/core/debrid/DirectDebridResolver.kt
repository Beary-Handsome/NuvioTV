package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.core.streams.StreamDiagnosticStage
import com.nuvio.tv.core.streams.StreamDiagnostics
import com.nuvio.tv.core.streams.StreamProviderDiagnostic
import com.nuvio.tv.core.network.StreamUrlFreshnessValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val torboxResolver: TorboxDirectDebridResolver,
    private val realDebridResolver: RealDebridDirectDebridResolver,
    private val premiumizeResolver: PremiumizeDirectDebridResolver,
    private val allDebridResolver: AllDebridDirectDebridResolver,
    private val easynewsResolver: EasynewsDirectDebridResolver,
    private val localDebridService: LocalDebridService,
    private val diagnostics: StreamDiagnostics,
    private val streamUrlFreshnessValidator: StreamUrlFreshnessValidator
) {
    suspend fun searchEasyNewsStreams(
        title: String,
        season: Int?,
        episode: Int?,
        year: Int?
    ): List<Stream> {
        val settings = dataStore.settings.first()
        val username = settings.easynewsUsername.trim()
        val password = settings.easynewsPassword.trim()
        if (username.isBlank() || password.isBlank() || title.isBlank()) return emptyList()

        return easynewsResolver.search(
            username = username,
            password = password,
            title = title,
            season = season,
            episode = episode,
            year = year,
            limit = 12
        ).map { result ->
            val (url, authorization) = easynewsResolver.buildStreamUrl(
                username = username,
                password = password,
                hash = result.hash,
                filename = result.filename
            )
            Stream(
                name = "[EasyNews] ${result.sizeText}".trim(),
                title = result.filename,
                description = result.filename,
                url = url,
                ytId = null,
                infoHash = null,
                fileIdx = null,
                externalUrl = null,
                behaviorHints = StreamBehaviorHints(
                    notWebReady = false,
                    bingeGroup = "easynews",
                    countryWhitelist = null,
                    proxyHeaders = ProxyHeaders(
                        request = mapOf("Authorization" to authorization),
                        response = emptyMap()
                    ),
                    videoHash = result.hash,
                    videoSize = result.sizeBytes,
                    filename = result.filename
                ),
                addonName = "EasyNews",
                addonLogo = null,
                quality = null
            )
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val resolvedCache = mutableMapOf<String, CachedDirectDebridResolve>()
    private val inFlightResolves = mutableMapOf<String, Deferred<DirectDebridResolveResult>>()

    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return DirectDebridResolveResult.Stale
        }
        val cacheKey = stream.directDebridResolveCacheKey(season, episode)
        if (cacheKey == null) {
            return resolveUncached(stream, season, episode)
        }
        getCachedResult(cacheKey)?.let {
            return it
        }

        var ownsResolve = false
        val newResolve = scope.async(start = CoroutineStart.LAZY) {
            resolveUncached(stream, season, episode)
        }
        val activeResolve = mutex.withLock {
            getCachedResultLocked(cacheKey)?.let { cached ->
                return@withLock null to cached
            }
            val existing = inFlightResolves[cacheKey]
            if (existing != null) {
                existing to null
            } else {
                inFlightResolves[cacheKey] = newResolve
                ownsResolve = true
                newResolve to null
            }
        }
        activeResolve.second?.let {
            newResolve.cancel()
            return it
        }
        val deferred = activeResolve.first ?: return DirectDebridResolveResult.Error
        if (!ownsResolve) newResolve.cancel()
        if (ownsResolve) deferred.start()

        return try {
            val result = deferred.await()
            if (ownsResolve && result is DirectDebridResolveResult.Success) {
                mutex.withLock {
                    resolvedCache[cacheKey] = CachedDirectDebridResolve(
                        result = result,
                        cachedAtMs = System.currentTimeMillis()
                    )
                }
            }
            result
        } finally {
            if (ownsResolve) {
                mutex.withLock {
                    if (inFlightResolves[cacheKey] === deferred) {
                        inFlightResolves.remove(cacheKey)
                    }
                }
            }
        }
    }

    suspend fun cachedPlayableStream(stream: Stream, season: Int?, episode: Int?): Stream? {
        if (!shouldResolveToPlayableStream(stream)) return null
        val cacheKey = stream.directDebridResolveCacheKey(season, episode) ?: return null
        return getCachedResult(cacheKey)?.let { result -> stream.withResolvedDebridUrl(result) }
    }

    /**
     * Drops every resolved link from the in-memory resolve cache. Used when a
     * playback failure signals a resolved debrid URL has gone stale: clearing
     * everything (rather than a single key) guarantees the dead link is dropped
     * for all stream types — including local-torrent resolves whose resolved
     * form no longer carries the identity needed to recompute its cache key.
     * Entries re-resolve lazily on next access.
     */
    suspend fun invalidateAll() {
        mutex.withLock { resolvedCache.clear() }
    }

    suspend fun resolveToPlayableStream(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridPlayableResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return if (!stream.getStreamUrl().isNullOrBlank()) {
                DirectDebridPlayableResult.Success(stream)
            } else {
                DirectDebridPlayableResult.MissingApiKey
            }
        }
        return when (val result = resolve(stream, season, episode)) {
            is DirectDebridResolveResult.Success -> DirectDebridPlayableResult.Success(stream.withResolvedDebridUrl(result))
            DirectDebridResolveResult.MissingApiKey -> DirectDebridPlayableResult.MissingApiKey
            DirectDebridResolveResult.NotCached -> DirectDebridPlayableResult.NotCached
            DirectDebridResolveResult.Stale -> DirectDebridPlayableResult.Stale
            DirectDebridResolveResult.Error -> DirectDebridPlayableResult.Error
        }
    }

    suspend fun shouldResolveToPlayableStream(stream: Stream): Boolean {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return false
        if (stream.needsLocalDebridResolve()) {
            return localTorrentResolveCredential(settings) != null
        }
        if (!stream.isDirectDebrid() || stream.getStreamUrl() != null) return false
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return false
        return settings.apiKeyFor(providerId).isNotBlank()
    }

    private suspend fun getCachedResult(cacheKey: String): DirectDebridResolveResult.Success? {
        val cached = mutex.withLock { getCachedResultLocked(cacheKey) } ?: return null
        if (streamUrlFreshnessValidator.validate(cached.url, emptyMap()).playable) return cached
        mutex.withLock { resolvedCache.remove(cacheKey) }
        return null
    }

    private fun getCachedResultLocked(cacheKey: String): DirectDebridResolveResult.Success? {
        val cached = resolvedCache[cacheKey] ?: return null
        val age = System.currentTimeMillis() - cached.cachedAtMs
        return if (age in 0..DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS) {
            cached.result
        } else {
            resolvedCache.remove(cacheKey)
            null
        }
    }

    private suspend fun resolveUncached(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        if (stream.needsLocalDebridResolve()) {
            return resolveLocalTorrentStream(stream, season, episode)
        }
        return when (DebridProviders.byId(stream.clientResolve?.service)?.id) {
            DebridProviders.TORBOX_ID -> torboxResolver.resolve(stream, season, episode)
            DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(stream, season, episode)
            DebridProviders.REAL_DEBRID_ID -> realDebridResolver.resolve(stream, season, episode)
            DebridProviders.ALLDEBRID_ID -> {
                val settings = dataStore.settings.first()
                resolveViaAllDebrid(
                    settings.allDebridApiKey,
                    stream.getEffectiveInfoHash(),
                    stream.getEffectiveFileIdx(),
                    stream.behaviorHints?.filename,
                    season,
                    episode
                )
            }
            DebridProviders.EASYNEWS_ID -> DirectDebridResolveResult.Error
            else -> DirectDebridResolveResult.Error
        }
    }

    private suspend fun Stream.directDebridResolveCacheKey(season: Int?, episode: Int?): String? {
        if (needsLocalDebridResolve()) {
            val settings = dataStore.settings.first()
            val accounts = localTorrentResolveCredentials(settings, debridCacheStatus?.providerId)
            if (accounts.isEmpty()) return null
            val identity = getEffectiveInfoHash() ?: torrentMagnetUri() ?: behaviorHints?.filename ?: return null
            return listOf(
                accounts.joinToString(",") { "${it.provider.id}:${it.apiKey.stableFingerprint()}" },
                identity.trim().lowercase(),
                getEffectiveFileIdx()?.toString().orEmpty(),
                behaviorHints?.filename.orEmpty().trim().lowercase(),
                season?.toString().orEmpty(),
                episode?.toString().orEmpty()
            ).joinToString("|")
        }
        val resolve = clientResolve ?: return null
        val providerId = DebridProviders.byId(resolve.service)?.id ?: return null
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return null
        val apiKey = settings.apiKeyFor(providerId).trim().takeIf { it.isNotBlank() } ?: return null
        val identity = resolve.infoHash
            ?: resolve.magnetUri
            ?: resolve.torrentName
            ?: resolve.filename
            ?: return null

        return listOf(
            providerId,
            apiKey.stableFingerprint(),
            identity.trim().lowercase(),
            resolve.fileIdx?.toString().orEmpty(),
            (resolve.filename ?: behaviorHints?.filename).orEmpty().trim().lowercase(),
            (season ?: resolve.season)?.toString().orEmpty(),
            (episode ?: resolve.episode)?.toString().orEmpty()
        ).joinToString("|")
    }

    private suspend fun resolveLocalTorrentStream(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val settings = dataStore.settings.first()
        val accounts = localTorrentResolveCredentials(settings, stream.debridCacheStatus?.providerId)
        if (accounts.isEmpty()) return DirectDebridResolveResult.MissingApiKey
        val hash = stream.getEffectiveInfoHash()?.trim()?.lowercase()
        val magnet = DebridMagnetBuilder.fromStream(stream)
            ?: return DirectDebridResolveResult.Stale

        var sawDefinitiveMiss = false
        for (account in accounts) {
            val startedAt = System.currentTimeMillis()
            if (!hash.isNullOrBlank() && account.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck)) {
                when (localDebridService.isCached(account, hash)) {
                    false -> {
                        sawDefinitiveMiss = true
                        diagnostics.record(
                            StreamProviderDiagnostic(account.provider.displayName, StreamDiagnosticStage.CACHE_CHECK,
                                System.currentTimeMillis() - startedAt, outcome = "not_cached")
                        )
                        continue
                    }
                    true, null -> Unit
                }
            }
            val result = resolveLocalTorrentWithAccount(stream, account, magnet, season, episode)
            diagnostics.record(
                StreamProviderDiagnostic(
                    provider = account.provider.displayName,
                    stage = StreamDiagnosticStage.RESOLVE,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    outcome = result::class.simpleName.orEmpty()
                )
            )
            if (result is DirectDebridResolveResult.Success) return result
            if (result == DirectDebridResolveResult.NotCached) sawDefinitiveMiss = true
        }
        return if (sawDefinitiveMiss) DirectDebridResolveResult.NotCached else DirectDebridResolveResult.Stale
    }

    private suspend fun resolveLocalTorrentWithAccount(
        stream: Stream,
        account: DebridServiceCredential,
        magnet: String,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val resolveStream = stream.copy(
            clientResolve = StreamClientResolve(
                type = "torrent",
                infoHash = stream.getEffectiveInfoHash(),
                fileIdx = stream.getEffectiveFileIdx(),
                magnetUri = magnet,
                sources = stream.sources,
                torrentName = stream.title ?: stream.name,
                filename = stream.behaviorHints?.filename,
                mediaType = null,
                mediaId = null,
                mediaOnlyId = null,
                title = stream.title ?: stream.name,
                season = season,
                episode = episode,
                service = account.provider.id,
                serviceIndex = null,
                serviceExtension = null,
                isCached = stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED,
                stream = null
            )
        )

        return when (account.provider.id) {
            DebridProviders.TORBOX_ID -> torboxResolver.resolve(resolveStream, season, episode)
            DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(resolveStream, season, episode)
            DebridProviders.REAL_DEBRID_ID -> realDebridResolver.resolve(resolveStream, season, episode)
            DebridProviders.ALLDEBRID_ID -> resolveViaAllDebrid(
                account.apiKey,
                resolveStream.infoHash,
                resolveStream.fileIdx,
                resolveStream.behaviorHints?.filename,
                season,
                episode
            )
            else -> DirectDebridResolveResult.Error
        }
    }

    private suspend fun resolveViaAllDebrid(
        apiKey: String,
        infoHash: String?,
        fileIdx: Int?,
        filename: String?,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val url = allDebridResolver.resolve(apiKey, infoHash ?: "", fileIdx, season, episode)
        return if (url != null) DirectDebridResolveResult.Success(
            url = url, filename = filename, videoSize = null
        ) else DirectDebridResolveResult.Stale
    }

    private fun localTorrentResolveCredential(
        settings: com.nuvio.tv.domain.model.DebridSettings
    ): DebridServiceCredential? =
        settings.activeResolverCredential
            ?.takeIf { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentResolve) }

    private fun localTorrentResolveCredentials(
        settings: com.nuvio.tv.domain.model.DebridSettings,
        cacheHitProviderId: String?
    ): List<DebridServiceCredential> {
        val configured = DebridProviders.configuredResolverServices(settings)
            .filter { it.provider.supports(DebridProviderCapability.LocalTorrentResolve) }
        val preferredProviderId = settings.activeResolverProviderId
        return configured.sortedWith(
            compareByDescending<DebridServiceCredential> { it.provider.id == cacheHitProviderId }
                .thenByDescending { it.provider.id == preferredProviderId }
                .thenBy { it.provider.id }
        )
    }
}

private const val DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS = 15L * 60L * 1000L

private data class CachedDirectDebridResolve(
    val result: DirectDebridResolveResult.Success,
    val cachedAtMs: Long
)

sealed class DirectDebridPlayableResult {
    data class Success(val stream: Stream) : DirectDebridPlayableResult()
    data object MissingApiKey : DirectDebridPlayableResult()
    data object NotCached : DirectDebridPlayableResult()
    data object Stale : DirectDebridPlayableResult()
    data object Error : DirectDebridPlayableResult()
}

private fun String.stableFingerprint(): String {
    val hash = fold(1125899906842597L) { acc, char -> (acc * 31L) + char.code }
    return hash.toULong().toString(16)
}

private fun Stream.withResolvedDebridUrl(result: DirectDebridResolveResult.Success): Stream =
    copy(
        url = result.url,
        externalUrl = null,
        behaviorHints = behaviorHints.mergeResolvedDebridHints(result)
    )

private fun StreamBehaviorHints?.mergeResolvedDebridHints(
    result: DirectDebridResolveResult.Success
): StreamBehaviorHints {
    val current = this
    if (current != null) {
        return current.copy(
            filename = result.filename ?: current.filename,
            videoSize = result.videoSize ?: current.videoSize
        )
    }
    return StreamBehaviorHints(
        notWebReady = null,
        bingeGroup = null,
        countryWhitelist = null,
        proxyHeaders = null,
        filename = result.filename,
        videoSize = result.videoSize
    )
}
