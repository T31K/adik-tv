package com.arflix.tv.data.repository

import com.arflix.tv.ui.screens.details.DetailsUiState
import com.arflix.tv.ui.screens.settings.SettingsUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodeRatingsPreferenceTest {
    @Test
    fun episodeRatingsAreDisabledInInitialUiStates() {
        assertThat(SettingsUiState().showEpisodeRatings).isFalse()
        assertThat(DetailsUiState().showEpisodeRatings).isFalse()
    }

    @Test
    fun explicitOptInIsCarried() {
        assertThat(SettingsUiState(showEpisodeRatings = true).showEpisodeRatings).isTrue()
        assertThat(DetailsUiState(showEpisodeRatings = true).showEpisodeRatings).isTrue()
    }
}
