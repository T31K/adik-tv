package com.arflix.tv.ui.screens.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleFontOptionTest {

    @Test
    fun unknownOrMissingPreferenceFallsBackToSystem() {
        assertThat(SubtitleFontOption.fromPreference(null)).isEqualTo(SubtitleFontOption.SYSTEM)
        assertThat(SubtitleFontOption.fromPreference("Removed font")).isEqualTo(SubtitleFontOption.SYSTEM)
    }

    @Test
    fun cyclesThroughEveryBundledFontAndWrapsToSystem() {
        val sequence = buildList {
            var current = SubtitleFontOption.DefaultPreference
            repeat(SubtitleFontOption.entries.size) {
                current = SubtitleFontOption.nextPreference(current)
                add(current)
            }
        }

        assertThat(sequence).containsExactly(
            "Noto Sans",
            "Atkinson Hyperlegible",
            "Lexend",
            "Roboto Condensed",
            "Nunito Sans",
            "Quicksand",
            "Rubik",
            "Varela Round",
            "System",
        ).inOrder()
    }

    @Test
    fun everyStoredValueRoundTrips() {
        SubtitleFontOption.entries.forEach { option ->
            assertThat(SubtitleFontOption.fromPreference(option.preferenceValue)).isEqualTo(option)
        }
    }

    @Test
    fun previousFromSystemWrapsToVarelaRound() {
        assertThat(SubtitleFontOption.previousPreference("System")).isEqualTo("Varela Round")
    }

    @Test
    fun customFontOverridesEmbeddedAssFontStyling() {
        assertThat(shouldPreserveEmbeddedSubtitleStyles("Nunito Sans", true)).isFalse()
        assertThat(shouldPreserveEmbeddedSubtitleStyles("System", true)).isTrue()
        assertThat(shouldPreserveEmbeddedSubtitleStyles("System", false)).isFalse()
    }
}
