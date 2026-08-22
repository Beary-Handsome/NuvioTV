package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.AllDebridEnvelopeDto
import com.nuvio.tv.data.remote.dto.AllDebridInstantDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetUploadDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetStatusDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetListDto
import com.nuvio.tv.data.remote.dto.AllDebridMagnetFilesDto
import com.nuvio.tv.data.remote.dto.AllDebridUnlockDto
import retrofit2.Response
import retrofit2.http.*

/**
 * AllDebrid REST API.
 *
 * All endpoints require `agent` + `apikey` query params. The app passes
 * these via an OkHttp interceptor or directly in each call.
 *
 * API docs: https://docs.alldebrid.com/
 */
interface AllDebridApi {

    @GET("magnet/status")
    suspend fun listMagnets(
        @Query("agent") agent: String = "nuvio",
        @Query("apikey") apiKey: String,
        @Query("status") status: String = "ready"
    ): Response<AllDebridEnvelopeDto<AllDebridMagnetListDto>>

    @POST("https://api.alldebrid.com/v4/magnet/files")
    @FormUrlEncoded
    suspend fun magnetFiles(
        @Query("agent") agent: String = "nuvio",
        @Query("apikey") apiKey: String,
        @Field("id[]") magnetIds: List<String>
    ): Response<AllDebridEnvelopeDto<AllDebridMagnetFilesDto>>

    @GET("user")
    suspend fun getUser(
        @Query("agent") agent: String = "nuvio",
        @Query("apikey") apiKey: String
    ): Response<AllDebridEnvelopeDto<Any>>

    @POST("magnet/instant")
    @FormUrlEncoded
    suspend fun checkInstant(
        @Query("agent") agent: String = "nuvio",
        @Query("apikey") apiKey: String,
        @Field("magnets[]") magnets: List<String>
    ): Response<AllDebridEnvelopeDto<AllDebridInstantDto>>

    @POST("magnet/upload")
    @FormUrlEncoded
    suspend fun uploadMagnet(
        @Query("agent") agent: String = "nuvio",
        @Query("apikey") apiKey: String,
        @Field("magnets[]") magnets: List<String>
    ): Response<AllDebridEnvelopeDto<AllDebridMagnetUploadDto>>

    @GET("magnet/status")
    suspend fun magnetStatus(
        @Query("agent") agent: String = "nuvio",
        @Query("apikey") apiKey: String,
        @Query("id") magnetId: String
    ): Response<AllDebridEnvelopeDto<AllDebridMagnetStatusDto>>

    @POST("link/unlock")
    @FormUrlEncoded
    suspend fun unlockLink(
        @Query("agent") agent: String = "nuvio",
        @Query("apikey") apiKey: String,
        @Field("link") link: String
    ): Response<AllDebridEnvelopeDto<AllDebridUnlockDto>>

    @DELETE("magnet/delete")
    suspend fun deleteMagnet(
        @Query("agent") agent: String = "nuvio",
        @Query("apikey") apiKey: String,
        @Query("id") magnetId: String
    ): Response<AllDebridEnvelopeDto<Any>>
}
