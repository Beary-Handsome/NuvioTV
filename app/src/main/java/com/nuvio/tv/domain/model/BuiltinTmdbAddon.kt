package com.nuvio.tv.domain.model

/**
 * Definition of the built-in TMDB catalog source.
 *
 * Nuvio talks to TMDB directly for its home rows / discovery catalogs
 * instead of going through a Stremio meta-addon proxy. The TMDB
 * source is represented as an [Addon] with [AddonKind.BUILTIN_TMDB] so
 * it participates in the existing catalog enumeration, ordering, and
 * user-disable controls without special-casing the home pipeline.
 *
 * The addon is injected into the active list by AddonRepository — there
 * is no install / uninstall path. Its [Addon.baseUrl] is a sentinel
 * (`builtin://tmdb`) that should never be sent over the network; the
 * catalog router dispatches on [Addon.kind] and never reads it.
 */
object BuiltinTmdbAddon {
    const val ID = "builtin.tmdb"
    const val BASE_URL = "builtin://tmdb"

    // Built-in addon descriptors are immutable, so we keep one instance per
    // VM-lifetime rather than re-allocating on every preference change.
    val INSTANCE: Addon by lazy { create() }

    fun create(): Addon = Addon(
        id = ID,
        name = "TMDB",
        displayName = "TMDB",
        version = "1.0.0",
        description = "Movies and shows discovered directly from TMDB.",
        logo = null,
        background = null,
        baseUrl = BASE_URL,
        catalogs = TmdbCatalogs.ALL,
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
        kind = AddonKind.BUILTIN_TMDB
    )
}

/**
 * The set of catalog rows the built-in TMDB source exposes. Each catalog
 * id is a stable string that [TmdbCatalogSource] dispatches on — keep
 * them in sync.
 *
 * `showInHome = true` puts the row on the home screen by default; the
 * user can still reorder or disable any row through the standard catalog
 * preferences just like with a Stremio addon.
 */
object TmdbCatalogs {
    const val MOVIE_TRENDING = "tmdb.trending.movie"
    const val MOVIE_POPULAR = "tmdb.popular.movie"
    const val MOVIE_TOP_RATED = "tmdb.top_rated.movie"
    const val MOVIE_NOW_PLAYING = "tmdb.now_playing.movie"
    const val MOVIE_UPCOMING = "tmdb.upcoming.movie"

    const val TV_TRENDING = "tmdb.trending.tv"
    const val TV_POPULAR = "tmdb.popular.tv"
    const val TV_TOP_RATED = "tmdb.top_rated.tv"
    const val TV_ON_THE_AIR = "tmdb.on_the_air.tv"
    const val TV_AIRING_TODAY = "tmdb.airing_today.tv"

    // Per-streaming-service catalogs (TMDB watch-providers). Off-home by
    // default — they live in the Discover tab so Home stays uncluttered.
    // Catalog id format: `tmdb.provider.<provider_id>.<movie|tv>`.
    // Provider ids are TMDB's stable watch-provider ids (US region).
    data class ProviderSpec(
        val providerId: Int,
        val movieLabel: String,
        val tvLabel: String,
    )

    val PROVIDERS: List<ProviderSpec> = listOf(
        ProviderSpec(8,    "Netflix Movies",        "Netflix Shows"),
        ProviderSpec(337,  "Disney+ Movies",        "Disney+ Shows"),
        ProviderSpec(1899, "Max Movies",            "Max Shows"),
        ProviderSpec(9,    "Prime Video Movies",    "Prime Video Shows"),
        ProviderSpec(350,  "Apple TV+ Movies",      "Apple TV+ Shows"),
        ProviderSpec(15,   "Hulu Movies",           "Hulu Shows"),
        ProviderSpec(387,  "Peacock Movies",        "Peacock Shows"),
        ProviderSpec(531,  "Paramount+ Movies",     "Paramount+ Shows"),
    )

