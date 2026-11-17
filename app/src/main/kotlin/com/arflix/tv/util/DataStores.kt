package com.arflix.tv.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings_prefs")

/**
 * Local watchlist (and the pre-v0.2 continue-watching list PlaybackProgressStore imports once).
 * The file keeps its inherited name `trakt_prefs` so existing installs keep their data.
 */
val Context.watchlistDataStore: DataStore<Preferences> by preferencesDataStore(name = "trakt_prefs")
val Context.profilesDataStore: DataStore<Preferences> by preferencesDataStore(name = "profiles_prefs")
val Context.downloadsDataStore: DataStore<Preferences> by preferencesDataStore(name = "megaflix_downloads_prefs")
/** Local playback progress (resume points + watched flags), profile-scoped keys. */
val Context.playbackProgressDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_progress_prefs")
