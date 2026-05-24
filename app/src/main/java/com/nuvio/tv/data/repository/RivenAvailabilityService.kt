package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.RivenAvailabilityRequestDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls each addon's POST /availability endpoint with the IMDB IDs the user
 * has from that addon. Addons that don't implement the endpoint (404 / connect
 * error) are silently skipped — items from those addons stay null.
 *
 * Designed for the library screen: tells us which entries Riven can actually
 * play right now, so the UI can badge or hide unavailable items.
 *
 * Reachability state is tracked PER ADDON: a user-installed community
 * Stremio addon and Riven each get their own failure counter, so a 404
 * storm from a non-Riven addon doesn't clear Riven's outage flag (or vice
 * versa). The exposed `isReachable` flag specifically tracks the addon
 * whose URL matches the Riven pattern.
 */
@Singleton
class RivenAvailabilityService @Inject constructor(
    private val addonApi: AddonApi
) {
    companion object {
        private const val TAG = "RivenAvailability"
        private const val TIMEOUT_MS = 4_000L
        // Tolerate transient network blips before flipping the reachability
        // flag — Riven is local-network so single-call failures are usually
        // wifi hiccups rather than a real outage. Three consecutive misses
        // (~12s of wall time at TIMEOUT_MS) before pessimism kicks in.
        private const val UNREACHABLE_THRESHOLD = 3
        // Recovery probe interval: how often we re-check `/availability`
        // while the flag is `false`, so a banner doesn't stick after a
        // transient outage when no catalog fetches are running.
        private const val RECOVERY_PROBE_INTERVAL_MS = 20_000L
    }

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Per-addon failure counters. Keyed by addon base URL.
    private val failuresByAddon = ConcurrentHashMap<String, Int>()
    // One recovery probe per unreachable addon.
    private val probeJobsByAddon = ConcurrentHashMap<String, Job>()

    private val _isReachable = MutableStateFlow(true)
    /**
     * Whether *Riven* is currently reachable. The flag goes false when ANY
     * addon whose base URL matches the Riven pattern crosses the failure
     * threshold; it goes true when that same addon recovers. Other addons'
     * health doesn't affect this flag.
     */
    val isReachable: StateFlow<Boolean> = _isReachable.asStateFlow()

    /**
     * Returns a map of {imdbId → true/false}. IDs that no addon could resolve
     * (or the call timed out / failed) are absent from the result map.
     */
    suspend fun fetch(idsByAddonBaseUrl: Map<String, List<String>>): Map<String, Boolean> {
        if (idsByAddonBaseUrl.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val results = idsByAddonBaseUrl
                    .filterValues { it.isNotEmpty() }
                    .map { (baseUrl, ids) ->
                        async { fetchOne(baseUrl, ids) }
                    }
                    .map { it.await() }

                val merged = mutableMapOf<String, Boolean>()
                results.forEach { partial -> merged.putAll(partial) }
                merged
            }
        }
    }

    private suspend fun fetchOne(addonBaseUrl: String, ids: List<String>): Map<String, Boolean> {
        val url = buildAvailabilityUrl(addonBaseUrl) ?: return emptyMap()
        val response = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                addonApi.postRivenAvailability(url, RivenAvailabilityRequestDto(ids = ids))
            }.onFailure { Log.d(TAG, "availability call failed for $addonBaseUrl: ${it.message}") }
                .getOrNull()
        }

        if (response == null) {
            // Network / timeout failure — bump THIS addon's strike count only.
            val strikes = failuresByAddon.merge(addonBaseUrl, 1) { old, inc -> old + inc } ?: 1
            if (strikes >= UNREACHABLE_THRESHOLD && isRivenUrl(addonBaseUrl) && _isReachable.value) {
                Log.w(TAG, "marking Riven unreachable after $strikes consecutive failures on $addonBaseUrl")
                _isReachable.value = false
                startRecoveryProbe(addonBaseUrl)
            }
            return emptyMap()
        }

        if (!response.isSuccessful) {
            // 404 → addon doesn't implement /availability; treat as no info,
            // not as an outage. Successful HTTP roundtrip means the host is
            // alive, just doesn't speak the protocol.
            markReachableSuccess(addonBaseUrl)
            return emptyMap()
        }
        markReachableSuccess(addonBaseUrl)
        return response.body()?.available.orEmpty()
    }

    private fun markReachableSuccess(addonBaseUrl: String) {
        failuresByAddon[addonBaseUrl] = 0
        if (isRivenUrl(addonBaseUrl) && !_isReachable.value) {
            Log.i(TAG, "Riven back online ($addonBaseUrl)")
            _isReachable.value = true
            probeJobsByAddon.remove(addonBaseUrl)?.cancel()
        }
    }

    /**
     * Heuristic: is `url` the Riven addon? Riven's addon serves at the
     * stremiofin path or carries `riven` in the hostname. Any community
     * Stremio addon a user has installed is ignored for banner purposes.
     */
    private fun isRivenUrl(url: String): Boolean {
        val lower = url.lowercase()
        return "riven" in lower || "stremiofin" in lower
    }

    /**
     * Re-check Riven `/availability` on a slow loop while the reachability
     * flag is `false`. Probes ONLY the originally-failing addon's URL so
     * we don't clear Riven's outage by probing the wrong endpoint.
     */
    private fun startRecoveryProbe(addonBaseUrl: String) {
        if (probeJobsByAddon[addonBaseUrl]?.isActive == true) return
        val job = probeScope.launch {
            while (!_isReachable.value) {
                delay(RECOVERY_PROBE_INTERVAL_MS)
                // Sentinel id: clearly not a real IMDb id, but the
                // endpoint validates input and returns an empty map. Any
                // HTTP roundtrip clears the flag via markReachableSuccess.
                runCatching { fetchOne(addonBaseUrl, listOf("tt0000000")) }
                    .onFailure { Log.d(TAG, "recovery probe failed for $addonBaseUrl: ${it.message}") }
            }
        }
        probeJobsByAddon[addonBaseUrl] = job
    }

    private fun buildAvailabilityUrl(addonBaseUrl: String): String? {
        if (addonBaseUrl.isBlank()) return null
        // Addon base URLs are expected to end at the manifest path or be the bare base.
        // Strip a trailing manifest.json or /configure if present, then append /availability.
        val trimmed = addonBaseUrl
            .removeSuffix("/")
            .removeSuffix("/manifest.json")
            .removeSuffix("/configure")
            .removeSuffix("/")
        return "$trimmed/availability"
    }
}
