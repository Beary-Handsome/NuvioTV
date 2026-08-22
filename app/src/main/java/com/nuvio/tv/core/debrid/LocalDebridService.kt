package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCheckCachedRequestDto
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

data class LocalDebridCachedItem(
    val name: String?,
    val size: Long?
)

@Singleton
class LocalDebridService @Inject constructor(
    private val torboxApi: TorboxApi,
    private val premiumizeApi: PremiumizeApi,
    private val allDebridApi: AllDebridApi,
    private val realDebridApi: RealDebridApi
) {
    suspend fun checkCached(
        account: DebridServiceCredential,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? =
        when (account.provider.id) {
            DebridProviders.TORBOX_ID -> checkTorboxCached(account.apiKey, hashes)
            DebridProviders.PREMIUMIZE_ID -> checkPremiumizeCached(account.apiKey, hashes)
            DebridProviders.ALLDEBRID_ID -> checkAllDebridCached(account.apiKey, hashes)
            DebridProviders.REAL_DEBRID_ID -> checkRealDebridCached(account.apiKey, hashes)
            else -> null
        }

    suspend fun isCached(account: DebridServiceCredential, hash: String): Boolean? {
        val normalizedHash = hash.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        return checkCached(account, listOf(normalizedHash))?.containsKey(normalizedHash)
    }

    private suspend fun checkTorboxCached(
        apiKey: String,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes.normalizedHashes()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val response = torboxApi.checkCached(
                authorization = "Bearer ${apiKey.trim()}",
                body = TorboxCheckCachedRequestDto(hashes = normalizedHashes)
            )
            val body = response.body()
            if (!response.isSuccessful || body?.success == false) {
                null
            } else {
                body?.data.orEmpty().mapKeys { it.key.lowercase() }.mapValues { (_, value) ->
                    LocalDebridCachedItem(
                        name = value.name,
                        size = value.size
                    )
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun checkPremiumizeCached(
        apiKey: String,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes.normalizedHashes()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val response = premiumizeApi.checkCache(
                authorization = "Bearer ${apiKey.trim()}",
                items = normalizedHashes.map { hash -> "magnet:?xt=urn:btih:$hash" }
            )
            val body = response.body()
            if (!response.isSuccessful || body?.status.equals("error", ignoreCase = true)) {
                null
            } else {
                normalizedHashes.mapIndexedNotNull { index, hash ->
                    if (body?.response?.getOrNull(index) != true) return@mapIndexedNotNull null
                    hash to LocalDebridCachedItem(
                        name = body.filename?.getOrNull(index),
                        size = body.filesize?.getOrNull(index)
                    )
                }.toMap()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun checkAllDebridCached(
        apiKey: String,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes.normalizedHashes()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val response = allDebridApi.checkInstant(apiKey = apiKey, magnets = normalizedHashes)
            if (!response.isSuccessful) return null
            val magnets = response.body()?.data?.magnets ?: return emptyMap()
            buildMap {
                magnets.forEach { m ->
                    val hash = m.hash?.trim()?.lowercase()
                    if (hash != null && m.instant == true) {
                        put(hash, LocalDebridCachedItem(name = null, size = null))
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun checkRealDebridCached(
        apiKey: String,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes.normalizedHashes()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val authorization = "Bearer ${apiKey.trim()}"
            val result = mutableMapOf<String, LocalDebridCachedItem>()
            // RD supports multiple hashes per request: /torrents/instantAvailability/hash1/hash2/...
            // Batch in groups of 100 to stay within URL length limits
            for (batch in normalizedHashes.chunked(100)) {
                try {
                    val hashPath = batch.joinToString("/")
                    val response = realDebridApi.instantAvailability(authorization, hashPath)
                    if (!response.isSuccessful) return null
                    val body = response.body()?.string() ?: return null
                    try {
                        val json = org.json.JSONObject(body)
                        for (hash in batch) {
                            val hashData = json.optJSONObject(hash) ?: json.optJSONObject(hash.uppercase())
                            if (hashData != null && hashData.has("rd")) {
                                result[hash] = LocalDebridCachedItem(name = null, size = null)
                            }
                        }
                    } catch (_: Exception) {
                        return null
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return null
                }
            }
            result
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("LocalDebridService", "RD cache check failed: ${error.message}")
            null
        }

    private fun List<String>.normalizedHashes(): List<String> =
        map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
}
