package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridFileDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetFilesItemDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetInfoDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class AllDebridCloudLibraryProviderApi @Inject constructor(
    private val api: AllDebridApi
) : CloudLibraryProviderApi {
    override val provider = DebridProviders.AllDebrid

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> =
        runCatching {
            val statusResponse = api.listMagnets(apiKey = apiKey)
            val statusBody = statusResponse.body()
            if (!statusResponse.isSuccessful || statusBody?.status != "success") {
                throw IllegalStateException(
                    statusBody?.error?.message ?: statusResponse.errorBody()?.string().orEmpty().ifBlank { null }
                )
            }
            val magnets = statusBody.data?.magnets.orEmpty()
                .filter { it.id != null && it.statusCode == 4 }
            if (magnets.isEmpty()) return@runCatching emptyList()

            val filesById = mutableMapOf<Long, AllDebridMagnetFilesItemDto>()
            magnets.chunked(100).forEach { chunk ->
                val filesResponse = api.magnetFiles(
                    apiKey = apiKey,
                    magnetIds = chunk.mapNotNull { it.id?.toString() }
                )
                val filesBody = filesResponse.body()
                if (!filesResponse.isSuccessful || filesBody?.status != "success") {
                    throw IllegalStateException(
                        filesBody?.error?.message ?: filesResponse.errorBody()?.string().orEmpty().ifBlank { null }
                    )
                }
                filesBody.data?.magnets.orEmpty().forEach { files ->
                    files.id?.let { filesById[it] = files }
                }
            }
            magnets.mapNotNull { magnet ->
                magnet.toAllDebridCloudItem(
                    files = magnet.id?.let(filesById::get)?.files.orEmpty(),
                    providerId = provider.id,
                    providerName = provider.displayName
                )
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
            val response = api.unlockLink(apiKey = apiKey, link = restrictedLink)
            val body = response.body()
            if (!response.isSuccessful || body?.status != "success") {
                return CloudLibraryPlaybackResult.Failed(body?.error?.message)
            }
            val unlocked = body.data
            val url = unlocked?.link?.takeIf { it.isNotBlank() }
                ?: unlocked?.download?.takeIf { it.isNotBlank() }
                ?: return CloudLibraryPlaybackResult.Failed()
            CloudLibraryPlaybackResult.Success(
                url = url,
                filename = unlocked?.filename?.takeIf { it.isNotBlank() } ?: file.name,
                videoSizeBytes = unlocked?.filesize ?: file.sizeBytes
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CloudLibraryPlaybackResult.Failed(error.message)
        }
    }
}

fun AllDebridMagnetInfoDto.toAllDebridCloudItem(
    files: List<AllDebridFileDto>,
    providerId: String,
    providerName: String
): CloudLibraryItem? {
    val itemId = id?.toString() ?: return null
    val itemName = filename?.trim()?.takeIf { it.isNotBlank() } ?: itemId
    val cloudFiles = files.flattenAllDebridFiles().mapNotNull { file ->
        val name = file.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val link = file.link?.trim()?.takeIf { it.startsWith("http") }
        CloudLibraryFile(
            id = link,
            name = name,
            sizeBytes = file.size,
            playable = link != null && isCloudVideoFile(name)
        )
    }
    return CloudLibraryItem(
        providerId = providerId,
        providerName = providerName,
        id = itemId,
        type = CloudLibraryItemType.Torrent,
        name = itemName,
        status = status,
        sizeBytes = size,
        progressFraction = if (statusCode == 4) 1f else null,
        files = cloudFiles
    )
}

private fun List<AllDebridFileDto>.flattenAllDebridFiles(): List<AllDebridFileDto> =
    flatMap { file ->
        val children = file.entries.orEmpty().flattenAllDebridFiles()
        if (file.link.isNullOrBlank()) children else listOf(file) + children
    }
