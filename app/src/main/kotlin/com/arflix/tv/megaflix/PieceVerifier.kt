package com.arflix.tv.megaflix

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The slice of a torrent's metadata needed to hash-check ONE video file: the
 * torrent-wide piece layout and SHA-1 piece hashes, plus where the video file
 * sits inside the torrent's byte stream. Plain Kotlin data — built from the
 * native TorrentInfo on libtorrent's alert thread, then used anywhere.
 */
data class PieceManifest(
    val pieceLength: Long,
    val totalSize: Long,          // whole torrent, all files
    val pieceHashes: List<String>, // lowercase hex SHA-1, one per piece
    val fileOffset: Long,         // video file's start within the torrent
    val fileSize: Long
)

data class PieceCheck(
    val checkedPieces: Int,
    val badPieces: Int,
    val sizeMatches: Boolean
) {
    /** Every piece we could check matched, and there was at least one. */
    val healthy: Boolean get() = sizeMatches && checkedPieces > 0 && badPieces == 0
}

/**
 * Thorough integrity check: re-hashes the finished file on disk against the
 * torrent's own SHA-1 piece hashes, so a movie is only flagged healthy when its
 * bytes are exactly what the torrent published (catches missing pieces, a bad
 * USB copy, or anything else that changed the data after download).
 *
 * Pieces that straddle a neighbouring file (only in multi-file torrents — at
 * most the first and last piece of the video) can't be checked once the extras
 * are deleted, so they're skipped; everything else is verified.
 */
object PieceVerifier {
    fun verify(file: File, manifest: PieceManifest): PieceCheck {
        if (file.length() != manifest.fileSize) return PieceCheck(0, 0, sizeMatches = false)
        val fileStart = manifest.fileOffset
        val fileEnd = manifest.fileOffset + manifest.fileSize
        val sha1 = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(manifest.pieceLength.toInt())
        var checked = 0
        var bad = 0
        RandomAccessFile(file, "r").use { raf ->
            val first = (fileStart / manifest.pieceLength).toInt()
            val last = ((fileEnd - 1) / manifest.pieceLength).toInt()
            for (i in first..last) {
                val pieceStart = i * manifest.pieceLength
                val pieceEnd = minOf(pieceStart + manifest.pieceLength, manifest.totalSize)
                if (pieceStart < fileStart || pieceEnd > fileEnd) continue // straddles another file
                val expected = manifest.pieceHashes.getOrNull(i) ?: continue
                val len = (pieceEnd - pieceStart).toInt()
                raf.seek(pieceStart - fileStart)
                raf.readFully(buf, 0, len)
                sha1.reset()
                sha1.update(buf, 0, len)
                if (sha1.digest().toHex() != expected) bad++
                checked++
            }
        }
        return PieceCheck(checked, bad, sizeMatches = true)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
