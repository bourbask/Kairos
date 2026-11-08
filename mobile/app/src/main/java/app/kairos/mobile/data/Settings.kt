package app.kairos.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.kairos.mobile.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first

private val Context.settingsStore by preferencesDataStore("reglages")

/** Réglages de l'application (thème, lecture vocale, montants masqués, captures), persistés. */
class Settings(private val context: Context) {
    data class Values(
        val theme: ThemeMode = ThemeMode.Clair, val speakReplies: Boolean = false, val amountsMasked: Boolean = false,
        val hideFromCaptures: Boolean = true,
    )

    suspend fun load(): Values {
        val p = context.settingsStore.data.first()
        return Values(
            theme = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.Clair,
            speakReplies = p[SPEAK] ?: false,
            amountsMasked = p[MASKED] ?: false,
            hideFromCaptures = p[SECURE] ?: true,
        )
    }

    suspend fun saveTheme(mode: ThemeMode) {
        context.settingsStore.edit { it[THEME] = mode.name }
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

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val SPEAK = booleanPreferencesKey("lecture-reponses")
        val MASKED = booleanPreferencesKey("montants-masques")
        val SECURE = booleanPreferencesKey("masquer-captures")
    }
}
