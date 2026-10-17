package com.arflix.tv.megaflix

import java.io.File
import java.io.RandomAccessFile

/**
 * Detects a torrent download that was finalized before all its pieces arrived.
 *
 * libtorrent creates each file at its FULL size up front and fills pieces in as
 * they land, so a partial download already has the right length — only its
 * missing pieces are runs of zero bytes. Compressed video essentially never
 * contains a 4 KB all-zero block, so we sample one every [STEP_BYTES]; a couple
 * of zero samples means holes, and the player stalls the moment it hits one
 * (Grand Budapest Hotel / Mad Max on the dev TV: ~35–40% of samples were zero).
 */
object VideoIntegrity {
    private const val STEP_BYTES = 16L * 1024 * 1024 // one sample per 16 MB
    private const val SAMPLE_BYTES = 4096
    // ≥2, not ≥1: tolerate a single genuine zero-padded region (MKV Void, MP4 free).
    private const val ZERO_SAMPLES_FOR_HOLES = 2

    /** True when [file] has zero-filled regions that were never downloaded. */
    fun hasHoles(file: File): Boolean {
        val length = file.length()
        if (length <= 0L) return true
        val buf = ByteArray(SAMPLE_BYTES)
        var zeroSamples = 0
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            while (offset < length) {
                raf.seek(offset)
                val n = raf.read(buf, 0, minOf(SAMPLE_BYTES.toLong(), length - offset).toInt())
                if (n > 0 && isAllZero(buf, n) && ++zeroSamples >= ZERO_SAMPLES_FOR_HOLES) return true
                offset += STEP_BYTES
            }
        }
        return false
    }

    /** Cache key: a file is re-checked only if its size or mtime changes. */
    fun fingerprint(file: File): String = "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    private fun isAllZero(buf: ByteArray, n: Int): Boolean {
        for (i in 0 until n) if (buf[i].toInt() != 0) return false
        return true
    }
}
