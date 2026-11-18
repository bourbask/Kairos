package app.kairos.mobile.ui.politique

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.screens.*
import java.time.ZoneId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Mêmes cas que la validation du module : l'application ne doit ni accepter ce qu'il refuse, ni l'inverse. */
class ReportTest {
    private val claim = Claim(
        id = "phrase-exemple",
        facts = listOf("F1", "F2"),
        src = PolJson.parseToJsonElement("""[["Source A","https://example.com/a"],["Source B","https://example.com/b"]]"""),
    )
    private val data = PolData(emptyMap())
    private val ok = ReportForm(kind = "verdict", what = "x".repeat(30), basis = "lien", url = "https://example.com/a", fix = "y".repeat(10), oath = true)

    @Test fun lengths_count_code_points_after_cleaning() {
        assertEquals(30, reportLength("😀".repeat(30)))
        assertEquals(2, reportLength("  a​b‮  "))
        assertEquals(1, reportLength("é"))
        assertEquals(29, reportLength("x".repeat(29) + "​".repeat(5)))
        assertEquals("a\nb", cleanReport(" a\u0007\nb "))
        assertEquals("abcd", cleanReport("a‮b⁦c؜d"))
        assertEquals("abcd", cleanReport("a​b﻿c⁠d"))
    }

    @Test fun url_rules_match_server() {
        listOf("https://example.com/a", "https://x.fr:443/a", " https://example.fr ", "https://café.fr/x").forEach { assertNull(it, urlProblem(it)) }
        assertEquals("Seules les adresses en https sont acceptées.", urlProblem("http://example.com/a"))
        assertEquals("Indiquez l'adresse de la source.", urlProblem(""))
        assertEquals("L'adresse ne doit pas contenir d'identifiants.", urlProblem("https://a:b@example.com"))
        assertEquals("L'adresse ne doit pas préciser de port.", urlProblem("https://x.fr:8443/a"))
        assertEquals("Cette adresse n'est pas valide.", urlProblem("https://x.fr/a b"))
        listOf(
            "https://192.0.2.1/x", "https://[::1]/x", "https://localhost/x", "https://localhost./x", "https://a.local/x",
            "https://a.internal/x", "https://a.LAN/x", "https://a:b@example.com", "https://x.fr:8443/a", "https://x.fr:/a",
            "https://x.fr/a b", "https://x.fr/" + "a".repeat(300), "https://example.com/a‮b", "https://example.com/a​b",
            "https://-x.fr/", "https://x..fr/", "HTTPS://x.fr/", "",
        ).forEach { assertNotNull(it, urlProblem(it)) }
    }

    @Test fun ready_only_when_every_bound_holds() {
        assertTrue(reportReady(ok, claim, data))
        assertFalse(reportReady(ok.copy(what = "x".repeat(29)), claim, data))
        assertTrue(reportReady(ok.copy(what = "x".repeat(400)), claim, data))
        assertFalse(reportReady(ok.copy(what = "x".repeat(401)), claim, data))
        assertFalse(reportReady(ok.copy(kind = "fait"), claim, data))
        assertFalse(reportReady(ok.copy(kind = "fait", target = "f3"), claim, data))
        assertFalse(reportReady(ok.copy(kind = "fait", target = "f0"), claim, data))
        assertTrue(reportReady(ok.copy(kind = "fait", target = "f2"), claim, data))
        assertFalse(reportReady(ok.copy(kind = "source", target = "f1"), claim, data))
        assertFalse(reportReady(ok.copy(kind = "autre"), claim, data))
        assertFalse(reportReady(ok.copy(basis = "ref", refWho = "A", refWhat = "Titre"), claim, data))
        assertTrue(reportReady(ok.copy(basis = "ref", refWho = "Auteur", refWhat = "Titre"), claim, data))
        assertTrue(reportReady(ok.copy(basis = "carte", refIn = "Passage"), claim, data))
        assertFalse(reportReady(ok.copy(basis = "autre"), claim, data))
        assertFalse(reportReady(ok.copy(oath = false), claim, data))
        assertFalse(reportReady(ok, claim.copy(id = ""), data))
    }

    @Test fun args_carry_only_fields_of_the_chosen_basis() {
        val a = reportArgs(ok.copy(basis = "ref", refWho = " Auteur ", refWhat = "Titre", target = "f1"), "phrase-exemple")
        assertEquals(setOf("claim", "kind", "what", "basis", "ref_who", "ref_when", "ref_what", "fix", "oath", "idem"), a.keys)
        assertEquals("Auteur", a["ref_who"]!!.jsonPrimitive.content)
        val l = reportArgs(ok.copy(kind = "source", target = "s2", url = " https://example.com/a "), "phrase-exemple")
        assertEquals(setOf("claim", "kind", "target", "what", "basis", "url", "fix", "oath", "idem"), l.keys)
        assertEquals("https://example.com/a", l["url"]!!.jsonPrimitive.content)
    }

    @Test fun idem_is_stable_and_server_shaped() {
        val f = ReportForm()
        assertTrue(f.idem.length in 16..64 && f.idem.all { it.isLetterOrDigit() || it == '-' })
        assertEquals(f.idem, f.copy(what = "x").idem)
    }

    @Test fun outcomes_from_server_replies() {
        assertEquals(ReportOutcome.Limited(12), reportOutcome(ApiError.Server(422, "Limite atteinte : réessayer dans 12 minute(s).")))
        assertEquals(ReportOutcome.Closed, reportOutcome(ApiError.Server(422, "Plafond du jour atteint.")))
        assertEquals(ReportOutcome.Closed, reportOutcome(ApiError.Server(422, "signalements non configurés")))
        assertEquals(ReportOutcome.Refused("what : Description de 30 à 400 caractères."), reportOutcome(ApiError.Server(422, "what : Description de 30 à 400 caractères.")))
        assertEquals(ReportOutcome.Failed("Pas de réponse du serveur. Renvoyer garde la même référence."), reportOutcome(ApiError.NoReply))
        assertTrue(reportOutcome(ApiError.Offline) is ReportOutcome.Failed)
    }

    @Test fun quota_follows_receipts_and_limit() {
        val q = ReportQuota().after(ReportOutcome.Received("S-1", 0, 9), 0)
        assertEquals(9, q.left); assertNull(q.waitMinutes(0))
        val l = q.after(ReportOutcome.Limited(12), 1_000)
        assertEquals(12, l.waitMinutes(1_000)); assertEquals(1, l.waitMinutes(1_000 + 11 * 60_000 + 1))
        assertNull(l.waitMinutes(1_000 + 12 * 60_000))
        assertEquals(l, l.after(ReportOutcome.Closed, 5_000))
    }

    @Test fun targets_follow_server_counting() {
        assertEquals(listOf("f1", "f2"), reportTargets(claim, "fait", data).map { it.first })
        assertEquals("Fait 1 : F1", reportTargets(claim, "fait", data)[0].second)
        assertEquals(listOf("s1", "s2"), reportTargets(claim, "source", data).map { it.first })
        assertTrue(reportTargets(claim.copy(src = buildJsonObject { put("url", "https://example.com") }), "source", data).isEmpty())
        assertTrue(reportTargets(claim, "verdict", data).isEmpty())
    }

    @Test fun receipt_line_in_french() =
        assertEquals("Référence S-261118-001, reçu le 18 novembre 2026 à 14:05.", receiptLine("S-261118-001", 1795010700, ZoneId.of("UTC")))
}
