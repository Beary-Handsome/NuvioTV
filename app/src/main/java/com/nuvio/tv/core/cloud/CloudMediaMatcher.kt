package com.nuvio.tv.core.cloud

data class CloudMediaMatch(
    val item: CloudLibraryItem,
    val file: CloudLibraryFile
)

object CloudMediaMatcher {
    fun findMatches(
        items: List<CloudLibraryItem>,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?
    ): List<CloudMediaMatch> {
        val expectedTitle = title.normalizedMediaTitle()
        if (expectedTitle.isBlank()) return emptyList()
        val isEpisode = season != null && episode != null
        return items.flatMap { item ->
            item.playableFiles.mapNotNull { file ->
                val searchable = "${item.name} ${file.name}"
                if (!searchable.normalizedMediaTitle().contains(expectedTitle)) return@mapNotNull null
                if (isEpisode) {
                    if (!searchable.hasSeasonEpisode(season!!, episode!!)) return@mapNotNull null
                } else if (year != null) {
                    val years = YEAR_REGEX.findAll(searchable).mapNotNull { it.value.toIntOrNull() }.toSet()
                    if (years.isNotEmpty() && years.none { kotlin.math.abs(it - year) <= 1 }) return@mapNotNull null
                }
                CloudMediaMatch(item, file)
            }
        }.distinctBy { "${it.item.providerId}:${it.item.id}:${it.file.stableKey}" }
    }

    private fun String.hasSeasonEpisode(season: Int, episode: Int): Boolean =
        Regex("(?i)(?:s0*$season[ ._-]*e0*$episode(?!\\d)|\\b0*$season[ ._-]*x[ ._-]*0*$episode(?!\\d))")
            .containsMatchIn(this)

    private fun String.normalizedMediaTitle(): String =
        lowercase()
            .replace(YEAR_REGEX, " ")
            .replace(EPISODE_REGEX, " ")
            .replace(QUALITY_REGEX, " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private val YEAR_REGEX = Regex("\\b(?:19|20)\\d{2}\\b")
    private val EPISODE_REGEX = Regex("(?i)\\bs\\d{1,3}[ ._-]*e\\d{1,4}\\b|\\b\\d{1,3}[ ._-]*x[ ._-]*\\d{1,4}\\b")
    private val QUALITY_REGEX = Regex(
        "(?i)\\b(?:2160p|1080p|720p|480p|web[ ._-]*dl|webrip|bluray|brrip|remux|x26[45]|h26[45]|hevc|av1)\\b"
    )
}
