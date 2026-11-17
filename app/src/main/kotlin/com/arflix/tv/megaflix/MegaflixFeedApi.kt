package com.arflix.tv.megaflix

import retrofit2.http.GET
import retrofit2.http.Query

/** ADIK feed routes on main-server (`/adik/...`; base URL is the API host root). */
interface MegaflixFeedApi {
    @GET("adik/feed.json")
    suspend fun getFeed(
        @Query("k") token: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("d") deviceId: String? = null
    ): FeedResponseDto

    @GET("adik/rev")
    suspend fun getRev(
        @Query("k") token: String,
        @Query("d") deviceId: String? = null
    ): RevResponseDto

    @GET("adik/rows")
    suspend fun getRows(
        @Query("k") token: String,
        @Query("d") deviceId: String? = null
    ): RowsResponseDto
}
