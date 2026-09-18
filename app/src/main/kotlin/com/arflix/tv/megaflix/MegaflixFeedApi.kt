package com.arflix.tv.megaflix

import retrofit2.http.GET
import retrofit2.http.Query

interface MegaflixFeedApi {
    @GET("feed.json")
    suspend fun getFeed(
        @Query("k") token: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): FeedResponseDto

    @GET("rev")
    suspend fun getRev(
        @Query("k") token: String
    ): RevResponseDto
}
