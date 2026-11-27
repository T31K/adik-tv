package com.arflix.tv.ui.screens.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerChromeTest {
    private val movie = PlayerChrome.buttons(isSeries = false)
    private val series = PlayerChrome.buttons(isSeries = true)

    @Test
    fun movieBottomRowIsAudioAndSubtitlesOnly() {
        assertThat(movie).containsExactly(ChromeButton.AUDIO_SUBTITLES)
        assertThat(series).containsExactly(ChromeButton.EPISODES, ChromeButton.AUDIO_SUBTITLES).inOrder()
    }

    @Test
    fun centreZoneOkTogglesAndLeftRightSeek() {
        val s = ChromeFocus()
        assertThat(PlayerChrome.onKey(s, ChromeKey.OK, movie).second).isEqualTo(ChromeAction.TOGGLE_PLAY)
        assertThat(PlayerChrome.onKey(s, ChromeKey.LEFT, movie).second).isEqualTo(ChromeAction.SEEK_BACK)
        assertThat(PlayerChrome.onKey(s, ChromeKey.RIGHT, movie).second).isEqualTo(ChromeAction.SEEK_FORWARD)
    }

    @Test
    fun downMovesToAudioAndSubtitlesAndOkOpensTheMenu() {
        val (down, a) = PlayerChrome.onKey(ChromeFocus(), ChromeKey.DOWN, series)
        assertThat(a).isEqualTo(ChromeAction.NONE)
        assertThat(down.zone).isEqualTo(ChromeZone.BOTTOM)
        assertThat(series[down.button]).isEqualTo(ChromeButton.AUDIO_SUBTITLES)
        assertThat(PlayerChrome.onKey(down, ChromeKey.OK, series).second).isEqualTo(ChromeAction.OPEN_TRACKS)
    }

    @Test
    fun bottomRowLeftRightMoveBetweenButtonsAndClamp() {
        val (down, _) = PlayerChrome.onKey(ChromeFocus(), ChromeKey.DOWN, series)
        val (left, la) = PlayerChrome.onKey(down, ChromeKey.LEFT, series)
        assertThat(la).isEqualTo(ChromeAction.NONE)
        assertThat(series[left.button]).isEqualTo(ChromeButton.EPISODES)
        assertThat(PlayerChrome.onKey(left, ChromeKey.LEFT, series).first.button).isEqualTo(0)
        assertThat(PlayerChrome.onKey(left, ChromeKey.OK, series).second).isEqualTo(ChromeAction.OPEN_EPISODES)
        assertThat(PlayerChrome.onKey(down, ChromeKey.RIGHT, series).first.button).isEqualTo(1)
    }

    @Test
    fun upFromTheBottomRowReturnsToTheCentre() {
        val (down, _) = PlayerChrome.onKey(ChromeFocus(), ChromeKey.DOWN, movie)
        val (up, a) = PlayerChrome.onKey(down, ChromeKey.UP, movie)
        assertThat(up.zone).isEqualTo(ChromeZone.CENTER)
        assertThat(a).isEqualTo(ChromeAction.NONE)
    }
}
