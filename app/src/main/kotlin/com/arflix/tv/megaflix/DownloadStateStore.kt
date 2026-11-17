package com.arflix.tv.megaflix

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.arflix.tv.util.downloadsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val recordsKey = stringPreferencesKey("records")
    private val revKey = stringPreferencesKey("rev")
    private val mapType = object : TypeToken<Map<String, DownloadRecord>>() {}.type

    private fun decode(json: String?): Map<String, DownloadRecord> =
        if (json.isNullOrBlank()) emptyMap()
        else runCatching { gson.fromJson<Map<String, DownloadRecord>>(json, mapType) }.getOrNull() ?: emptyMap()

    suspend fun all(): Map<String, DownloadRecord> =
        decode(context.downloadsDataStore.data.first()[recordsKey])

    fun recordsFlow(): Flow<Map<String, DownloadRecord>> =
        context.downloadsDataStore.data.map { decode(it[recordsKey]) }

    suspend fun putAll(records: List<DownloadRecord>) {
        context.downloadsDataStore.edit { prefs ->
            val merged = decode(prefs[recordsKey]).toMutableMap()
            records.forEach { merged[it.id] = it }
            prefs[recordsKey] = gson.toJson(merged, mapType)
        }
    }

    suspend fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        context.downloadsDataStore.edit { prefs ->
            val merged = decode(prefs[recordsKey]).toMutableMap()
            ids.forEach { merged.remove(it) }
            prefs[recordsKey] = gson.toJson(merged, mapType)
        }
    }

    suspend fun setRev(rev: String) {
        context.downloadsDataStore.edit { it[revKey] = rev }
    }

    suspend fun getRev(): String? =
        context.downloadsDataStore.data.first()[revKey]

    // Fingerprints (VideoIntegrity.fingerprint) of READY files already checked for
    // holes, so each movie is scanned once rather than on every launch.
    private val verifiedKey = stringSetPreferencesKey("verified_files")

    suspend fun verifiedFiles(): Set<String> =
        context.downloadsDataStore.data.first()[verifiedKey] ?: emptySet()

    suspend fun setVerifiedFiles(fingerprints: Set<String>) {
        context.downloadsDataStore.edit { it[verifiedKey] = fingerprints }
    }

    // Fingerprints of files that passed the full torrent hash check (PieceVerifier)
    // — the "healthy" flag. A file whose size/mtime changes no longer matches.
    private val healthyKey = stringSetPreferencesKey("healthy_files")

    suspend fun healthyFiles(): Set<String> =
        context.downloadsDataStore.data.first()[healthyKey] ?: emptySet()

    fun healthyFilesFlow(): Flow<Set<String>> =
        context.downloadsDataStore.data.map { it[healthyKey] ?: emptySet() }

    suspend fun markHealthy(fingerprint: String) {
        context.downloadsDataStore.edit { it[healthyKey] = (it[healthyKey] ?: emptySet()) + fingerprint }
    }

    /** A file was renamed: move its hole-scan and hash-check flags to the new fingerprint. */
    suspend fun renameFingerprint(old: String, new: String) {
        context.downloadsDataStore.edit { prefs ->
            for (key in listOf(verifiedKey, healthyKey)) {
                val set = prefs[key] ?: continue
                if (old in set) prefs[key] = set - old + new
            }
        }
    }

    // Which family TV this is (see Constants.MEGAFLIX_DEVICE_IDS); set once
    // per TV from Settings and sent as `d=` on every feed call.
    private val deviceIdKey = stringPreferencesKey("device_id")

    suspend fun setDeviceId(id: String?) {
        context.downloadsDataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(deviceIdKey) else prefs[deviceIdKey] = id
        }
    }

    suspend fun getDeviceId(): String? =
        context.downloadsDataStore.data.first()[deviceIdKey]
}
