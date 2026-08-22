package com.nuvio.tv.core.cloud

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridProviderCapability
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DebridServiceCredential
import com.nuvio.tv.core.debrid.supports
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Singleton
class CloudLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DebridSettingsDataStore,
    torboxApi: TorboxCloudLibraryProviderApi,
    premiumizeApi: PremiumizeCloudLibraryProviderApi,
    realDebridApi: RealDebridCloudLibraryProviderApi,
    allDebridApi: AllDebridCloudLibraryProviderApi
) {
    private val providerApis: List<CloudLibraryProviderApi> = listOf(
        torboxApi,
        premiumizeApi,
        realDebridApi,
        allDebridApi
    )

    suspend fun refresh(): CloudLibraryUiState {
        val settings = dataStore.settings.first()
        if (!settings.cloudLibraryEnabled) {
            return CloudLibraryUiState(isLoaded = true, isEnabled = false)
        }

        val credentials = DebridProviders.configuredServices(settings)
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }

        val providerStates = credentials.map { credential ->
            val api = providerApis.firstOrNull { it.provider.id == credential.provider.id }
            if (api == null) {
                return@map CloudLibraryProviderState(
                    provider = credential.provider,
                    errorMessage = context.getString(
                        R.string.cloud_library_error_provider_unavailable,
                        credential.provider.displayName
                    )
                )
            }

            api.listItems(credential.apiKey)
                .fold(
                    onSuccess = { items ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            items = items
                        )
                    },
                    onFailure = { error ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            errorMessage = error.message
                        )
                    }
                )
        }

        return CloudLibraryUiState(
            isLoaded = true,
            isEnabled = true,
            isRefreshing = false,
            providers = providerStates
        )
    }

    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val settings = dataStore.settings.first()
        if (!settings.cloudLibraryEnabled) {
            return CloudLibraryPlaybackResult.Failed(
                context.getString(R.string.cloud_library_error_disabled)
            )
        }
        val credential = DebridProviders.configuredServices(settings)
            .firstOrNull { credential -> credential.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.MissingCredentials
        val api = providerApis.firstOrNull { it.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.Failed()
        return api.resolvePlayback(
            apiKey = credential.apiKey,
            item = item,
            file = file
        )
    }

    suspend fun connectedCloudCredentials(): List<DebridServiceCredential> {
        val settings = dataStore.settings.first()
        return settings
            .takeIf { it.cloudLibraryEnabled }
            ?.let(DebridProviders::configuredServices)
            .orEmpty()
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }
    }

    suspend fun findMatchingStreams(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?
    ): List<Stream> {
        val state = refresh()
        val matches = CloudMediaMatcher.findMatches(
            items = state.items,
            title = title,
            year = year,
            season = season,
            episode = episode
        ).take(MAX_CLOUD_STREAM_MATCHES)
        return coroutineScope {
            matches.map { match ->
                async {
                    when (val result = resolvePlayback(match.item, match.file)) {
                        is CloudLibraryPlaybackResult.Success -> Stream(
                            name = "[${match.item.providerName} Cloud] ${match.file.name}",
                            title = match.file.name,
                            description = null,
                            url = result.url,
                            ytId = null,
                            infoHash = null,
                            fileIdx = null,
                            externalUrl = null,
                            behaviorHints = StreamBehaviorHints(
                                notWebReady = false,
                                bingeGroup = "cloud:${match.item.providerId}:${match.item.id}",
                                countryWhitelist = null,
                                proxyHeaders = null,
                                videoSize = result.videoSizeBytes ?: match.file.sizeBytes,
                                filename = result.filename ?: match.file.name
                            ),
                            addonName = "${match.item.providerName} Cloud",
                            addonLogo = null,
                            debridCacheStatus = StreamDebridCacheStatus(
                                providerId = match.item.providerId,
                                providerName = match.item.providerName,
                                state = StreamDebridCacheState.CACHED,
                                cachedName = match.file.name,
                                cachedSize = match.file.sizeBytes
                            )
                        )
                        else -> null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private companion object {
        const val MAX_CLOUD_STREAM_MATCHES = 30
    }
}
