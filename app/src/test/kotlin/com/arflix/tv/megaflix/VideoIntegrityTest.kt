package com.arflix.tv.megaflix

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

class VideoIntegrityTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mb = 1024 * 1024

    /** A file of random (video-like) bytes, with the given MB ranges zeroed out. */
    private fun movie(sizeMb: Int, vararg zeroedMb: IntRange): File {
        val f = tmp.newFile("movie.mp4")
        val rnd = Random(42)
        RandomAccessFile(f, "rw").use { raf ->
            val chunk = ByteArray(mb)
            for (i in 0 until sizeMb) {
                if (zeroedMb.any { i in it }) chunk.fill(0) else rnd.nextBytes(chunk)
                raf.write(chunk)
            }
        }
        return f
    }

    @Test
    fun completeFileHasNoHoles() {
        assertThat(VideoIntegrity.hasHoles(movie(100))).isFalse()
    }

    @Test
    fun partialTorrentWithMissingPiecesHasHoles() {
        // Like the stranded downloads: right length, big unfilled stretch mid-file.
        assertThat(VideoIntegrity.hasHoles(movie(100, 30 until 70))).isTrue()
    }

    @Test
    fun singleZeroPaddedRegionIsTolerated() {
        assertThat(VideoIntegrity.hasHoles(movie(100, 0 until 1))).isFalse()
    }

    @Test
    fun emptyFileCountsAsBroken() {
        assertThat(VideoIntegrity.hasHoles(tmp.newFile("empty.mp4"))).isTrue()
    }

    @Test
    fun fingerprintChangesWhenFileChanges() {
        val f = movie(2)
        val before = VideoIntegrity.fingerprint(f)
        f.appendBytes(ByteArray(10) { 1 })
        assertThat(VideoIntegrity.fingerprint(f)).isNotEqualTo(before)
    }
}
