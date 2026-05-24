package com.nuvio.tv.data.repository.sources

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catalog source backed by the standard Stremio addon protocol.
 *
 * Issues a single HTTP GET against the addon's `/catalog/{type}/{id}.json`
 * endpoint (or `/catalog/{type}/{id}/<extra>.json` when extra args are
 * present), parses the Stremio meta-preview response, and emits a single
 * [CatalogRow] wrapped in [NetworkResult].
 *
 * This is the lift-and-shift of the original [com.nuvio.tv.data.repository.CatalogRepositoryImpl]
 * fetch path. The router-style impl now delegates here for Stremio addons
 * and to [TmdbCatalogSource] for the built-in TMDB source.
 */
@Singleton
class StremioCatalogSource @Inject constructor(
    private val api: AddonApi,
) {
    companion object {
        private const val TAG = "StremioCatalogSource"
    }

    fun fetch(
        addon: Addon,
        catalog: CatalogDescriptor,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean,
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        val apiType = catalog.apiType
        val url = buildCatalogUrl(addon.baseUrl, apiType, catalog.id, skip, extraArgs)
        Log.d(
            TAG,
            "fetch addonId=${addon.id} addonName=${addon.name} type=$apiType " +
                "catalogId=${catalog.id} skip=$skip skipStep=$skipStep " +
                "supportsSkip=$supportsSkip url=$url"
        )

        when (val result = safeApiCall { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val items = result.data.metas
                    .map { it.toDomain() }
                    .distinctBy { it.id }
                Log.d(
                    TAG,
                    "success addonId=${addon.id} type=$apiType catalogId=${catalog.id} items=${items.size}"
                )

                val effectiveSkipStep = if (skip == 0 && items.isNotEmpty() && items.size < skipStep) {
                    items.size
                } else {
                    skipStep
                }
                emit(
                    NetworkResult.Success(
                        CatalogRow(
                            addonId = addon.id,
                            addonName = addon.name,
                            addonBaseUrl = addon.baseUrl,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = catalog.type,
                            rawType = apiType,
                            items = items,
                            isLoading = false,
                            hasMore = supportsSkip && items.isNotEmpty(),
                            currentPage = if (effectiveSkipStep > 0) skip / effectiveSkipStep else 0,
                            supportsSkip = supportsSkip,
                            skipStep = effectiveSkipStep,
                            extraArgs = extraArgs
                        )
                    )
                )
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "fetch failed addonId=${addon.id} type=$apiType catalogId=${catalog.id} " +
                        "code=${result.code} message=${result.message} url=$url"
                )
                emit(result)
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
