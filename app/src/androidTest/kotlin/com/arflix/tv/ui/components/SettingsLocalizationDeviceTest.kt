package com.arflix.tv.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.arflix.tv.R
import com.arflix.tv.util.localizedAppContext
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SettingsLocalizationDeviceTest(private val language: String) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val originalLocale = Locale.getDefault()

    @After fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun languages() = listOf("en", "de", "pt-BR")
    }

    @Test fun formattedMessagesAndUntranslatedLocaleFallbackLoadCorrectly() {
        val context = localizedAppContext(compose.activity, language)
        assertEquals(
            if (language == "de") "Katalog-Paket importieren" else "Import Catalog Pack",
            context.getString(R.string.settings_catalog_pack_import_title)
        )
        assertEquals(
            if (language == "de") "Enthaltene Kataloge (7):" else "Catalogs included (7):",
            context.getString(R.string.settings_catalog_pack_included, 7)
        )
        assertEquals(
            if (language == "de") "Autor: Example • v2.0" else "Author: Example • v2.0",
            context.getString(R.string.settings_catalog_pack_author, "Example", "2.0")
        )
        assertEquals(
            if (language == "de") "Paket-URL eingeben …" else "Enter Paket-URL...",
            context.getString(R.string.settings_input_hint_enter, "Paket-URL")
        )
        assertTrue(context.getString(R.string.settings_activation_visit_instruction, "https://example.test/pin")
            .contains("https://example.test/pin"))
        assertEquals(if (language == "pt-BR") "T2 E3" else "S2 E3",
            context.getString(R.string.player_season_episode_short, 2, 3))
    }
}
