package app.kairos.mobile.ui.mimir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirStateTest {
    @Test
    fun accepts_https_link_with_token() {
        assertEquals("https://exemple.org/partage?k=abc123", normalizeMimirLink("https://exemple.org/partage?k=abc123").getOrThrow())
    }

    @Test
    fun keeps_token_in_fragment() {
        // Le jeton reste dans le fragment (#k=...), jamais envoyé au serveur ni journalisé.
        assertEquals("https://exemple.org/entrer#k=abc123", normalizeMimirLink("https://exemple.org/entrer#k=abc123").getOrThrow())
    }

    @Test
    fun strips_spaces_and_line_breaks_from_paste() {
        // Copier-coller : espaces autour, retour à la ligne au milieu.
        val link = normalizeMimirLink("  https://exemple.org/partage\n?k=abc123 \t").getOrThrow()
        assertEquals("https://exemple.org/partage?k=abc123", link)
    }

    @Test
    fun accepts_capitalised_scheme() {
        assertEquals("https://exemple.org/?k=abc", normalizeMimirLink("HTTPS://exemple.org/?k=abc").getOrThrow())
    }

    @Test
    fun rejects_http() {
        val r = normalizeMimirLink("http://exemple.org/partage?k=abc123")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("https://"))
    }

    @Test
    fun rejects_empty() {
        assertTrue(normalizeMimirLink("").isFailure)
        assertTrue(normalizeMimirLink("   \n ").isFailure)
    }

    @Test
    fun rejects_malformed_and_other_schemes() {
        assertTrue(normalizeMimirLink("exemple.org/partage?k=abc").isFailure)
        assertTrue(normalizeMimirLink("https://").isFailure)
        assertTrue(normalizeMimirLink("https:exemple.org").isFailure)
        assertTrue(normalizeMimirLink("https://exemple.org:99999/").isFailure)
        assertTrue(normalizeMimirLink("ftp://exemple.org/").isFailure)
        assertTrue(normalizeMimirLink("javascript:alert(1)").isFailure)
    }

    @Test
    fun error_message_never_repeats_the_token() {
        val r = normalizeMimirLink("http://exemple.org/?k=secret-jeton")
        assertFalse(r.exceptionOrNull()!!.message!!.contains("secret-jeton"))
    }

    @Test
    fun same_host_stays_in_app() {
        val base = "https://exemple.org/partage?k=abc"
        assertEquals(MimirRoute.InApp, mimirRoute(base, "https://exemple.org/autre/page"))
        assertEquals(MimirRoute.InApp, mimirRoute(base, "https://EXEMPLE.org/x"))
        assertEquals(MimirRoute.InApp, mimirRoute(base, "about:blank"))
    }

    @Test
    fun other_hosts_and_schemes_go_outside() {
        val base = "https://exemple.org/partage?k=abc"
        assertEquals(MimirRoute.External, mimirRoute(base, "https://autre.exemple.org/"))
        assertEquals(MimirRoute.External, mimirRoute(base, "https://exemple.net/"))
        assertEquals(MimirRoute.External, mimirRoute(base, "http://exemple.org/"))
        assertEquals(MimirRoute.External, mimirRoute(base, "mailto:contact@example.org"))
        assertEquals(MimirRoute.External, mimirRoute(base, "tel:+33100000000"))
    }
}
