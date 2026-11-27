package com.arflix.tv.stream

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Header of an ADIKENC1 file (64 bytes, big-endian). See docs/superpowers/specs/2026-11-27-adik-stream-r2-encrypted-design.md §4.1. */
class EncHeader(
    val chunkSize: Int,
    val plaintextSize: Long,
    val chunkCount: Int,
    val noncePrefix: ByteArray,
    val movieUuid: ByteArray
)

/**
 * ADIKENC1: one R2 object per movie. Chunk i (AES-256-GCM, 1 MiB plaintext) sits at
 * 64 + i*(chunkSize+16); nonce = prefix(4)||u64(i); AAD = "ADIK1"||uuid||u64(i)||isLast.
 * Tampered, swapped or truncated chunks fail with AEADBadTagException.
 */
object AdikEncFormat {
    const val HEADER_SIZE = 64
    const val TAG = 16
    private val MAGIC = "ADIKENC1".toByteArray(Charsets.US_ASCII)

    fun parseHeader(b: ByteArray): EncHeader {
        require(b.size >= HEADER_SIZE && b.copyOfRange(0, 8).contentEquals(MAGIC)) { "not an ADIKENC1 file" }
        val buf = ByteBuffer.wrap(b, 8, HEADER_SIZE - 8)
        val version = buf.short.toInt()
        require(version == 1) { "unsupported ADIKENC version $version" }
        val chunkSize = buf.int
        val size = buf.long
        val count = buf.int
        val prefix = ByteArray(4).also { buf.get(it) }
        val uuid = ByteArray(16).also { buf.get(it) }
        require(chunkSize > 0 && size > 0 && count.toLong() == (size + chunkSize - 1) / chunkSize) { "bad header" }
        return EncHeader(chunkSize, size, count, prefix, uuid)
    }

    fun chunkOffset(h: EncHeader, i: Int): Long = HEADER_SIZE + i.toLong() * (h.chunkSize + TAG)

    fun chunkPlainLen(h: EncHeader, i: Int): Int =
        minOf(h.chunkSize.toLong(), h.plaintextSize - i.toLong() * h.chunkSize).toInt()

    fun chunkCipherLen(h: EncHeader, i: Int): Int = chunkPlainLen(h, i) + TAG

    /** Chunks covering plaintext bytes [start, endInclusive]. */
    fun chunksFor(h: EncHeader, start: Long, endInclusive: Long): IntRange =
        (start / h.chunkSize).toInt()..(endInclusive / h.chunkSize).toInt()

    fun decryptChunk(ck: ByteArray, h: EncHeader, i: Int, blob: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).put(h.noncePrefix).putLong(i.toLong()).array()
        val aad = ByteBuffer.allocate(5 + 16 + 8 + 1)
            .put("ADIK1".toByteArray(Charsets.US_ASCII)).put(h.movieUuid).putLong(i.toLong())
            .put(if (i == h.chunkCount - 1) 1 else 0).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(ck, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob)
    }
}
