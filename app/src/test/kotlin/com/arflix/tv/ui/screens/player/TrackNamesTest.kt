package com.arflix.tv.ui.screens.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackNamesTest {
    @Test
    fun plainVlcTrackBecomesTheLanguage() {
        assertThat(TrackNames.labels(listOf("Track 1 - [Japanese]"))).containsExactly("Japanese")
    }

    @Test
    fun descriptionThatIsJustTheLanguageIsNotRepeated() {
        assertThat(TrackNames.labels(listOf("Track 1 - English - [English]"))).containsExactly("English")
    }

    @Test
    fun extraDescriptionIsKeptNextToTheLanguage() {
        assertThat(TrackNames.labels(listOf("Track 2 - SDH - [English]"))).containsExactly("English (SDH)")
        assertThat(TrackNames.labels(listOf("Track 3 - English Forced - [English]"))).containsExactly("English Forced")
    }

    @Test
    fun noLanguageFallsBackToTheDescriptionOrANumber() {
        assertThat(TrackNames.labels(listOf("Track 1 - Commentary", "Track 2"))).containsExactly("Commentary", "Track 2").inOrder()
    }

    @Test
    fun duplicateLanguagesAreNumbered() {
        assertThat(TrackNames.labels(listOf("Track 1 - [English]", "Track 2 - [English]", "Track 3 - [Japanese]")))
            .containsExactly("English", "English 2", "Japanese").inOrder()
    }
}
