package com.nuvio.tv.domain.model

/**
 * Definition of the built-in OpenSubtitles source.
 *
 * Provides subtitles directly from `api.opensubtitles.com` instead of
 * going through a Stremio subtitle addon. Reuses the existing
 * SubtitleRepository iteration / Subtitle UI by appearing in the
 * installed-addon list with [AddonKind.BUILTIN_OPENSUBTITLES] and a
 * declared `subtitles` resource — the repository dispatches on kind so
 * we never actually hit `baseUrl` over the network.
 */
object BuiltinOpenSubtitlesAddon {
    const val ID = "builtin.opensubtitles"
    const val BASE_URL = "builtin://opensubtitles"
    const val DISPLAY_NAME = "OpenSubtitles"

    val INSTANCE: Addon by lazy { create() }

    fun create(): Addon = Addon(
        id = ID,
        name = "OpenSubtitles",
        displayName = "OpenSubtitles",
        version = "1.0.0",
        description = "Subtitles direct from OpenSubtitles.com (no addon proxy).",
        logo = null,
        background = null,
        baseUrl = BASE_URL,
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = listOf("movie", "series"),
        resources = listOf(
            AddonResource(
                name = "subtitles",
                types = listOf("movie", "series"),
                idPrefixes = listOf("tt", "tmdb:")
            )
        ),
        idPrefixes = listOf("tt", "tmdb:"),
        kind = AddonKind.BUILTIN_OPENSUBTITLES
    )
}
