package com.arflix.tv.megaflix

import retrofit2.http.GET
import retrofit2.http.Query

interface MegaflixFeedApi {
    @GET("feed.json")
    suspend fun getFeed(
        @Query("k") token: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("d") deviceId: String? = null
    ): FeedResponseDto

    @GET("rev")
    suspend fun getRev(
        @Query("k") token: String,
        @Query("d") deviceId: String? = null
    ): RevResponseDto

    @GET("rows")
    suspend fun getRows(
        @Query("k") token: String,
        @Query("d") deviceId: String? = null
    ): RowsResponseDto
}
