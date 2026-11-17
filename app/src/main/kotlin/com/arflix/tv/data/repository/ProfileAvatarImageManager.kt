package com.arflix.tv.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.arflix.tv.R
import com.arflix.tv.data.model.Profile
import com.arflix.tv.util.ProfileAvatarFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class ImportedProfileAvatar(
    val version: Long,
    val storagePath: String?
)

/** Custom profile photos, stored as local files only (see [ProfileAvatarFiles]). */
@Singleton
class ProfileAvatarImageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun importAvatar(profileId: String, uriString: String): ImportedProfileAvatar =
        withContext(Dispatchers.IO) {
            val version = System.currentTimeMillis()
            val file = ProfileAvatarFiles.localFile(context, profileId, version)
            decodeSquareAvatar(Uri.parse(uriString))?.let { bitmap ->
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)
                }
                bitmap.recycle()
            } ?: throw IllegalArgumentException(context.getString(R.string.avatar_decode_failed))

            ProfileAvatarFiles.cleanupProfile(context, profileId, keepVersion = version)
            ImportedProfileAvatar(version = version, storagePath = null)
        }

    /** Writes [inlineBase64] as the profile's avatar file when the file is missing. */
    suspend fun restoreAvatarIfNeeded(profile: Profile, inlineBase64: String? = null) =
        withContext(Dispatchers.IO) {
            if (profile.avatarImageVersion <= 0L || inlineBase64.isNullOrBlank()) return@withContext
            val file = ProfileAvatarFiles.localFile(context, profile) ?: return@withContext
            if (file.exists() && file.length() > 0L) return@withContext
            try {
                file.writeBytes(Base64.decode(inlineBase64, Base64.NO_WRAP))
                ProfileAvatarFiles.cleanupProfile(context, profile.id, keepVersion = profile.avatarImageVersion)
            } catch (e: Exception) {
                com.arflix.tv.util.AppLogger.e("ProfileAvatar", "Error restoring avatar: ${e.message}")
            }
        }

    fun clearLocalAvatar(profileId: String) {
        ProfileAvatarFiles.cleanupProfile(context, profileId)
    }

    private fun decodeSquareAvatar(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, 1024)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        val side = min(decoded.width, decoded.height)
        val x = max(0, (decoded.width - side) / 2)
        val y = max(0, (decoded.height - side) / 2)
        val cropped = Bitmap.createBitmap(decoded, x, y, side, side)
        if (cropped != decoded) decoded.recycle()

        val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
        if (scaled != cropped) cropped.recycle()
        return scaled
    }

    private fun calculateSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        val halfWidth = width / 2
        val halfHeight = height / 2
        while (halfWidth / sample >= target && halfHeight / sample >= target) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}
