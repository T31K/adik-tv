package com.arflix.tv.stream

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.AEADBadTagException

/** Cross-checks the Kotlin decoder against fixture.bin written by the Mac encoder (adik-curator/adikenc.py). */
class AdikEncFormatTest {
    private val blob: ByteArray = javaClass.getResourceAsStream("/adikenc/fixture.bin")!!.readBytes()
    private val meta = JsonParser.parseString(
        javaClass.getResourceAsStream("/adikenc/fixture.json")!!.readBytes().decodeToString()
    ).asJsonObject
    private val ck = meta["ck_hex"].asString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val h = AdikEncFormat.parseHeader(blob.copyOfRange(0, AdikEncFormat.HEADER_SIZE))

    private fun chunk(i: Int): ByteArray {
        val off = AdikEncFormat.chunkOffset(h, i).toInt()
        return blob.copyOfRange(off, off + AdikEncFormat.chunkCipherLen(h, i))
    }

    @Test
    fun parsesHeader() {
        assertThat(h.chunkSize).isEqualTo(4096)
        assertThat(h.plaintextSize).isEqualTo(10000L)
        assertThat(h.chunkCount).isEqualTo(3)
        assertThat(h.noncePrefix).hasLength(4)
        assertThat(h.movieUuid).hasLength(16)
    }

    @Test
    fun decryptsAllChunksToTheOriginal() {
        val out = (0 until h.chunkCount).flatMap { AdikEncFormat.decryptChunk(ck, h, it, chunk(it)).asList() }.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(out).joinToString("") { "%02x".format(it) }
        assertThat(sha).isEqualTo(meta["plaintext_sha256"].asString)
    }

    @Test
    fun chunkMath() {
        assertThat(AdikEncFormat.chunkOffset(h, 2)).isEqualTo(64L + 2 * (4096 + 16))
        assertThat(AdikEncFormat.chunkCipherLen(h, 2)).isEqualTo(10000 - 2 * 4096 + 16)
        assertThat(AdikEncFormat.chunksFor(h, 4095, 4096)).isEqualTo(0..1)
        assertThat(AdikEncFormat.chunksFor(h, 9999, 9999)).isEqualTo(2..2)
        assertThat(AdikEncFormat.chunksFor(h, 0, 9999)).isEqualTo(0..2)
    }

    @Test
    fun tamperedChunkThrows() {
        val bad = chunk(1).also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { AdikEncFormat.decryptChunk(ck, h, 1, bad) }
    }

    @Test
    fun chunkInWrongPositionThrows() {
        assertThrows(AEADBadTagException::class.java) { AdikEncFormat.decryptChunk(ck, h, 0, chunk(1)) }
    }

    @Test
    fun rejectsNonAdikHeader() {
        assertThrows(IllegalArgumentException::class.java) { AdikEncFormat.parseHeader(ByteArray(64)) }
    }
}