    const val PROVIDER_CATALOG_PREFIX = "tmdb.provider."

    fun providerMovieCatalogId(providerId: Int): String =
        "$PROVIDER_CATALOG_PREFIX$providerId.movie"

    fun providerTvCatalogId(providerId: Int): String =
        "$PROVIDER_CATALOG_PREFIX$providerId.tv"

    /**
     * Parse a provider-catalog id back into (providerId, isMovie). Returns
     * null when the id is not a provider catalog.
     */
    fun parseProviderCatalog(catalogId: String): Pair<Int, Boolean>? {
        if (!catalogId.startsWith(PROVIDER_CATALOG_PREFIX)) return null
        val tail = catalogId.removePrefix(PROVIDER_CATALOG_PREFIX)
        val dot = tail.indexOf('.')
        if (dot <= 0) return null
        val providerId = tail.substring(0, dot).toIntOrNull() ?: return null
        val kind = tail.substring(dot + 1)
        return when (kind) {
            "movie" -> providerId to true
            "tv" -> providerId to false
            else -> null
        }
    }

    // Genre catalogs — TMDB genre ids are stable. Both endpoints share most
    // ids (Action=28, Comedy=35, etc.) but TV has a few different ones
    // (Sci-Fi & Fantasy=10765, War & Politics=10768). We expose only the
    // genres that map cleanly to both types so the row counts stay paired.
    data class GenreSpec(
        val movieGenreId: Int,
        val tvGenreId: Int?,
        val label: String,
    )

    /**
     * Each spec maps Nuvio's display label to TMDB's genre ids. `tvGenreId`
     * is nullable because TMDB's TV genre namespace is smaller — Horror,
     * for instance, has no TV counterpart, so the TV side of that row is
     * omitted entirely rather than redirected to an unrelated genre.
     */
    val GENRES: List<GenreSpec> = listOf(
        GenreSpec(movieGenreId = 28,    tvGenreId = 10759, label = "Action"),    // tv "Action & Adventure"
        GenreSpec(movieGenreId = 12,    tvGenreId = 10759, label = "Adventure"), // tv shares "Action & Adventure"
        GenreSpec(movieGenreId = 16,    tvGenreId = 16,    label = "Animation"),
        GenreSpec(movieGenreId = 35,    tvGenreId = 35,    label = "Comedy"),
        GenreSpec(movieGenreId = 80,    tvGenreId = 80,    label = "Crime"),
        GenreSpec(movieGenreId = 99,    tvGenreId = 99,    label = "Documentary"),
        GenreSpec(movieGenreId = 18,    tvGenreId = 18,    label = "Drama"),
        GenreSpec(movieGenreId = 10751, tvGenreId = 10751, label = "Family"),
        GenreSpec(movieGenreId = 14,    tvGenreId = 10765, label = "Fantasy"),   // tv "Sci-Fi & Fantasy"
        GenreSpec(movieGenreId = 36,    tvGenreId = null,  label = "History"),
        GenreSpec(movieGenreId = 27,    tvGenreId = null,  label = "Horror"),    // movie-only; tv has no Horror genre
        GenreSpec(movieGenreId = 9648,  tvGenreId = 9648,  label = "Mystery"),
        GenreSpec(movieGenreId = 10749, tvGenreId = null,  label = "Romance"),
        GenreSpec(movieGenreId = 878,   tvGenreId = 10765, label = "Sci-Fi"),    // tv "Sci-Fi & Fantasy"
        GenreSpec(movieGenreId = 53,    tvGenreId = null,  label = "Thriller"),
        GenreSpec(movieGenreId = 10752, tvGenreId = 10768, label = "War"),       // tv "War & Politics"
        GenreSpec(movieGenreId = 37,    tvGenreId = 37,    label = "Western"),
    )

    const val GENRE_CATALOG_PREFIX = "tmdb.genre."

