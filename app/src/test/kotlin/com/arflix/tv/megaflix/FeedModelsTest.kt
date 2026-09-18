package com.arflix.tv.megaflix

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class FeedModelsTest {
    private val gson = Gson()

    @Test
    fun parsesFeedResponseFromServerJson() {
        val json = """
            {"total":1,"offset":0,"limit":100,"items":[
              {"id":"big-buck-bunny-2008","type":"movie","tmdbId":10378,
               "title":"Big Buck Bunny","description":"A rabbit.",
               "link":"magnet:?xt=urn:btih:abc","path":"Megaflix/Movies/Big Buck Bunny (2008)",
               "sizeBytes":276445467}]}
        """.trimIndent()

        val resp = gson.fromJson(json, FeedResponseDto::class.java)

        assertThat(resp.total).isEqualTo(1)
        assertThat(resp.items).hasSize(1)
        val item = resp.items[0]
        assertThat(item.id).isEqualTo("big-buck-bunny-2008")
        assertThat(item.tmdbId).isEqualTo(10378)
        assertThat(item.season).isNull()
        assertThat(item.sizeBytes).isEqualTo(276445467L)
        assertThat(item.type).isEqualTo("movie")
    }

    @Test
    fun parsesSeriesItemWithSeason() {
        val json = """{"total":1,"offset":0,"limit":100,"items":[
            {"id":"severance-s02","type":"series","tmdbId":95396,"season":2,
             "title":"Severance","description":null,"link":"magnet:?x","path":"Megaflix/TV/Severance/Season 02","sizeBytes":null}]}"""
        val resp = gson.fromJson(json, FeedResponseDto::class.java)
        assertThat(resp.items[0].season).isEqualTo(2)
        assertThat(resp.items[0].sizeBytes).isNull()
    }

    @Test
    fun parsesRev() {
        val rev = gson.fromJson("""{"rev":"203:2026-09-19T02:11:33Z"}""", RevResponseDto::class.java)
        assertThat(rev.rev).isEqualTo("203:2026-09-19T02:11:33Z")
    }
}
