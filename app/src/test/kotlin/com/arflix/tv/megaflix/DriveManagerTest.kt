package com.arflix.tv.megaflix

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class DriveManagerTest {
    @Test
    fun recognisesVideoExtensions() {
        assertThat(DriveManager.isVideoFile("Movie.mkv")).isTrue()
        assertThat(DriveManager.isVideoFile("Movie.MP4")).isTrue()
        assertThat(DriveManager.isVideoFile("clip.avi")).isTrue()
        assertThat(DriveManager.isVideoFile("subtitles.srt")).isFalse()
        assertThat(DriveManager.isVideoFile("poster.jpg")).isFalse()
        assertThat(DriveManager.isVideoFile("readme")).isFalse()
    }

    @Test
    fun pickPlayableChoosesFirstVideo() {
        val small = File("/x/sample.mp4")
        val big = File("/x/feature.mkv")
        // pickPlayable returns the first video in the caller-sorted list.
        assertThat(DriveManager.pickPlayable(listOf(big, small))).isEqualTo(big)
        assertThat(DriveManager.pickPlayable(listOf(File("/x/a.txt"), small))).isEqualTo(small)
        assertThat(DriveManager.pickPlayable(listOf(File("/x/a.txt")))).isNull()
    }
}