    fun genreMovieCatalogId(movieGenreId: Int): String =
        "$GENRE_CATALOG_PREFIX$movieGenreId.movie"

    fun genreTvCatalogId(tvGenreId: Int): String =
        "$GENRE_CATALOG_PREFIX$tvGenreId.tv"

    /**
     * Parse a genre-catalog id back into (genreId, isMovie). Returns null
     * when the id is not a genre catalog.
     */
    fun parseGenreCatalog(catalogId: String): Pair<Int, Boolean>? {
        if (!catalogId.startsWith(GENRE_CATALOG_PREFIX)) return null
        val tail = catalogId.removePrefix(GENRE_CATALOG_PREFIX)
        val dot = tail.indexOf('.')
        if (dot <= 0) return null
        val genreId = tail.substring(0, dot).toIntOrNull() ?: return null
        val kind = tail.substring(dot + 1)
        return when (kind) {
            "movie" -> genreId to true
            "tv" -> genreId to false
            else -> null
        }
    }

    // Search catalogs — single id each because TMDB exposes /search/movie
    // and /search/tv. The search query arrives via the catalog extra `search`
    // at fetch time; the catalog id itself is constant.
    const val SEARCH_MOVIE = "tmdb.search.movie"
    const val SEARCH_TV = "tmdb.search.tv"

    // Genre lists exposed via the Discover screen's Genre dropdown.
    // Movies have a few genres TV doesn't (Horror / Thriller / Romance /
    // History) — keeping them in the movie list only lets the dropdown
    // show the right options per content type.
    private val MOVIE_GENRE_NAMES: List<String> = GENRES.map { it.label }
    private val TV_GENRE_NAMES: List<String> = GENRES.filter { it.tvGenreId != null }.map { it.label }

    private fun movie(id: String, name: String, inHome: Boolean = true): CatalogDescriptor =
        CatalogDescriptor(
            type = ContentType.MOVIE,
            rawType = "movie",
            id = id,
            name = name,
            pageSize = TMDB_PAGE_SIZE,
            showInHome = inHome,
            hasExplicitShowInHome = true,
            extra = listOf(
                CatalogExtra(name = "genre", options = MOVIE_GENRE_NAMES),
                CatalogExtra(name = "skip"),
            ),
            extraSupported = listOf("skip", "genre")
        )

    private fun tv(id: String, name: String, inHome: Boolean = true): CatalogDescriptor =
        CatalogDescriptor(
            type = ContentType.SERIES,
            rawType = "series",
            id = id,
            name = name,
            pageSize = TMDB_PAGE_SIZE,
            showInHome = inHome,
            hasExplicitShowInHome = true,
            extra = listOf(
                CatalogExtra(name = "genre", options = TV_GENRE_NAMES),
                CatalogExtra(name = "skip"),
            ),
            extraSupported = listOf("skip", "genre")
        )

    private val PROVIDER_DESCRIPTORS: List<CatalogDescriptor> = PROVIDERS.flatMap { p ->
        listOf(
            movie(providerMovieCatalogId(p.providerId), p.movieLabel, inHome = false),
            tv(providerTvCatalogId(p.providerId), p.tvLabel, inHome = false),
        )
    }

    private val GENRE_DESCRIPTORS: List<CatalogDescriptor> = GENRES.flatMap { g ->
        buildList {
            add(movie(genreMovieCatalogId(g.movieGenreId), "${g.label} Movies", inHome = false))
            // Some genres (Horror) exist only in TMDB's movie namespace; skip
            // the TV side rather than mislabel a different genre's results.
            g.tvGenreId?.let { tvId ->
                add(tv(genreTvCatalogId(tvId), "${g.label} Shows", inHome = false))
            }
        }
    }

