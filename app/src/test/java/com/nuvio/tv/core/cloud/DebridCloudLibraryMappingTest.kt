package com.nuvio.tv.core.cloud

import com.nuvio.tv.data.remote.dto.AllDebridFileDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridCloudLibraryMappingTest {
    @Test
    fun `real debrid maps selected files to restricted links in order`() {
        val item = RealDebridTorrentInfoDto(
            id = "rd-1",
            filename = "Show Season 1",
            status = "downloaded",
            files = listOf(
                RealDebridTorrentFileDto(1, "/Show.S01E01.mkv", 100, 1),
                RealDebridTorrentFileDto(2, "/sample.txt", 10, 0),
                RealDebridTorrentFileDto(3, "/Show.S01E02.mp4", 200, 1)
            ),
            links = listOf("https://rd.test/one", "https://rd.test/two")
        ).toRealDebridCloudItem("realdebrid", "Real-Debrid")!!

        assertEquals(listOf("Show.S01E01.mkv", "Show.S01E02.mp4"), item.playableFiles.map { it.name })
        assertEquals("https://rd.test/two", item.playableFiles.last().id)
    }

    @Test
    fun `all debrid flattens nested folders and rejects non-video files`() {
        val item = AllDebridMagnetInfoDto(
            id = 42,
            filename = "Show Pack",
            size = 300,
            hash = "a".repeat(40),
            status = "Ready",
            statusCode = 4,
            links = null,
            files = null
        ).toAllDebridCloudItem(
            files = listOf(
                AllDebridFileDto(
                    name = "Season 1",
                    size = null,
                    link = null,
                    entries = listOf(
                        AllDebridFileDto("Show.S01E01.mkv", 100, "https://ad.test/one", null),
                        AllDebridFileDto("readme.txt", 1, "https://ad.test/readme", null)
                    )
                )
            ),
            providerId = "alldebrid",
            providerName = "AllDebrid"
        )!!

        assertEquals(2, item.files.size)
        assertTrue(item.files.first { it.name.endsWith(".mkv") }.playable)
        assertFalse(item.files.first { it.name.endsWith(".txt") }.playable)
        assertEquals(listOf("Show.S01E01.mkv"), item.playableFiles.map { it.name })
    }

    @Test
    fun `cloud video matching is case insensitive`() {
        assertTrue(isCloudVideoFile("MOVIE.MKV"))
        assertTrue(isCloudVideoFile("no-extension", "video/mp4"))
        assertFalse(isCloudVideoFile("archive.rar"))
    }
}
