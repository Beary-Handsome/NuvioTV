package com.nuvio.tv.core.network

import com.nuvio.tv.core.streams.StreamDiagnosticStage
import com.nuvio.tv.core.streams.StreamDiagnostics
import com.nuvio.tv.core.streams.StreamProviderDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class StreamUrlValidation(val playable: Boolean, val statusCode: Int? = null)

@Singleton
class StreamUrlFreshnessValidator @Inject constructor(
    client: OkHttpClient,
    private val diagnostics: StreamDiagnostics
) {
    private val client = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun validate(url: String, headers: Map<String, String>): StreamUrlValidation = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext StreamUrlValidation(playable = true)
        }
        val started = System.currentTimeMillis()
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "stream" }
        val requestBuilder = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }
        val request = requestBuilder.get().header("Range", "bytes=0-0").build()
        val validation = runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code in RANGE_UNSUPPORTED_CODES) {
                    client.newCall(requestBuilder.head().removeHeader("Range").build()).execute().use { head ->
                        StreamUrlValidation(head.code in 200..399, head.code)
                    }
                } else {
                    StreamUrlValidation(response.code in 200..299 || response.code == 416, response.code)
                }
            }
        }.getOrElse { StreamUrlValidation(playable = false) }
        diagnostics.record(
            StreamProviderDiagnostic(
                provider = host,
                stage = StreamDiagnosticStage.VALIDATE,
                elapsedMs = System.currentTimeMillis() - started,
                statusCode = validation.statusCode,
                outcome = if (validation.playable) "playable" else "expired"
            )
        )
        validation
    }

    private companion object {
        val RANGE_UNSUPPORTED_CODES = setOf(400, 405, 501)
    }
}
