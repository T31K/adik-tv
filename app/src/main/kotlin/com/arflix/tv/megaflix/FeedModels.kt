package com.arflix.tv.megaflix

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class FeedResponseDto(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("offset") val offset: Int = 0,
    @SerializedName("limit") val limit: Int = 100,
    @SerializedName("items") val items: List<FeedItemDto> = emptyList()
)

@Keep
data class FeedItemDto(
    @SerializedName("id") val id: String = "",
    @SerializedName("type") val type: String = "movie",
    @SerializedName("tmdbId") val tmdbId: Int = 0,
    @SerializedName("season") val season: Int? = null,
    @SerializedName("episode") val episode: Int? = null,
    @SerializedName("title") val title: String = "",
    @SerializedName("description") val description: String? = null,
    @SerializedName("link") val link: String = "",
    @SerializedName("path") val path: String = "",
    @SerializedName("sizeBytes") val sizeBytes: Long? = null
)

@Keep
data class RevResponseDto(
    @SerializedName("rev") val rev: String = ""
)
