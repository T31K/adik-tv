package com.arflix.tv.megaflix

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DriveManagerTest {
    @Test
    fun recognisesVideoExtensions() {
        assertThat(DriveManager.isVideoFile("game_of_thrones_s6e1.mp4")).isTrue()
        assertThat(DriveManager.isVideoFile("Movie.MP4")).isTrue()
        assertThat(DriveManager.isVideoFile("clip.mkv")).isTrue()
        assertThat(DriveManager.isVideoFile("subtitles.srt")).isFalse()
        assertThat(DriveManager.isVideoFile("poster.jpg")).isFalse()
        assertThat(DriveManager.isVideoFile("readme")).isFalse()
    }
}