    // Search catalogs use the same poster-row layout but the `search` extra
    // marks them as query-driven — Nuvio's search pipeline routes through
    // these descriptors when the user types in the search bar.
    private fun searchDescriptor(id: String, name: String, isMovie: Boolean): CatalogDescriptor =
        CatalogDescriptor(
            type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
            rawType = if (isMovie) "movie" else "series",
            id = id,
            name = name,
            pageSize = TMDB_PAGE_SIZE,
            showInHome = false,
            hasExplicitShowInHome = true,
            extraSupported = listOf("search", "skip")
        )

    val ALL: List<CatalogDescriptor> = listOf(
        // ── Home rows (general discovery; filtered through Riven
        //    /availability so only library content shows) ──────────────
        movie(MOVIE_TRENDING, "Trending Movies"),
        tv(TV_TRENDING, "Trending Shows"),
        movie(MOVIE_POPULAR, "Popular Movies"),
        tv(TV_POPULAR, "Popular Shows"),
        tv(TV_AIRING_TODAY, "Airing Today"),
        tv(TV_ON_THE_AIR, "On The Air"),

        // ── Discover-only (off home by default) ───────────────────────
        movie(MOVIE_TOP_RATED, "Top Rated Movies", inHome = false),
        tv(TV_TOP_RATED, "Top Rated Shows", inHome = false),
        // Now Playing / Upcoming describe theatrical-window content, so
        // they're frequently unavailable through Riven — keep them off
        // home and only surface in Discover where the intent is browsing.
        movie(MOVIE_NOW_PLAYING, "Now Playing", inHome = false),
        movie(MOVIE_UPCOMING, "Upcoming Movies", inHome = false),

        // ── Search (query-driven; surfaced by the search screen) ──────
        searchDescriptor(SEARCH_MOVIE, "Search Movies", isMovie = true),
        searchDescriptor(SEARCH_TV, "Search Shows", isMovie = false),
    ) + PROVIDER_DESCRIPTORS
    // Note: `GENRE_DESCRIPTORS` no longer participates in ALL. Each catalog
    // here advertises a `genre` extra via `extra = [...]`, and the Discover
    // screen's Genre dropdown reads those options and passes the selection
    // through to TmdbCatalogSource as an `extraArgs["genre"]` lookup — so
    // the user picks a base catalog (Trending, Popular, Netflix, etc.) and
    // then filters by genre in one place instead of a wall of "Action
    // Movies" / "Comedy Movies" / etc. duplicates.

    /**
     * TMDB returns up to 20 items per page; matching that here means the
     * catalog row's paging cursor stays in lockstep with the upstream API.
     */
    const val TMDB_PAGE_SIZE = 20
}

/**
 * Prefix for TMDB-native catalog item IDs. The downstream stream/meta
 * code-paths use the prefix to know whether to look up by IMDb id (the
 * traditional Stremio convention) or by TMDB id directly.
 */
const val TMDB_ID_PREFIX = "tmdb:"

/** Build a Nuvio-canonical content id from a TMDB numeric id. */
fun tmdbContentId(tmdbId: Long, type: ContentType): String =
    "$TMDB_ID_PREFIX${type.toApiString().lowercase()}:$tmdbId"

/**
 * Parses a Nuvio content id of the form `tmdb:movie:12345` or
 * `tmdb:series:67890` into its parts. Returns null when the id does not
 * match (i.e. it's an IMDb-style `tt12345` from a Stremio addon).
 */
data class TmdbContentRef(val type: ContentType, val tmdbId: Long)

fun parseTmdbContentId(id: String): TmdbContentRef? {
    if (!id.startsWith(TMDB_ID_PREFIX)) return null
    val rest = id.removePrefix(TMDB_ID_PREFIX).split(':')
    if (rest.size != 2) return null
    val tmdbId = rest[1].toLongOrNull() ?: return null
    val type = when (rest[0].lowercase()) {
        "movie" -> ContentType.MOVIE
        "series", "tv" -> ContentType.SERIES
        else -> return null
    }
    return TmdbContentRef(type, tmdbId)
}
