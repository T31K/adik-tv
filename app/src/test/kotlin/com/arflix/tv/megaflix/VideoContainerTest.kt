package com.arflix.tv.megaflix

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoContainerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mkvHeader = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)
    private val mp4Header = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()

    private fun file(name: String, header: ByteArray): File =
        File(tmp.root, name).apply { writeBytes(header + ByteArray(64)) }

    @Test
    fun sniffsMatroskaAndMp4() {
        assertThat(VideoContainer.sniffExtension(mkvHeader)).isEqualTo("mkv")
        assertThat(VideoContainer.sniffExtension(mp4Header)).isEqualTo("mp4")
        assertThat(VideoContainer.sniffExtension("RIFF\u0000\u0000\u0000\u0000AVI ".toByteArray())).isEqualTo("avi")
        assertThat(VideoContainer.sniffExtension(ByteArray(12))).isNull()
    }

    @Test
    fun swapsExtension() {
        assertThat(VideoContainer.withExtension("coco_2017.mp4", "mkv")).isEqualTo("coco_2017.mkv")
        assertThat(VideoContainer.withExtension("noext", "mkv")).isEqualTo("noext.mkv")
    }

    @Test
    fun findsByBasenameWhenExtensionDiffers() {
        val onDrive = file("coco_2017.mp4", mkvHeader)
        assertThat(VideoContainer.findIn(tmp.root, "coco_2017.mkv")).isEqualTo(onDrive)
        assertThat(VideoContainer.findIn(tmp.root, "coco_2017.mp4")).isEqualTo(onDrive)
        assertThat(VideoContainer.findIn(tmp.root, "up_2009.mp4")).isNull()
    }

    @Test
    fun ignoresNonVideoSiblings() {
        file("coco_2017.srt", mkvHeader)
        assertThat(VideoContainer.findIn(tmp.root, "coco_2017.mkv")).isNull()
    }

    @Test
    fun normalizeRenamesMisnamedMkv() {
        val misnamed = file("coco_2017.mp4", mkvHeader)
        val result = VideoContainer.normalize(misnamed)
        assertThat(result.name).isEqualTo("coco_2017.mkv")
        assertThat(result.isFile).isTrue()
        assertThat(misnamed.exists()).isFalse()
    }

    @Test
    fun normalizeLeavesCorrectNamesAlone() {
        val ok = file("up_2009.mp4", mp4Header)
        assertThat(VideoContainer.normalize(ok)).isEqualTo(ok)
        val unknown = file("odd_2000.mp4", ByteArray(12))
        assertThat(VideoContainer.normalize(unknown)).isEqualTo(unknown)
    }

    @Test
    fun normalizeNeverOverwritesAnExistingFile() {
        val misnamed = file("coco_2017.mp4", mkvHeader)
        file("coco_2017.mkv", mkvHeader)
        assertThat(VideoContainer.normalize(misnamed)).isEqualTo(misnamed)
        assertThat(misnamed.exists()).isTrue()
    }
}
