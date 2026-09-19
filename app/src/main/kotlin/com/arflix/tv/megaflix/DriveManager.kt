package com.arflix.tv.megaflix

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locates the USB drive's flat "Megaflix/" media folder on the TV.
 *
 * USB drives on Android TV mount unpredictably (getExternalFilesDirs often omits them),
 * so instead of guessing one path we SCAN every plausible mount point for a "Megaflix/"
 * folder and use the one that exists. Requires All-files access to read /storage/<uuid>.
 */
@Singleton
class DriveManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Every candidate "<mount>/Megaflix" folder across all known mount schemes. */
    private fun candidateMediaDirs(): List<File> {
        val roots = LinkedHashSet<File>()

        // 1. Manual/test override (settings key) — takes priority.
        val override = runCatching {
            runBlocking { context.settingsDataStore.data.first()[stringPreferencesKey("megaflix_drive_override")] }
        }.getOrNull()
        if (!override.isNullOrBlank()) roots += File(override)

        // 2. Volume roots derived from app-specific external dirs (internal + any shared volumes).
        runCatching { context.getExternalFilesDirs(null).filterNotNull() }.getOrDefault(emptyList())
            .forEach { roots += volumeRootOf(it) }

        // 3. Mounted volumes under /storage (USB drives usually appear as /storage/XXXX-XXXX).
        runCatching { File("/storage").listFiles() }.getOrNull()?.forEach {
            if (it.isDirectory && it.name !in setOf("emulated", "self", "enc_emulated")) roots += it
        }

        // 4. /mnt/media_rw/<uuid> (some devices expose USB here).
        runCatching { File("/mnt/media_rw").listFiles() }.getOrNull()?.forEach {
            if (it.isDirectory) roots += it
        }

        return roots.map { File(it, MEDIA_DIR) }
    }

    /** The Megaflix/ folder that actually exists on a drive (for reads), else the best guess. */
    fun mediaDir(): File? {
        val candidates = candidateMediaDirs()
        return candidates.firstOrNull { it.isDirectory } ?: candidates.firstOrNull()
    }

    /** Root of the drive that holds the Megaflix/ folder (its parent). */
    fun driveRoot(): File? = mediaDir()?.parentFile

    fun fileFor(fileName: String): File? {
        val dir = mediaDir() ?: return null
        return File(dir, fileName)
    }

    /**
     * Flat model: the feed `path` IS the filename. Scans every candidate Megaflix/ folder
     * for it and returns the first match that's a readable video file.
     */
    fun findPlayableFile(fileName: String): File? {
        candidateMediaDirs().forEach { dir ->
            val f = File(dir, fileName)
            if (f.isFile && isVideoFile(f.name)) return f
        }
        return null
    }

    private fun volumeRootOf(appDir: File): File {
        // appDir looks like /storage/XXXX-XXXX/Android/data/<pkg>/files — climb to
        // /storage/XXXX-XXXX so we can read a top-level "Megaflix/" folder.
        var f: File = appDir
        repeat(4) { f = f.parentFile ?: return appDir }
        return f
    }

    companion object {
        /** Single flat folder on the drive that holds all Megaflix video files. */
        const val MEDIA_DIR = "Megaflix"
        private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "wmv", "flv")

        fun isVideoFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTS
        }
    }
}
