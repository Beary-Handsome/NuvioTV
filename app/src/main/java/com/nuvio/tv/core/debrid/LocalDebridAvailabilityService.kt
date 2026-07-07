package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDebridAvailabilityService @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val localDebridService: LocalDebridService
) {
    suspend fun markChecking(groups: List<AddonStreams>): List<AddonStreams> {
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return groups
        val primary = accounts.first()
        return groups.updateAvailabilityStatus { stream ->
            if (stream.localAvailabilityHash() == null || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) {
                stream
            } else {
                stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = primary.provider.id,
                        providerName = primary.provider.displayName,
                        state = StreamDebridCacheState.CHECKING
                    )
                )
            }
        }
    }

    suspend fun annotateCachedAvailability(groups: List<AddonStreams>): List<AddonStreams> {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return groups
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return groups

        val hashes = groups.flatMap { group ->
            group.streams.mapNotNull { stream ->
                stream.localAvailabilityHash()
                    ?.takeUnless { stream.debridCacheStatus?.state in FINAL_CACHE_STATES }
            }
        }.distinct()
        if (hashes.isEmpty()) return groups

        // Check ALL configured providers in parallel. Keep each provider's raw
        // nullable result: null means the provider call failed / returned nothing
        // definitive, which must NOT be conflated with a confirmed cache miss.
        val allResults = coroutineScope {
            accounts.map { account ->
                async {
                    account to localDebridService.checkCached(account = account, hashes = hashes)
                }
            }.map { it.await() }
        }

        // If NO provider returned a definitive answer, the whole check was
        // inconclusive (network timeout, rate-limit, empty response). In that
        // case streams are marked UNKNOWN (below) rather than NOT_CACHED, so a
        // transient failure can't permanently disable auto-play for them.
        val anyProviderAnswered = allResults.any { (_, cached) -> cached != null }

        // Merge results: prefer the user's preferred provider, fall back to any that has it
        val preferredId = settings.activeResolverProviderId
        val mergedCache = mutableMapOf<String, CacheHit>()
        for ((account, cached) in allResults) {
            if (cached == null) continue
            for ((hash, item) in cached) {
                val existing = mergedCache[hash]
                // Overwrite if no existing hit, or if this is the preferred provider
                if (existing == null || (account.provider.id == preferredId && existing.providerId != preferredId)) {
                    mergedCache[hash] = CacheHit(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        item = item
                    )
                }
            }
        }

        val fallbackAccount = accounts.first()
        return groups.updateAvailabilityStatus { stream ->
            val hash = stream.localAvailabilityHash() ?: return@updateAvailabilityStatus stream
            if (stream.debridCacheStatus?.state in FINAL_CACHE_STATES) return@updateAvailabilityStatus stream
            val hit = mergedCache[hash]
            val resolvedState = when {
                hit != null -> StreamDebridCacheState.CACHED
                // Only a definitive negative from a provider that actually
                // answered counts as NOT_CACHED; an all-failed check is UNKNOWN.
                anyProviderAnswered -> StreamDebridCacheState.NOT_CACHED
                else -> StreamDebridCacheState.UNKNOWN
            }
            stream.copy(
                debridCacheStatus = StreamDebridCacheStatus(
                    providerId = hit?.providerId ?: fallbackAccount.provider.id,
                    providerName = hit?.providerName ?: fallbackAccount.provider.displayName,
                    state = resolvedState,
                    cachedName = hit?.item?.name,
                    cachedSize = hit?.item?.size
                )
            )
        }
    }

    suspend fun isCached(hash: String): Boolean? {
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return null
        // Check all providers — cached on ANY means cached
        for (account in accounts) {
            val result = localDebridService.isCached(account, hash)
            if (result == true) return true
        }
        return false
    }

    private suspend fun cacheCheckAccounts(): List<DebridServiceCredential> {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return emptyList()
        return DebridProviders.configuredServices(settings)
            .filter { it.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
    }
}

private data class CacheHit(
    val providerId: String,
    val providerName: String,
    val item: LocalDebridCachedItem
)

private val FINAL_CACHE_STATES = setOf(
    StreamDebridCacheState.CACHED,
    StreamDebridCacheState.NOT_CACHED
)

fun Stream.localAvailabilityHash(): String? =
    getEffectiveInfoHash()
        ?.trim()
        ?.lowercase()
        ?.takeIf { needsLocalDebridResolve() && it.isNotBlank() }

private fun List<AddonStreams>.updateAvailabilityStatus(
    transform: (Stream) -> Stream
): List<AddonStreams> =
    map { group ->
        var changed = false
        val updatedStreams = group.streams.map { stream ->
            val updated = transform(stream)
            if (updated != stream) changed = true
            updated
        }
        if (changed) group.copy(streams = updatedStreams) else group
    }
