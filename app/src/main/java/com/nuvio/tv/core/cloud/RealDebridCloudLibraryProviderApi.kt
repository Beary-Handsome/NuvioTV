package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class RealDebridCloudLibraryProviderApi @Inject constructor(
    private val api: RealDebridApi
) : CloudLibraryProviderApi {
    override val provider = DebridProviders.RealDebrid

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> =
        runCatching {
            val authorization = "Bearer $apiKey"
            val response = api.listTorrents(authorization)
            if (!response.isSuccessful) {
                throw IllegalStateException(response.errorBody()?.string().orEmpty().ifBlank { null })
            }
            val ready = response.body().orEmpty().filter {
                it.id?.isNotBlank() == true && it.status.equals("downloaded", ignoreCase = true)
            }
            val permits = Semaphore(4)
            coroutineScope {
                ready.map { summary ->
                    async {
                        permits.withPermit {
                            val id = summary.id ?: return@withPermit null
                            val detail = api.getTorrentInfo(authorization, id)
                            if (detail.isSuccessful) detail.body()?.toRealDebridCloudItem(
                                providerId = provider.id,
                                providerName = provider.displayName
                            ) else null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    override suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val restrictedLink = file.id?.takeIf { it.startsWith("http") }
            ?: return CloudLibraryPlaybackResult.Failed()
        return try {
            val response = api.unrestrictLink("Bearer $apiKey", restrictedLink)
            if (!response.isSuccessful) {
                return CloudLibraryPlaybackResult.Failed(response.errorBody()?.string())
            }
            val body = response.body()
            val url = body?.download?.takeIf { it.isNotBlank() }
                ?: return CloudLibraryPlaybackResult.Failed()
            CloudLibraryPlaybackResult.Success(
                url = url,
                filename = body.filename?.takeIf { it.isNotBlank() } ?: file.name,
                videoSizeBytes = body.filesize ?: file.sizeBytes
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CloudLibraryPlaybackResult.Failed(error.message)
        }
    }
}

fun RealDebridTorrentInfoDto.toRealDebridCloudItem(
    providerId: String,
    providerName: String
): CloudLibraryItem? {
    val itemId = id?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val itemName = filename?.trim()?.takeIf { it.isNotBlank() }
        ?: originalFilename?.trim()?.takeIf { it.isNotBlank() }
        ?: itemId
    val selectedFiles = files.orEmpty().filter { it.selected == 1 }
    val cloudFiles = selectedFiles.zip(links.orEmpty()).mapNotNull { (file, link) ->
        val name = file.displayName().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CloudLibraryFile(
            id = link.takeIf { it.startsWith("http") },
            name = name,
            sizeBytes = file.bytes,
            playable = link.startsWith("http") && isCloudVideoFile(name)
        )
    }
    return CloudLibraryItem(
        providerId = providerId,
        providerName = providerName,
        id = itemId,
        type = CloudLibraryItemType.Torrent,
        name = itemName,
        status = status,
        sizeBytes = bytes ?: originalBytes,
        progressFraction = progress?.div(100f)?.coerceIn(0f, 1f),
        files = cloudFiles
    )
}

internal fun isCloudVideoFile(name: String, mimeType: String? = null): Boolean {
    if (mimeType?.lowercase()?.startsWith("video/") == true) return true
    return name.substringAfterLast('.', "").lowercase() in cloudVideoExtensions
}

private val cloudVideoExtensions = setOf(
    "3g2", "3gp", "avi", "divx", "flv", "m2ts", "m4v", "mkv", "mov",
    "mp4", "mpeg", "mpg", "mts", "ogm", "ogv", "ts", "webm", "wmv"
)
