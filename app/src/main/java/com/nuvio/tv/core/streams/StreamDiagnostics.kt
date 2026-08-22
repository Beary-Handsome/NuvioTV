package com.nuvio.tv.core.streams

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class StreamDiagnosticStage { SCRAPE, CACHE_CHECK, RESOLVE, VALIDATE, MATCH }

data class StreamProviderDiagnostic(
    val provider: String,
    val stage: StreamDiagnosticStage,
    val elapsedMs: Long,
    val resultCount: Int = 0,
    val statusCode: Int? = null,
    val outcome: String,
    val detail: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

@Singleton
class StreamDiagnostics @Inject constructor() {
    private val _events = MutableStateFlow<List<StreamProviderDiagnostic>>(emptyList())
    val events: StateFlow<List<StreamProviderDiagnostic>> = _events.asStateFlow()

    fun record(event: StreamProviderDiagnostic) {
        _events.update { current -> (listOf(event) + current).take(MAX_EVENTS) }
    }

    fun clear() { _events.value = emptyList() }

    private companion object { const val MAX_EVENTS = 100 }
}
