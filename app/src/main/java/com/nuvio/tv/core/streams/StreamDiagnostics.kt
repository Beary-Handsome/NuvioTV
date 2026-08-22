package com.nuvio.tv.core.streams

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class StreamDiagnosticStage { SCRAPE, CACHE_CHECK, RESOLVE, VALIDATE, MATCH, PLAYBACK }

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

data class StreamProviderHealth(
    val provider: String,
    val score: Int,
    val successes: Int,
    val failures: Int,
    val averageLatencyMs: Long,
    val lastOutcome: String
)

@Singleton
class StreamDiagnostics @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _events = MutableStateFlow(loadEvents())
    val events: StateFlow<List<StreamProviderDiagnostic>> = _events.asStateFlow()

    private val _providerHealth = MutableStateFlow(calculateHealth(_events.value))
    val providerHealth: StateFlow<List<StreamProviderHealth>> = _providerHealth.asStateFlow()

    fun record(event: StreamProviderDiagnostic) {
        _events.update { current ->
            (listOf(event) + current).take(MAX_EVENTS).also(::persistEvents)
        }
        _providerHealth.value = calculateHealth(_events.value)
    }

    fun clear() {
        _events.value = emptyList()
        _providerHealth.value = emptyList()
        preferences.edit().remove(EVENTS_KEY).apply()
    }

    fun scoreFor(provider: String): Int = providerHealth.value
        .firstOrNull { it.provider.equals(provider, ignoreCase = true) }
        ?.score
        ?: NEUTRAL_SCORE

    private fun calculateHealth(events: List<StreamProviderDiagnostic>): List<StreamProviderHealth> =
        events.groupBy { it.provider }.map { (provider, providerEvents) ->
            val recent = providerEvents.take(HEALTH_EVENT_WINDOW)
            val successes = recent.count { it.isSuccessful() }
            val failures = recent.size - successes
            val averageLatency = recent.map { it.elapsedMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L
            val reliability = if (recent.isEmpty()) 0.5 else successes.toDouble() / recent.size
            val latencyPenalty = (averageLatency / 500L).coerceAtMost(20L).toInt()
            StreamProviderHealth(
                provider = provider,
                score = (reliability * 100).toInt().minus(latencyPenalty).coerceIn(0, 100),
                successes = successes,
                failures = failures,
                averageLatencyMs = averageLatency,
                lastOutcome = recent.firstOrNull()?.outcome.orEmpty()
            )
        }.sortedByDescending { it.score }

    private fun StreamProviderDiagnostic.isSuccessful(): Boolean =
        outcome.lowercase() in SUCCESS_OUTCOMES && (statusCode == null || statusCode in 200..399)

    private fun persistEvents(events: List<StreamProviderDiagnostic>) {
        val encoded = events.joinToString("\n") { event ->
            listOf(
                event.provider,
                event.stage.name,
                event.elapsedMs,
                event.resultCount,
                event.statusCode ?: "",
                event.outcome,
                event.detail.orEmpty(),
                event.timestampMs
            ).joinToString("\t") { it.toString().replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n") }
        }
        preferences.edit().putString(EVENTS_KEY, encoded).apply()
    }

    private fun loadEvents(): List<StreamProviderDiagnostic> = preferences.getString(EVENTS_KEY, null)
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val fields = line.split('\t').map { it.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\") }
            if (fields.size != 8) return@mapNotNull null
            runCatching {
                StreamProviderDiagnostic(
                    provider = fields[0],
                    stage = StreamDiagnosticStage.valueOf(fields[1]),
                    elapsedMs = fields[2].toLong(),
                    resultCount = fields[3].toInt(),
                    statusCode = fields[4].toIntOrNull(),
                    outcome = fields[5],
                    detail = fields[6].ifBlank { null },
                    timestampMs = fields[7].toLong()
                )
            }.getOrNull()
        }.take(MAX_EVENTS).toList()

    private companion object {
        const val PREFERENCES_NAME = "stream_diagnostics"
        const val EVENTS_KEY = "events_v2"
        const val MAX_EVENTS = 200
        const val HEALTH_EVENT_WINDOW = 30
        const val NEUTRAL_SCORE = 50
        val SUCCESS_OUTCOMES = setOf(
            "success", "cached", "valid", "playable", "resolved", "inline_fallback", "playback_success"
        )
    }
}
