package com.nuvio.tv.domain.model

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DebridStreamFormatterDefaults
import com.nuvio.tv.core.debrid.supports

data class DebridSettings(
    val enabled: Boolean = false,
    val cloudLibraryEnabled: Boolean = true,
    val torboxApiKey: String = "",
    val premiumizeApiKey: String = "",
    val realDebridApiKey: String = "",
    val allDebridApiKey: String = "",
    val easynewsUsername: String = "",
    val easynewsPassword: String = "",
    val preferredResolverProviderId: String = "",
    val instantPlaybackPreparationLimit: Int = 0,
    val streamMaxResults: Int = 0,
    val streamSortMode: DebridStreamSortMode = DebridStreamSortMode.DEFAULT,
    val streamMinimumQuality: DebridStreamMinimumQuality = DebridStreamMinimumQuality.ANY,
    val streamDolbyVisionFilter: DebridStreamFeatureFilter = DebridStreamFeatureFilter.ANY,
    val streamHdrFilter: DebridStreamFeatureFilter = DebridStreamFeatureFilter.ANY,
    val streamCodecFilter: DebridStreamCodecFilter = DebridStreamCodecFilter.ANY,
    val streamPreferences: DebridStreamPreferences = DebridStreamPreferences(),
    val streamNameTemplate: String = DebridStreamFormatterDefaults.NAME_TEMPLATE,
    val streamDescriptionTemplate: String = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE
) {
    val hasAnyApiKey: Boolean
        get() = DebridProviders.configuredServices(this).isNotEmpty()

    val resolverServices: List<com.nuvio.tv.core.debrid.DebridServiceCredential>
        get() = DebridProviders.configuredResolverServices(this)

    val activeResolverCredential: com.nuvio.tv.core.debrid.DebridServiceCredential?
        get() = DebridProviders.preferredResolverService(this)

    val activeResolverProviderId: String?
        get() = activeResolverCredential?.provider?.id

    val hasResolverProvider: Boolean
        get() = activeResolverCredential != null

    val canResolvePlayableLinks: Boolean
        get() = enabled && hasResolverProvider

    val hasCloudLibraryProvider: Boolean
        get() = DebridProviders.configuredServices(this)
            .any { credential -> credential.provider.supports(com.nuvio.tv.core.debrid.DebridProviderCapability.CloudLibrary) }

    val canUseCloudLibrary: Boolean
        get() = cloudLibraryEnabled && hasCloudLibraryProvider

    fun apiKeyFor(providerId: String?): String {
        return when (DebridProviders.byId(providerId)?.id ?: providerId?.trim()?.lowercase()) {
            DebridProviders.TORBOX_ID -> torboxApiKey
            DebridProviders.PREMIUMIZE_ID -> premiumizeApiKey
            DebridProviders.REAL_DEBRID_ID -> realDebridApiKey
            DebridProviders.ALLDEBRID_ID -> allDebridApiKey
            DebridProviders.EASYNEWS_ID -> {
                // EN uses user:pass, encode as "user:pass" so configuredServices check works
                if (easynewsUsername.isNotBlank() && easynewsPassword.isNotBlank()) {
                    "$easynewsUsername:$easynewsPassword"
                } else ""
            }
            else -> ""
        }
    }

    val isEasynewsConfigured: Boolean
        get() = easynewsUsername.isNotBlank() && easynewsPassword.isNotBlank()

    val isAllDebridConfigured: Boolean
        get() = allDebridApiKey.isNotBlank()
}

const val DEBRID_PREPARE_INSTANT_PLAYBACK_DEFAULT_LIMIT = 2
