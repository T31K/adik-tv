package com.arflix.tv.ui.screens.home

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.resolveAppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class HomeProfilePreferences(
    val showBudget: Boolean,
    val clockFormat: String,
    val smoothScrolling: Boolean,
    val contentLanguage: String
)

internal fun readHomeProfilePreferences(
    preferences: Preferences,
    profileId: String
): HomeProfilePreferences {
    val prefix = "profile_${profileId}_"
    return HomeProfilePreferences(
        showBudget = preferences[booleanPreferencesKey("${prefix}show_budget_on_home")] ?: true,
        clockFormat = preferences[stringPreferencesKey("${prefix}clock_format")] ?: "24h",
        smoothScrolling = preferences[booleanPreferencesKey("${prefix}smooth_scrolling")] ?: false,
        contentLanguage = resolveAppLanguage(preferences, profileId)
    )
}

internal fun observeHomeProfilePreferences(
    activeProfileId: Flow<String>,
    preferences: Flow<Preferences>
): Flow<HomeProfilePreferences> = activeProfileId
    .combine(preferences) { profileId, profilePreferences ->
        readHomeProfilePreferences(profilePreferences, profileId)
    }
    .distinctUntilChanged()
