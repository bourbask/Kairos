package app.kairos.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.Preferences
import app.kairos.mobile.ui.mascot.MascotKind
import app.kairos.mobile.ui.theme.ThemeMode
import app.kairos.mobile.ui.theme.ThemePalette
import kotlinx.coroutines.flow.first

private val Context.settingsStore by preferencesDataStore("reglages")

/** Réglages de l'application (thème, personnage, lecture vocale, montants masqués, captures), persistés. */
class Settings(private val context: Context) {
    data class Values(
        val theme: ThemeMode = ThemeMode.Clair, val speakReplies: Boolean = false, val amountsMasked: Boolean = false,
        val hideFromCaptures: Boolean = true, val palette: ThemePalette = ThemePalette.Fougere,
        val mascot: MascotKind = MascotKind.Kairos,
    )

    suspend fun load(): Values = read(context.settingsStore.data.first())

    suspend fun saveTheme(mode: ThemeMode) {
        context.settingsStore.edit { it[THEME] = mode.name }
    }

    suspend fun savePalette(palette: ThemePalette) {
        context.settingsStore.edit { it[PALETTE] = palette.name }
    }

    suspend fun saveMascot(mascot: MascotKind) {
        context.settingsStore.edit { it[MASCOT] = mascot.name }
    }

    suspend fun saveSpeakReplies(on: Boolean) {
        context.settingsStore.edit { it[SPEAK] = on }
    }

    suspend fun saveAmountsMasked(on: Boolean) {
        context.settingsStore.edit { it[MASKED] = on }
    }

    suspend fun saveHideFromCaptures(on: Boolean) {
        context.settingsStore.edit { it[SECURE] = on }
    }

    internal companion object {
        /** Valeur absente ou inconnue (version plus récente, nom retiré) : la valeur par défaut. */
        fun read(p: Preferences) = Values(
            theme = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.Clair,
            speakReplies = p[SPEAK] ?: false,
            amountsMasked = p[MASKED] ?: false,
            hideFromCaptures = p[SECURE] ?: true,
            palette = p[PALETTE]?.let { runCatching { ThemePalette.valueOf(it) }.getOrNull() } ?: ThemePalette.Fougere,
            mascot = p[MASCOT]?.let { runCatching { MascotKind.valueOf(it) }.getOrNull() } ?: MascotKind.Kairos,
        )

        val THEME = stringPreferencesKey("theme")
        val SPEAK = booleanPreferencesKey("lecture-reponses")
        val MASKED = booleanPreferencesKey("montants-masques")
        val SECURE = booleanPreferencesKey("masquer-captures")
        val PALETTE = stringPreferencesKey("palette")
        val MASCOT = stringPreferencesKey("mascotte")
    }
}
