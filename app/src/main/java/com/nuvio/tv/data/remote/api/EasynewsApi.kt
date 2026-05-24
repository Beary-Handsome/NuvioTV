package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.EasynewsSearchResponseDto
import retrofit2.Response
import retrofit2.http.*

/**
 * EasyNews search API.
 *
 * EasyNews is a Usenet provider with a keyword-search HTTP API.
 * Unlike torrent-based debrid providers, EN doesn't use infohashes —
 * content is identified by a content hash and retrieved via direct
 * download with HTTP basic auth.
 *
 * Base URL: https://members.easynews.com
 */
interface EasynewsApi {

    @GET("2.0/search/solr-search/advanced")
    suspend fun search(
        @Header("Authorization") auth: String,
        @Query("gps") query: String,
        @Query("pby") perPage: Int = 25,
        @Query("pno") page: Int = 1,
        @Query("safe") safe: Int = 0,
        @Query("u") u: Int = 1,
        @Query("fty[]") fileType: String = "VIDEO",
        @Query("st") searchType: String = "adv",
        @Query("s1") sortBy: String = "dsize",
        @Query("s1d") sortDir: String = "-"
    ): Response<EasynewsSearchResponseDto>
}
