package com.nuvio.tv.core.cloud

import android.content.Context
import com.nuvio.tv.core.debrid.DebridProviders
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class CloudLibraryIndexStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences("cloud_library_index", Context.MODE_PRIVATE)

    fun load(maxAgeMs: Long? = null): CloudLibraryUiState? {
        val savedAt = preferences.getLong(SAVED_AT_KEY, 0L)
        if (savedAt == 0L || maxAgeMs != null && System.currentTimeMillis() - savedAt > maxAgeMs) return null
        val raw = preferences.getString(INDEX_KEY, null) ?: return null
        return runCatching {
            val providers = JSONArray(raw).objects().mapNotNull { providerJson ->
                val provider = DebridProviders.byId(providerJson.getString("providerId")) ?: return@mapNotNull null
                CloudLibraryProviderState(
                    provider = provider,
                    items = providerJson.optJSONArray("items").orEmpty().map { itemJson ->
                        CloudLibraryItem(
                            providerId = provider.id,
                            providerName = provider.displayName,
                            id = itemJson.getString("id"),
                            type = runCatching { CloudLibraryItemType.valueOf(itemJson.getString("type")) }
                                .getOrDefault(CloudLibraryItemType.File),
                            name = itemJson.getString("name"),
                            status = itemJson.optString("status").ifBlank { null },
                            sizeBytes = itemJson.optLongOrNull("sizeBytes"),
                            progressFraction = itemJson.optDoubleOrNull("progress")?.toFloat(),
                            files = itemJson.optJSONArray("files").orEmpty().map { fileJson ->
                                CloudLibraryFile(
                                    id = fileJson.optString("id").ifBlank { null },
                                    name = fileJson.getString("name"),
                                    sizeBytes = fileJson.optLongOrNull("sizeBytes"),
                                    mimeType = fileJson.optString("mimeType").ifBlank { null },
                                    playable = fileJson.optBoolean("playable", true)
                                )
                            }.toList()
                        )
                    }.toList()
                )
            }
            CloudLibraryUiState(isLoaded = true, isEnabled = true, providers = providers.toList())
        }.getOrNull()
    }

    fun save(state: CloudLibraryUiState) {
        val providers = JSONArray()
        state.providers.forEach { providerState ->
            val items = JSONArray()
            providerState.items.forEach { item ->
                val files = JSONArray()
                item.files.forEach { file ->
                    files.put(JSONObject().apply {
                        put("id", file.id ?: "")
                        put("name", file.name)
                        file.sizeBytes?.let { put("sizeBytes", it) }
                        put("mimeType", file.mimeType ?: "")
                        put("playable", file.playable)
                    })
                }
                items.put(JSONObject().apply {
                    put("id", item.id)
                    put("type", item.type.name)
                    put("name", item.name)
                    put("status", item.status ?: "")
                    item.sizeBytes?.let { put("sizeBytes", it) }
                    item.progressFraction?.let { put("progress", it.toDouble()) }
                    put("files", files)
                })
            }
            providers.put(JSONObject().apply {
                put("providerId", providerState.providerId)
                put("items", items)
            })
        }
        preferences.edit()
            .putString(INDEX_KEY, providers.toString())
            .putLong(SAVED_AT_KEY, System.currentTimeMillis())
            .apply()
    }

    private fun JSONArray?.orEmpty(): Sequence<JSONObject> =
        if (this == null) emptySequence() else objects()

    private fun JSONArray.objects(): Sequence<JSONObject> = sequence {
        for (index in 0 until length()) yield(getJSONObject(index))
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        takeIf { has(key) && !isNull(key) }?.optLong(key)

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        takeIf { has(key) && !isNull(key) }?.optDouble(key)

    private companion object {
        const val INDEX_KEY = "index_v1"
        const val SAVED_AT_KEY = "saved_at"
    }
}
