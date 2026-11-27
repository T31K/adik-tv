package com.arflix.tv.stream

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** ADIK Stream routes on main-server (`/adik/stream/...`; base URL is the API host root). */
interface StreamApi {
    @POST("adik/stream/pair")
    suspend fun pair(@Query("k") token: String, @Body body: PairRequestDto): PairResponseDto

    @GET("adik/stream/challenge")
    suspend fun challenge(@Query("k") token: String, @Query("d") deviceId: String): ChallengeDto

    @GET("adik/stream/movies")
    suspend fun movies(
        @Query("k") token: String,
        @Query("d") deviceId: String,
        @Query("nonce") nonce: String,
        @Query("sig") sig: String
    ): StreamMoviesDto

    @POST("adik/stream/play/{id}")
    suspend fun play(@Path("id") id: String, @Query("k") token: String, @Body body: PlayRequestDto): PlayResponseDto
}
