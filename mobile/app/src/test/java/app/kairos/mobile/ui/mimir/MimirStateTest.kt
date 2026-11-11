package app.kairos.mobile.ui.mimir

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.KairosJson
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    @Test
    fun embed_flag_rides_in_fragment() {
        assertEquals("https://partage.example.org/#embed=1", mimirEmbedUrl("https://partage.example.org/"))
        assertEquals("https://partage.example.org/entrer#k=abc&embed=1", mimirEmbedUrl("https://partage.example.org/entrer#k=abc"))
        assertEquals("https://partage.example.org/#embed=1", mimirEmbedUrl("https://partage.example.org/#embed=1"))
    }

    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun default_label_is_dated() {
        assertEquals("Partage du 05/03", defaultShareLabel(LocalDate.of(2027, 3, 5)))
    }

    @Test
    fun label_is_tidied_and_bounded() {
        assertEquals("Partage du 05/03", cleanShareLabel("  Partage \n du   05/03 ").getOrThrow())
        assertTrue(cleanShareLabel("  ").isFailure)
        assertTrue(cleanShareLabel("x".repeat(SHARE_LABEL_MAX + 1)).isFailure)
        assertEquals(SHARE_LABEL_MAX, cleanShareLabel("é".repeat(SHARE_LABEL_MAX)).getOrThrow().length)
    }

    @Test
    fun owner_link_request_is_flagged() {
        // Le module remplace les liens du propriétaire jamais ouverts : le marqueur doit partir.
        assertEquals("""{"libelle":"moi","proprietaire":true}""", shareRequest(OWNER_LABEL, owner = true).toString())
        assertEquals("""{"libelle":"Exemple"}""", shareRequest("Exemple").toString())
    }

    @Test
    fun module_replies_are_read() {
        val link = KairosJson.decodeFromString(ShareLink.serializer(), """{"lien":"https://site.example.org/entrer#k=abc","expire_le":"2027-03-08T10:00:00Z"}""")
        assertEquals("https://site.example.org/entrer#k=abc", link.lien)
        val list = KairosJson.decodeFromString(
            ListSerializer(Share.serializer()),
            """[{"id":2,"libelle":"b","cree_le":"2027-03-05T10:00:00Z","expire_le":"2027-03-08T10:00:00Z","ouvert_le":null,"acces_jusqu_au":null,"appareils":0,"max_appareils":3,"actif":true}]""",
        )
        assertEquals(null, list[0].openedAt)
        assertEquals(3, list[0].maxDevices)
        assertTrue(list[0].actif)
    }

    @Test
    fun status_line_follows_state() {
        val s = Share(1, "a", "2027-03-05T09:00:00Z", "2027-03-08T09:00:00Z", null, true)
        assertEquals("Créé le 05/03 10:00 · à ouvrir avant le 08/03 10:00", shareStatus(s, paris))
        val opened = s.copy(openedAt = "2027-03-06T09:00:00Z", accessUntil = "2027-04-05T09:00:00Z", devices = 1, maxDevices = 3)
        val during = Instant.parse("2027-03-07T00:00:00Z")
        assertEquals(
            "Ouvert sur 1 appareil sur 3 · accès jusqu'au 05/04 11:00 · autre appareil avant le 08/03 10:00",
            shareStatus(opened, paris, during),
        )
        val after = Instant.parse("2027-03-09T00:00:00Z")
        assertEquals("Ouvert sur 1 appareil sur 3 · accès jusqu'au 05/04 11:00", shareStatus(opened, paris, after))
        val full = opened.copy(devices = 3, accessUntil = "2027-04-06T09:00:00Z")
        assertEquals("Ouvert sur 3 appareils sur 3 · accès jusqu'au 06/04 11:00", shareStatus(full, paris, during))
        assertEquals("Inactif · ouvert le 06/03 10:00", shareStatus(opened.copy(actif = false), paris))
        assertEquals("Inactif · jamais ouvert", shareStatus(s.copy(actif = false), paris))
    }

    @Test
    fun older_module_reply_keeps_former_wording() {
        // Module pas encore à jour : ni décompte ni fin d'accès, l'échéance tient lieu de fin d'accès.
        val s = KairosJson.decodeFromString(
            Share.serializer(),
            """{"id":1,"libelle":"a","cree_le":"2027-03-05T09:00:00Z","expire_le":"2027-04-05T09:00:00Z","ouvert_le":"2027-03-06T09:00:00Z","actif":true}""",
        )
        assertEquals("Ouvert le 06/03 10:00 · accès jusqu'au 05/04 11:00", shareStatus(s, paris))
    }

    @Test
    fun active_shares_first_then_newest() {
        val old = Share(1, "a", "2027-03-01T00:00:00Z", "2027-03-04T00:00:00Z", actif = true)
        val newer = old.copy(id = 2, createdAt = "2027-03-02T00:00:00Z")
        val dead = old.copy(id = 3, createdAt = "2027-03-03T00:00:00Z", actif = false)
        assertEquals(listOf(2L, 1L, 3L), sortedShares(listOf(old, dead, newer)).map { it.id })
    }

    @Test
    fun share_issues_are_readable() {
        assertEquals("Serveur injoignable.", shareIssue(ApiError.Offline))
        assertEquals("Partage non configuré sur le serveur.", shareIssue(ApiError.Server(404, "module inconnu")))
        assertEquals("${ApiError.Server.OUTDATED}.", shareIssue(ApiError.Server(404, "x", bare = true)))
        assertEquals("Libellé vide.", shareIssue(ApiError.Server(422, "Libellé vide.")))
    }

    @Test(expected = ApiError.Unauthorized::class)
    fun unauthorized_goes_up() {
        shareIssue(ApiError.Unauthorized)
    }
}
