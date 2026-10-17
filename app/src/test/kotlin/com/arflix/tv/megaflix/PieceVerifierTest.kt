package com.arflix.tv.megaflix

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random

class PieceVerifierTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pieceLength = 64 * 1024L

    private fun sha1Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Hashes a whole torrent stream the way a .torrent's piece list does. */
    private fun hashes(stream: ByteArray): List<String> =
        stream.toList().chunked(pieceLength.toInt()).map { sha1Hex(it.toByteArray()) }

    private fun write(name: String, bytes: ByteArray): File = tmp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun intactSingleFileTorrentIsHealthy() {
        val video = Random(1).nextBytes(10 * pieceLength.toInt() + 1234) // partial last piece
        val m = PieceManifest(pieceLength, video.size.toLong(), hashes(video), 0, video.size.toLong())
        val result = PieceVerifier.verify(write("movie.mp4", video), m)
        assertThat(result.healthy).isTrue()
        assertThat(result.checkedPieces).isEqualTo(11)
    }

    @Test
    fun oneFlippedByteIsCaught() {
        val video = Random(2).nextBytes(8 * pieceLength.toInt())
        val m = PieceManifest(pieceLength, video.size.toLong(), hashes(video), 0, video.size.toLong())
        val f = write("movie.mp4", video)
        RandomAccessFile(f, "rw").use { it.seek(5 * pieceLength + 77); it.write(video[(5 * pieceLength + 77).toInt()].toInt() xor 1) }
        val result = PieceVerifier.verify(f, m)
        assertThat(result.healthy).isFalse()
        assertThat(result.badPieces).isEqualTo(1)
    }

    @Test
    fun zeroFilledHoleIsCaught() {
        val video = Random(3).nextBytes(8 * pieceLength.toInt())
        val m = PieceManifest(pieceLength, video.size.toLong(), hashes(video), 0, video.size.toLong())
        val holed = video.copyOf().also { it.fill(0, 2 * pieceLength.toInt(), 5 * pieceLength.toInt()) }
        assertThat(PieceVerifier.verify(write("movie.mp4", holed), m).badPieces).isEqualTo(3)
    }

    @Test
    fun videoInsideMultiFileTorrentSkipsStraddlingPieces() {
        // [sample.txt 1000 B][movie.mp4][poster.jpg 5000 B] — the video doesn't start
        // or end on a piece boundary, so its first and last pieces can't be checked.
        val before = Random(4).nextBytes(1000)
        val video = Random(5).nextBytes(6 * pieceLength.toInt())
        val after = Random(6).nextBytes(5000)
        val stream = before + video + after
        val m = PieceManifest(pieceLength, stream.size.toLong(), hashes(stream), before.size.toLong(), video.size.toLong())
        val result = PieceVerifier.verify(write("movie.mp4", video), m)
        assertThat(result.healthy).isTrue()
        assertThat(result.checkedPieces).isEqualTo(5) // 7 pieces touch the video, 2 straddle
    }

    @Test
    fun wrongSizeIsNotHealthy() {
        val video = Random(7).nextBytes(4 * pieceLength.toInt())
        val m = PieceManifest(pieceLength, video.size.toLong() + 1, hashes(video), 0, video.size.toLong() + 1)
        val result = PieceVerifier.verify(write("movie.mp4", video), m)
        assertThat(result.sizeMatches).isFalse()
        assertThat(result.healthy).isFalse()
    }
}
