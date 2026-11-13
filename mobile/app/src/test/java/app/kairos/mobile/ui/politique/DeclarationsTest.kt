package app.kairos.mobile.ui.politique

import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.ui.politique.kit.NEUTRAL_LABEL
import app.kairos.mobile.ui.politique.kit.quoted
import app.kairos.mobile.ui.politique.kit.shareText
import app.kairos.mobile.ui.politique.kit.verdictLabel
import app.kairos.mobile.ui.politique.model.LIST_KEY
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.model.PolPage
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.model.mergePages
import app.kairos.mobile.ui.politique.screens.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class DeclarationsTest {
    private fun obj(s: String) = PolJson.parseToJsonElement(s).jsonObject

    private val identities = """{"$LIST_KEY": [
        {"id":"a","type":"personne","nom":"Candidat A","parti":"Parti Bleu"},
        {"id":"b","type":"personne","nom":"Candidate B","parti":"Parti Vert"},
        {"id":"parti-bleu","type":"parti","nom":"Parti Bleu","parti":"Parti Bleu"},
        {"id":"parti-vert","type":"parti","nom":"Parti Vert","parti":"Parti Vert"}
    ]}"""

    // 0 gouvernement exact (A, parti par sa fiche), 1 opposition justice (B, parti explicite), 2 gouvernement
    // auto-contradiction (nom libre), 3 opposition document (B), 4 sans date, 5 sans citation (écarté), 6 invalide.
    private val decls = """{"DECLARATIONS": [
        {"id":"d0","qui":"a","fonction":"Ministre fictif","pouvoir":"gouvernement","date":"2026-01-10","citation":"Phrase 0","verdict":"exact"},
        {"id":"d1","qui":"b","parti":"parti-vert","pouvoir":"opposition","date":"2026-03-01","citation":"Phrase 1","verdict":"contredit-justice"},
        {"id":"d2","qui":"Personne Exemple","pouvoir":"gouvernement","date":"2026-03-01","citation":"Phrase 2","verdict":"auto-contradiction",
         "source":["Média fictif","https://example.org/v","12:30–12:45"],"preuves":[["Rapport fictif","https://example.org/r","ev"],["Page","http://example.org/p"]]},
        {"id":"d3","qui":"b","pouvoir":"opposition","date":"2025-06-01","citation":"« Phrase 3 »","verdict":"contredit-document","reponse":"Je maintiens."},
        {"id":"d4","qui":"a","pouvoir":"autre","citation":"Phrase 4","verdict":"bizarre"},
        {"id":"d5","qui":"a","pouvoir":"gouvernement","date":"2026-05-01","citation":" ","verdict":"exact"},
        42
    ]}"""

    private val data = PolData(mapOf("identites" to obj(identities), "declarations" to obj(decls)))

    @Test fun verdict_strength_order() {
        // Du plus objectif au moins : auto-contradiction, justice, document, preuve ; puis exact, non vérifiable.
        assertEquals(listOf("auto-contradiction", "contredit-justice", "contredit-document", "contredit-preuve", "exact", "non-verifiable"), DECL_VERDICTS)
        assertTrue(DECL_VERDICTS.zipWithNext().all { (x, y) -> verdictStrength(x) < verdictStrength(y) })
        assertTrue(verdictStrength("inconnu") > verdictStrength("non-verifiable"))
        // Chaque verdict a son mot ; inconnu : libellé neutre.
        DECL_VERDICTS.forEach { assertNotEquals(it, NEUTRAL_LABEL, verdictLabel(it)) }
        assertEquals("Contredit par la justice", verdictLabel("contredit-justice"))
        assertEquals(NEUTRAL_LABEL, verdictLabel("bizarre"))
        // Les filtres de verdict suivent cet ordre, seulement ceux présents.
        assertEquals(listOf("auto-contradiction", "contredit-justice", "contredit-document", "exact"), declState(data).verdicts)
    }

    @Test fun declarations_sorted_and_filtered() {
        val st = declState(data)
        // Date décroissante ; même date : verdict le plus solide d'abord ; sans date à la fin ; sans citation écartée.
        assertEquals(listOf("d2", "d1", "d0", "d3", "d4"), st.list.map { it.id })
        // Camps : parti explicite, sinon celui de la fiche ; ordre alphabétique ; nom libre : aucun camp.
        assertEquals(listOf("parti-bleu", "parti-vert"), st.camps)
        assertEquals(listOf(null, "parti-vert", "parti-bleu", "parti-vert", "parti-bleu"), st.list.map { campOf(data, it) })
        assertEquals(listOf(0, 1, 2, 3, 4), declView(st, DeclFilter()))
        assertEquals(listOf(0, 2), declView(st, DeclFilter(pouvoir = "gouvernement")))
        assertEquals(listOf("d2", "d0"), declView(st, DeclFilter(pouvoir = "gouvernement")).map { st.list[it].id })
        assertEquals(listOf("d1", "d3"), declView(st, DeclFilter(pouvoir = "opposition")).map { st.list[it].id })
        assertEquals(listOf("d1", "d3"), declView(st, DeclFilter(camp = "parti-vert")).map { st.list[it].id })
        assertEquals(listOf("d3"), declView(st, DeclFilter(pouvoir = "opposition", verdict = "contredit-document")).map { st.list[it].id })
        assertTrue(declView(st, DeclFilter(pouvoir = "gouvernement", camp = "parti-vert")).isEmpty())
        // Qui : identité reconnue, sinon le nom tel que publié.
        assertEquals(listOf(null, "b", "a", "b", "a"), st.list.map { whoOf(data, it) })
        assertTrue(declState(PolData(emptyMap())).list.isEmpty())
    }

    @Test fun government_flagged() {
        val st = declState(data)
        assertEquals(listOf(true, false, true, false, false), st.list.map { isGovernment(it) })
        assertTrue(isGovernment(Declaration(pouvoir = " Gouvernement ")))
        assertFalse(isGovernment(Declaration(pouvoir = "")))
        // Même grille : la parole du gouvernement n'a ni barème ni ordre à part.
        val gov = st.list.filter { isGovernment(it) }.map { verdictStrength(it.verdict) }
        assertEquals(listOf(0, 4), gov)
        // Carte à table : marquée, citation non doublée de guillemets, faits et réponse lus.
        val card = declTableCard(data, st.list[0])
        assertEquals("Personne Exemple", card.title)
        assertTrue(card.kicker.startsWith("Gouvernement"))
        assertEquals("auto-contradiction", card.verdict)
        val opp = declTableCard(data, st.list[3])
        assertFalse(opp.kicker.contains("Gouvernement"))
        assertTrue(opp.lines.any { it.contains("Je maintiens.") })
        assertEquals("« Phrase 3 »", quoted(opp.quote!!))
        assertTrue(shareText(opp).contains("« Phrase 3 »") && !shareText(opp).contains("« « "))
    }

    @Test fun sources_named_https_only() {
        val d2 = declState(data).list[0]
        // Source de la citation : minutage lu dans le libellé, jamais pris pour un évaluateur.
        assertEquals(SourceRef("Média fictif · 12:30–12:45", "https://example.org/v"), citationSource(d2))
        assertNull(citationSource(Declaration()))
        assertEquals(listOf("Rapport fictif", "Page"), preuvesOf(d2).map { it.label })
        assertEquals("ev", preuvesOf(d2)[0].ev)
        assertEquals("« a »", quoted("a"))
        assertEquals("« a » dit-il", quoted("« a » dit-il"))
    }

    @Test fun home_tile_counts_and_marks() {
        assertEquals("5 déclarations", RUBRICS.single { it.id == "declarations" }.count(data))
        assertEquals("Paroles publiques", rubricTitle("declarations"))
        assertEquals(listOf(Ink.Fragile, Ink.Fragile, Ink.Solide, Ink.Fragile, Ink.Muted), tileViz(data, "declarations")!!.marks.map { it.ink })
        assertEquals(MarkShape.Hollow, tileViz(data, "declarations")!!.marks.last().shape)
    }

    @Test fun identity_links_include_declarations() {
        assertEquals(listOf("d0", "d4"), identityLinks(data, "a").decls.map { it.id })
        // Le parti voit les paroles de ses membres.
        assertEquals(listOf("d1", "d3"), identityLinks(data, "parti-vert").decls.map { it.id })
        assertEquals(2, identityLinks(data, "parti-vert").size)
    }

    @Test fun contract_sample_decodes() {
        val page = KairosJson.decodeFromString(PolPage.serializer(), javaClass.getResource("/politique/fichier-declarations.json")!!.readText())
        val d = PolData(mapOf("declarations" to mergePages(listOf(page))))
        val list = declState(d).list
        assertEquals(3, list.size)
        assertEquals("b-2026-03-10", list[0].id)
        assertTrue(list.any { !it.reponse.isNullOrBlank() && !it.procede.isNullOrBlank() })
    }
}
