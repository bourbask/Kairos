package app.kairos.mobile.data

import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun capturesHiddenByDefault() {
        assertTrue(Settings.Values().hideFromCaptures)
    }
}
