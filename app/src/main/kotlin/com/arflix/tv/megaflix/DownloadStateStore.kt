package com.arflix.tv.megaflix

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
}
