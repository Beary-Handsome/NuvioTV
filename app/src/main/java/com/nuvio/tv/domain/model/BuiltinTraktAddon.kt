package com.nuvio.tv.domain.model

/**
 * Definition of the built-in Trakt catalog source.
 *
 * Provides three home rows backed directly by the user's Trakt account:
 *   - Watchlist (mixed movies + shows, sorted by added-desc)
 *   - Recommended Movies
 *   - Recommended Shows
 *
 * Rendered like any other addon (same row/picker UI), but [Addon.kind] is
 * [AddonKind.BUILTIN_TRAKT] so the catalog router dispatches it to
 * `TraktCatalogSource` instead of issuing an HTTP request against the
 * sentinel base URL. The addon is only injected into the active list when
 * the user is logged into Trakt — see `AddonRepository`.
 */
object BuiltinTraktAddon {
    const val ID = "builtin.trakt"
    const val BASE_URL = "builtin://trakt"

    val INSTANCE: Addon by lazy { create() }

    fun create(): Addon = Addon(
        id = ID,
        name = "Trakt",
        displayName = "Trakt",
        version = "1.0.0",
        description = "Recommendations and watchlist from your Trakt account.",
        logo = null,
        background = null,
        baseUrl = BASE_URL,
        catalogs = TraktCatalogs.ALL,
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = listOf("movie", "series"),
        resources = listOf(
            AddonResource(
                name = "catalog",
                types = listOf("movie", "series"),
                idPrefixes = null
            )
        ),
        idPrefixes = listOf("tmdb:", "tt"),
        kind = AddonKind.BUILTIN_TRAKT
    )
}

object TraktCatalogs {
    // Mixed watchlist (movies + shows merged by listed_at desc). Declared
    // as a MOVIE-typed catalog because the descriptor model requires a
    // single type, but the row's items each carry their own per-item type,
    // so click routing still picks the right detail screen.
    const val WATCHLIST = "trakt.watchlist"
    const val RECOMMENDED_MOVIES = "trakt.recommended.movie"
    const val RECOMMENDED_SHOWS = "trakt.recommended.tv"

    private fun movie(id: String, name: String, inHome: Boolean = true): CatalogDescriptor =
        CatalogDescriptor(
            type = ContentType.MOVIE,
            rawType = "movie",
            id = id,
            name = name,
            pageSize = TRAKT_PAGE_SIZE,
            showInHome = inHome,
            hasExplicitShowInHome = true,
            extraSupported = emptyList()
        )

    private fun tv(id: String, name: String, inHome: Boolean = true): CatalogDescriptor =
        CatalogDescriptor(
            type = ContentType.SERIES,
            rawType = "series",
            id = id,
            name = name,
            pageSize = TRAKT_PAGE_SIZE,
            showInHome = inHome,
            hasExplicitShowInHome = true,
            extraSupported = emptyList()
        )

    // Watchlist is intentionally NOT a home catalog row — it already lives
    // as its own tab in Library, and duplicating it on home wasted a row
    // for content the user can find with one click.
    val ALL: List<CatalogDescriptor> = listOf(
        movie(RECOMMENDED_MOVIES, "Recommended Movies"),
        tv(RECOMMENDED_SHOWS, "Recommended Shows"),
    )

    /**
     * Trakt's recommendation + watchlist endpoints don't paginate the way
     * the rest of the home pipeline does, so we cap the row size to one
     * "page" worth and report no hasMore.
     */
    const val TRAKT_PAGE_SIZE = 40
}
