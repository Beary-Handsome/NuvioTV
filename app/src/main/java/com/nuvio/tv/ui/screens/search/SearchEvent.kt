package com.nuvio.tv.ui.screens.search

sealed interface SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent
    data object SubmitSearch : SearchEvent
    /** Moving from the text input to the results confirms the current search (see #2928 review). */
    data object RememberSearchFromTextInput : SearchEvent
    data object ClearRecentSearches : SearchEvent

    data class LoadMoreCatalog(
        val catalogId: String,
        val addonId: String,
        val type: String
    ) : SearchEvent

    data class SelectDiscoverType(val type: String) : SearchEvent
    data class SelectDiscoverCatalog(val catalogKey: String) : SearchEvent
    data class SelectDiscoverGenre(val genre: String?) : SearchEvent
    data class SelectDiscoverYear(val year: Int?) : SearchEvent
    data class SelectDiscoverMinimumRating(val rating: Float?) : SearchEvent
    data class SelectDiscoverLanguage(val language: String?) : SearchEvent
    data object LoadNextDiscoverResults : SearchEvent

    data object Retry : SearchEvent
}
