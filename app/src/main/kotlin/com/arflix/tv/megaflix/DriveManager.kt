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

@Singleton
class DriveManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Root folder to converge with the feed. Order of preference:
     * 1. A test/manual override path stored under settings key "megaflix_drive_override"
     *    (used for emulator verification — adb-push files there).
     * 2. The first *removable* external volume (a plugged-in USB drive).
     * 3. Primary external storage (fallback).
     */
    fun driveRoot(): File? {
        val override = runBlocking {
            context.settingsDataStore.data.first()[stringPreferencesKey("megaflix_drive_override")]
        }
        if (!override.isNullOrBlank()) return File(override)

        // getExternalFilesDirs returns one dir per mounted volume; index > 0 is
        // typically removable (SD/USB). Walk up from the app-specific dir to the
        // volume root where the "Megaflix/..." tree lives.
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        val removable = dirs.getOrNull(1)
        val volumeRoot = removable?.let { volumeRootOf(it) }
        if (volumeRoot != null && volumeRoot.canRead()) return volumeRoot

        return dirs.getOrNull(0)?.let { volumeRootOf(it) }
    }

    /** The single flat folder that holds every video file: <drive>/Megaflix/. */
    fun mediaDir(): File? {
        val root = driveRoot() ?: return null
        return File(root, MEDIA_DIR)
    }

    /** Resolves a feed filename to its file in the flat media folder (may not exist). */
    fun fileFor(fileName: String): File? {
        val dir = mediaDir() ?: return null
        return File(dir, fileName)
    }

    /** Flat model: the feed `path` IS the filename. Returns it if present and a video. */
    fun findPlayableFile(fileName: String): File? =
        fileFor(fileName)?.takeIf { it.isFile && isVideoFile(it.name) }

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
