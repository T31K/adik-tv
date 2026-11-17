package com.arflix.tv.megaflix

import java.io.File

/**
 * Keeps a drive file's extension honest. Torrents ship MKV and MP4 alike, and the
 * feed `path` used to hardcode `.mp4` (curate.py), so MKV releases landed on drives
 * as `*.mp4`. Playback never cared (ExoPlayer sniffs content), but the names lied.
 *
 * The feed `path` is still the identity of a file, matched by BASENAME: a feed path
 * `foo_2025.mkv` finds `foo_2025.mp4` on the drive (and vice versa), so changing a
 * path's extension server-side never makes a TV re-download a movie it already has.
 */
object VideoContainer {

    /** The real extension of a video from its first bytes, or null when unrecognised. */
    fun sniffExtension(header: ByteArray): String? {
        if (header.size >= 4 &&
            header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
            header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
        ) return "mkv" // EBML magic (Matroska)
        if (header.size >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp") return "mp4" // ISO-BMFF
        if (header.size >= 12 &&
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "AVI "
        ) return "avi"
        return null
    }

    fun sniffExtension(file: File): String? = runCatching {
        val buf = ByteArray(12)
        val n = file.inputStream().use { it.read(buf) }
        if (n <= 0) null else sniffExtension(buf.copyOf(n))
    }.getOrNull()

    fun baseName(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    fun withExtension(fileName: String, ext: String): String = "${baseName(fileName)}.$ext"

    /**
     * [fileName] inside [dir]: the exact name first, else any video file with the same
     * basename (`foo.mkv` for `foo.mp4`). Null when neither exists.
     */
    fun findIn(dir: File, fileName: String): File? {
        val exact = File(dir, fileName)
        if (exact.isFile && DriveManager.isVideoFile(exact.name)) return exact
        val base = baseName(fileName)
        return dir.listFiles { f ->
            f.isFile && baseName(f.name) == base && DriveManager.isVideoFile(f.name)
        }?.minByOrNull { it.name }
    }

    /**
     * Rename [file] in place so its extension matches its contents (an MKV named
     * `.mp4` becomes `.mkv`). Returns the file as it now stands — unchanged when the
     * name is already right, the content is unrecognised, the target name is taken,
     * or the rename fails (an intra-dir rename works on the TVs' FAT32 FUSE mount).
     */
    fun normalize(file: File): File {
        val real = sniffExtension(file) ?: return file
        if (file.extension.equals(real, ignoreCase = true)) return file
        val target = File(file.parentFile, withExtension(file.name, real))
        if (target.exists()) return file
        return if (file.renameTo(target)) target else file
    }
}
