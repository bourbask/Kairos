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
        assertEquals("Adresse de 300 caractères au plus.", urlProblem("https://x.fr/" + "a".repeat(300)))
        assertEquals("Cette adresse n'est pas valide.", urlProblem("https://x..fr/"))
        assertEquals("Indiquez l'adresse publique d'un site (pas une adresse IP ni un nom local).", urlProblem("https://192.0.2.1/x"))
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
        assertEquals(f.idem, f.copy(kind = "fait", target = "").idem)
        val shown = f.copy(what = "texte du lecteur").toString()
        assertFalse("texte du lecteur" in shown || f.idem in shown)
    }

    @Test fun outcomes_from_server_replies() {
        assertEquals(ReportOutcome.Limited(12), reportOutcome(ApiError.Server(422, "Limite atteinte : réessayer dans 12 minute(s).")))
        assertEquals(ReportOutcome.Closed, reportOutcome(ApiError.Server(422, "Plafond du jour atteint.")))
        assertEquals(ReportOutcome.Closed, reportOutcome(ApiError.Server(422, "signalements non configurés")))
        assertEquals(ReportOutcome.Refused("what : Description de 30 à 400 caractères."), reportOutcome(ApiError.Server(422, "what : Description de 30 à 400 caractères.")))
        assertEquals(ReportOutcome.Failed("Pas de réponse du serveur. Renvoyer garde la même référence."), reportOutcome(ApiError.NoReply))
        assertTrue(reportOutcome(ApiError.Offline) is ReportOutcome.Failed)
        assertEquals(ReportOutcome.Refused("Cette affirmation a été retirée ou modifiée depuis l'affichage : rechargez la page."), reportOutcome(ApiError.Server(422, "claim : Affirmation inconnue.")))
        assertEquals(ReportOutcome.Refused("Cet élément n'existe plus dans la carte : rechargez la page."), reportOutcome(ApiError.Server(422, "target : Cible inconnue.")))
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
        val gap = reportTargets(claim.copy(src = PolJson.parseToJsonElement("""[[null,"https://example.com/a"],["Source B","https://example.com/b"]]""")), "source", data)
        assertEquals(listOf("s1", "s2"), gap.map { it.first })
        assertEquals("Source 1", gap[0].second); assertTrue(gap[1].second, "Source B" in gap[1].second)
        val flat = reportTargets(claim.copy(src = PolJson.parseToJsonElement("""["Source A","https://example.com/a"]""")), "source", data)
        assertEquals(listOf("s1"), flat.map { it.first }); assertTrue("Source A" in flat[0].second)
    }

    @Test fun receipt_line_in_french() =
        assertEquals("Référence S-261118-001, reçu le 18 novembre 2026 à 14:05.", receiptLine("S-261118-001", 1795010700, ZoneId.of("UTC")))

    @Test fun counter_shows_what_remains_below_minimum() {
        assertEquals("12 / 400 · encore 18 caractères au moins", counterText(12, 30, 400))
        assertEquals("0 / 400", counterText(0, 30, 400))
        assertEquals("31 / 400", counterText(31, 30, 400))
        assertEquals("29 / 400 · encore 1 caractère au moins", counterText(29, 30, 400))
    }

    @Test fun oversize_body_is_refused_before_sending() {
        assertNull(oversize(reportArgs(ok, "phrase-exemple")))
        assertEquals("Votre texte est trop long une fois encodé : raccourcissez-le un peu.", oversize(buildJsonObject { put("what", "é".repeat(2100)) }))
    }

    @Test fun anonymity_note_agrees_with_remaining_sends() {
        assertTrue(anonymityNote(null).startsWith("Anonyme : ni adresse IP"))
        assertTrue(anonymityNote(null).endsWith(" 10 envois au plus par heure."))
        assertTrue(anonymityNote(1).endsWith(" 1 envoi possible encore cette heure."))
        assertTrue(anonymityNote(0).endsWith(" 0 envoi possible encore cette heure."))
        assertTrue(anonymityNote(9).endsWith(" 9 envois possibles encore cette heure."))
    }

    // Valeurs obtenues avec les fonctions du site (`ref`, `extrait`) sur les mêmes entrées.
    @Test fun refs_match_the_site() {
        val seen = mutableMapOf<String, Int>()
        assertEquals("bilan:ralentissement-de-la-depense-publique", infoRef("bilan", "Ralentissement de la dépense publique", seen))
        assertEquals("votes:loi-immigration-texte-final-cmp", infoRef("votes", "Loi « immigration » : texte <b>final</b> (CMP)", seen))
        assertEquals("votes:loi-immigration-texte-final-cmp:2", infoRef("votes", "Loi « immigration » : texte final (CMP)", seen))
        assertEquals("revirements:jean-luc-exemple-retraites-a-60-ans", infoRef("revirements", "Jean-Luc Exemple Retraites à 60 ans"))
        assertEquals("medias:audience-tf1", infoRef("medias", "audience TF1"))
        assertEquals("argent:finances-les-republicains", infoRef("argent", "finances Les Républicains"))
        assertEquals("budgets:uvre-d-ete-12-5-du-pib", infoRef("budgets", "Œuvre d'Été — 12,5 % du PIB"))
        assertEquals("rhetorique:" + "x".repeat(80), infoRef("rhetorique", "x".repeat(100)))
        assertEquals("camps:a:b:dette-publique", infoRef("camps:a:b", "Dette publique"))
        assertEquals("ofce", infoRef("OFCE"))
        assertEquals("trente-secondes:a_b.c", infoRef("trente-secondes:A_b.c"))
        assertEquals("duel-x:dette", infoRef("Duel X:dette"))
        assertEquals("confiance:3", infoRef("confiance:3"))
        assertEquals("p:element", infoRef("p", ""))
        assertTrue(refOk(infoRef("x".repeat(200))) && infoRef("x".repeat(200)).length == 80)
    }

    @Test fun extrait_is_cleaned_and_bounded_like_the_site() {
        assertEquals("a b c d", infoExtrait("  a\u00a0 b\n\nc <i>d</i> \u200b "))
        val long = infoExtrait("é".repeat(310))
        assertEquals(300, long.codePointCount(0, long.length)); assertTrue(long.endsWith("…"))
        assertEquals("Titre", infoTarget("vote", "votes:x", "  Texte ", " Titre ").titre)
    }

    private val info = infoTarget("bilan", "bilan:emploi", "Emploi : texte affiché", "Emploi")
    private val okInfo = ok.copy(kind = "autre", basis = "carte", refIn = "Passage")

    @Test fun choices_follow_the_target() {
        assertEquals(listOf("verdict", "fait", "source", "formulation", "classement"), reportKinds(ReportTarget.Affirmation("x")).map { it.first })
        assertEquals(listOf("fait", "source", "formulation", "autre"), reportKinds(info).map { it.first })
        assertEquals(listOf("Un chiffre ou un fait", "Une source", "La formulation", "Autre chose"), reportKinds(info).map { it.second })
        assertEquals("Une contradiction dans la carte elle-même", reportBases(ReportTarget.Affirmation("x")).last().second)
        assertEquals("Une contradiction dans l'élément lui-même", reportBases(info).last().second)
        assertEquals(listOf("lien", "ref", "carte"), reportBases(info).map { it.first })
    }

    @Test fun info_ready_only_with_valid_target_and_info_kind() {
        assertTrue(reportReady(okInfo, info))
        assertTrue(reportReady(okInfo.copy(kind = "fait"), info))
        assertFalse(reportReady(okInfo.copy(kind = "verdict"), info))
        assertFalse(reportReady(okInfo.copy(kind = "classement"), info))
        assertFalse(reportReady(okInfo.copy(oath = false), info))
        assertFalse(reportReady(okInfo, info.copy(type = "affirmation")))
        assertFalse(reportReady(okInfo, info.copy(ref = "Bilan:emploi")))
        assertFalse(reportReady(okInfo, info.copy(ref = "-x")))
        assertFalse(reportReady(okInfo, info.copy(ref = "a".repeat(121))))
        assertFalse(reportReady(okInfo, info.copy(extrait = "abc")))
        assertFalse(reportReady(okInfo, info.copy(extrait = "x".repeat(301))))
        assertTrue(infoOk(info.copy(extrait = "x".repeat(300))))
    }

    @Test fun claim_payload_is_unchanged() {
        val f = ok.copy(kind = "fait", target = "f2")
        val a = reportArgs(f, ReportTarget.Affirmation("phrase-exemple"))
        assertEquals(reportArgs(f, "phrase-exemple"), a)
        assertEquals(listOf("claim", "kind", "target", "what", "basis", "url", "fix", "oath", "idem"), a.keys.toList())
        assertFalse(a.keys.any { it.startsWith("cible") || it == "extrait" })
    }

    @Test fun info_payload_carries_target_fields_without_claim() {
        val a = reportArgs(okInfo.copy(target = "f1"), info)
        assertEquals(listOf("cible_type", "cible_ref", "extrait", "kind", "what", "basis", "ref_in", "fix", "oath", "idem"), a.keys.toList())
        assertEquals("bilan", a["cible_type"]!!.jsonPrimitive.content)
        assertEquals("bilan:emploi", a["cible_ref"]!!.jsonPrimitive.content)
        assertEquals("Emploi : texte affiché", a["extrait"]!!.jsonPrimitive.content)
        assertNull(oversize(a))
    }

    @Test fun keys_separate_targets() {
        assertEquals("phrase-exemple", ReportTarget.Affirmation("phrase-exemple").key)
        assertEquals("bilan|bilan:emploi", info.key)
        assertTrue(anonymityNote(null, info = true).contains("l'information visée"))
    }

    @Test fun game_answers_target_the_claim_or_the_round() {
        val m = app.kairos.mobile.ui.politique.jeux.Manche(id = "Phrase-1", q = "Question ?", reponse = app.kairos.mobile.ui.politique.jeux.Reponse(titre = "Titre", texte = "Texte"))
        assertEquals(ReportTarget.Affirmation("Phrase-1"), app.kairos.mobile.ui.politique.jeux.mancheTarget("trente-secondes", "Jeu", m, setOf("Phrase-1")))
        val off = app.kairos.mobile.ui.politique.jeux.mancheTarget("trente-secondes", "Jeu", m, emptySet()) as ReportTarget.Info
        assertEquals("trente-secondes:phrase-1", off.ref)
        val t = app.kairos.mobile.ui.politique.jeux.mancheTarget("qui-la-dit", "Qui l'a dit ?", m, setOf("Phrase-1")) as ReportTarget.Info
        assertEquals(listOf("manche", "qui-la-dit:phrase-1", "Question ? · Titre · Texte", "Qui l'a dit ? · Titre"), listOf(t.type, t.ref, t.extrait, t.titre))
        assertTrue(infoOk(t))
    }

    @Test fun vote_refs_share_one_counter_like_the_site() {
        val r = voteRefs(
            listOf(VoteRow(t = "Loi A"), VoteRow(t = "Loi B")), listOf(VoteRow(t = "Loi A")),
            listOf(CohGroup("Camp", listOf(listOf("ecart", "Loi A")))),
        )
        assertEquals(listOf("votes:loi-a", "votes:loi-b"), r.an)
        assertEquals(listOf("votes:loi-a:2"), r.of("PE"))
        assertEquals(listOf("votes:camp-loi-a"), r.coh["Camp"])
        assertEquals(listOf("revirements:p-s", "revirements:p-s:2"), turnRefs(listOf(Turn(p = "P", s = "S"), Turn(p = "P", s = "S"))))
    }

    @Test fun duel_blocks_carry_both_sides() {
        assertEquals("parti" to "partis:a:b:camp-et-classement", duelBlockSig(app.kairos.mobile.ui.politique.model.IdKind.Parti, "a", "b", "Camp et classement"))
        assertEquals("camp" to "camps:x:y:elus", duelBlockSig(app.kairos.mobile.ui.politique.model.IdKind.Camp, "x", "y", "Élus"))
        assertNull(duelBlockSig(app.kairos.mobile.ui.politique.model.IdKind.Personne, "x", "y", "Élus"))
        assertEquals("Création : 2016 depuis", lineText(Kv("Création", "2016", "depuis")))
    }
}
