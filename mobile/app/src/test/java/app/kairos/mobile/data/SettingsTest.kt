package app.kairos.mobile.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kairos.mobile.ui.mascot.MascotKind
import app.kairos.mobile.ui.theme.ThemeMode
import app.kairos.mobile.ui.theme.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun capturesHiddenByDefault() {
        assertTrue(Settings.Values().hideFromCaptures)
    }

    @Test
    fun nothing_saved_keeps_current_look() {
        val v = Settings.read(emptyPreferences())
        assertEquals(Settings.Values(), v)
        assertEquals(ThemePalette.Fougere, v.palette)
        assertEquals(MascotKind.Kairos, v.mascot)
        assertEquals(ThemeMode.Clair, v.theme)
    }

    @Test
    fun saved_choice_is_read_back_and_unknown_falls_back() {
        val saved = mutablePreferencesOf(stringPreferencesKey("palette") to "Graphite", stringPreferencesKey("mascotte") to "Tosk")
        assertEquals(ThemePalette.Graphite, Settings.read(saved).palette)
        assertEquals(MascotKind.Tosk, Settings.read(saved).mascot)
        val unknown = mutablePreferencesOf(stringPreferencesKey("palette") to "Inconnue", stringPreferencesKey("mascotte") to "")
        assertEquals(ThemePalette.Fougere, Settings.read(unknown).palette)
        assertEquals(MascotKind.Kairos, Settings.read(unknown).mascot)
    }
}
