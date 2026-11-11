package app.kairos.mobile.ui.mimir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirStateTest {
    @Test
    fun keeps_token_in_fragment() {
        // Le jeton reste dans le fragment (#k=...), jamais envoyé au serveur ni journalisé.
        assertEquals("https://partage.example.org/entrer#k=abc123", normalizeMimirLink("https://partage.example.org/entrer#k=abc123").getOrThrow())
    }

    @Test
    fun rejects_token_in_query() {
        // Ancienne forme : le jeton partirait au serveur et dans ses journaux.
        val r = normalizeMimirLink("https://partage.example.org/partage?k=abc123")
        assertTrue(r.isFailure)
        assertFalse(r.exceptionOrNull()!!.message!!.contains("abc123"))
    }

    @Test
    fun strips_spaces_and_line_breaks_from_paste() {
        // Copier-coller : espaces autour, retour à la ligne au milieu.
        val link = normalizeMimirLink("  https://partage.example.org/entrer\n#k=abc123 \t").getOrThrow()
        assertEquals("https://partage.example.org/entrer#k=abc123", link)
    }

    @Test
    fun accepts_capitalised_scheme() {
        assertEquals("https://example.org/#k=abc", normalizeMimirLink("HTTPS://example.org/#k=abc").getOrThrow())
    }

    @Test
    fun rejects_http() {
        val r = normalizeMimirLink("http://example.org/entrer#k=abc123")
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
        assertTrue(normalizeMimirLink("example.org/entrer#k=abc").isFailure)
        assertTrue(normalizeMimirLink("https://").isFailure)
        assertTrue(normalizeMimirLink("https:example.org").isFailure)
        assertTrue(normalizeMimirLink("https://example.org:99999/").isFailure)
        assertTrue(normalizeMimirLink("ftp://example.org/").isFailure)
        assertTrue(normalizeMimirLink("javascript:alert(1)").isFailure)
    }

    @Test
    fun error_message_never_repeats_the_token() {
        val r = normalizeMimirLink("http://example.org/#k=secret-jeton")
        assertFalse(r.exceptionOrNull()!!.message!!.contains("secret-jeton"))
    }

    @Test
    fun origin_drops_path_fragment_and_credentials() {
        assertEquals("https://partage.example.org/", mimirOrigin("https://partage.example.org/entrer#k=abc"))
        assertEquals("https://example.org:8443/", mimirOrigin("https://u:p@example.org:8443/a/b#k=abc"))
    }

    @Test
    fun full_link_kept_until_session_opens_then_origin_only() {
        val link = "https://partage.example.org/entrer#k=abc"
        assertEquals(link, mimirStoredLink(link, sessionOpen = false))
        assertEquals("https://partage.example.org/", mimirStoredLink(link, sessionOpen = true))
        // Déjà réduit : rien ne change.
        assertEquals("https://partage.example.org/", mimirStoredLink("https://partage.example.org/", sessionOpen = true))
    }

    @Test
    fun same_origin_compares_scheme_host_and_port() {
        assertTrue(sameOrigin("https://example.org/a#k=1", "https://EXAMPLE.org:443/b"))
        assertFalse(sameOrigin("https://example.org/", "https://example.org:8443/"))
        assertFalse(sameOrigin("https://example.org/", "http://example.org/"))
        assertFalse(sameOrigin("https://example.org/", "https://autre.example.org/"))
    }

    @Test
    fun same_origin_stays_in_app() {
        val base = "https://partage.example.org/"
        assertEquals(MimirRoute.InApp, mimirRoute(base, "https://partage.example.org/autre/page"))
        assertEquals(MimirRoute.InApp, mimirRoute(base, "https://PARTAGE.example.org:443/x"))
        assertEquals(MimirRoute.InApp, mimirRoute(base, "about:blank"))
    }

    @Test
    fun other_origins_go_outside() {
        val base = "https://partage.example.org/"
        assertEquals(MimirRoute.External, mimirRoute(base, "https://partage.example.org:8443/"))
        assertEquals(MimirRoute.External, mimirRoute(base, "https://autre.example.org/"))
        assertEquals(MimirRoute.External, mimirRoute(base, "https://example.net/"))
        assertEquals(MimirRoute.External, mimirRoute(base, "http://partage.example.org/"))
        assertEquals(MimirRoute.External, mimirRoute(base, "mailto:contact@example.org"))
        assertEquals(MimirRoute.External, mimirRoute(base, "tel:+33100000000"))
    }

    @Test
    fun engine_and_app_schemes_are_blocked() {
        val base = "https://partage.example.org/"
        assertEquals(MimirRoute.Blocked, mimirRoute(base, "javascript:alert(1)"))
        assertEquals(MimirRoute.Blocked, mimirRoute(base, "JavaScript:alert(1)"))
        assertEquals(MimirRoute.Blocked, mimirRoute(base, "data:text/html,x"))
        assertEquals(MimirRoute.Blocked, mimirRoute(base, "blob:https://partage.example.org/x"))
        assertEquals(MimirRoute.Blocked, mimirRoute(base, "about:srcdoc"))
        assertEquals(MimirRoute.Blocked, mimirRoute(base, "file:///sdcard/x"))
        assertEquals(MimirRoute.Blocked, mimirRoute(base, "intent://x#Intent;end"))
    }
}
