package app.kairos.mobile.ui.politique

import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.data.PolCompare
import app.kairos.mobile.data.PolFiche
import app.kairos.mobile.data.PolHint
import app.kairos.mobile.data.PolHome
import app.kairos.mobile.data.PolRubric
import app.kairos.mobile.data.PolSearch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Réponses réelles du module sur ses fixtures fictives (`resources/politique`, régénérées par
 * le test `contract_samples_match_app_resources` du module) : le décodage et l'affichage
 * suivent le contrat publié, et les indications de présentation passent avant la devinette.
 */
class PolitiqueContractTest {
    private fun <T> sample(name: String, s: DeserializationStrategy<T>): T =
        KairosJson.decodeFromString(s, javaClass.getResource("/politique/$name.json")!!.readText())

    private val home = sample("accueil", PolHome.serializer())
    private val hints = home.presentations

    private fun rubric(id: String) = sample("rubrique-$id", PolRubric.serializer())
    private fun section(r: PolRubric, path: String) = sections(r.contenu).single { it.path == path }

    @Test
    fun home_sample_maps_contract() {
        assertEquals("Données vérifiées le 15 janvier 2026", checkedLabel(home.checkedOn))
        assertEquals(listOf("a", "b", "c", "parti-bleu"), home.identites.map { it.id })
        assertNotNull("avatar valide gardé", avatarOf(home.identites[0].avatar))
        assertNull("avatar invalide retiré par le module", home.identites[2].avatar)
        assertEquals(listOf("budget", "niveau-de-vie"), home.themes.map { it.id })
        assertEquals(6, hints.size)
        assertEquals(PolHint("affirmations", "CLAIMS", "verifications", mapOf("titre" to "q", "verdict" to "v", "faits" to "facts", "source" to "src", "theme" to "theme", "camp" to "camp")), hints[0])
        val cards = homeCards(home)
        assertEquals(
            listOf("Dit / voté", "Déclarations vérifiées", "Argent", "Budgets", "divers", "Niveau de vie", "Rhétorique", "Votes", "Hors de ma bulle"),
            cards.map { it.titre },
        )
        assertEquals("indication avant le nom", RubricKind.Declarations, cards[1].kind)
        val bulle = cards.last()
        assertTrue(bulle.wide)
        assertEquals(RubricKind.Bulle, bulle.kind)
        assertEquals("affirmations", bulle.id)
        assertEquals("indication générique : pas de devinette", RubricKind.Generic, rubricKind("budgets", "Budgets", hints))
        assertEquals(RubricKind.Votes, rubricKind("votes", "Votes", hints))
    }

    @Test
    fun rubric_samples_sections_attachments_and_hints() {
        val aff = rubric("affirmations")
        assertEquals(listOf("verifications", "bulle"), aff.presentation.map { it.vue })
        val claims = section(aff, "CLAIMS")
        assertEquals(listOf("a"), attachedIds(aff, claims.path, 0))
        assertTrue("entrée non rattachée gardée", attachedIds(aff, claims.path, 1).isEmpty())
        val hint = hintFor(aff.presentation, aff.id, claims.path)
        assertEquals("verifications", hint?.vue)
        val v = entryView(claims.entries[0], hint)
        assertEquals("Le prix de l'électricité a doublé.", v.title)
        assertEquals("Exagéré", v.verdict)
        assertEquals("Source fictive", v.source)
        assertEquals("https://example.org/elec", v.url)
        assertEquals(listOf("Faits", "Thème", "Camp"), v.fields.map { it.label })
        assertEquals("Hausse fictive de 40 % en trois ans.", v.fields[0].text)

        val argent = rubric("argent")
        assertEquals(listOf("parti-bleu"), attachedIds(argent, section(argent, "AIDE.rows").path, 0))
        assertEquals(listOf("parti-bleu"), attachedIds(argent, section(argent, "PARTIS").path, 0))
        assertTrue(argent.presentation.isEmpty())

        val budgets = rubric("budgets")
        assertEquals(listOf("a", "parti-bleu"), attachedIds(budgets, "PROGRAMS", 0))
        val program = entryView(section(budgets, "PROGRAMS").entries[1], hintFor(budgets.presentation, "budgets", "PROGRAMS"))
        assertEquals("Mme B", program.title)
        assertEquals("https://example.org/budget-b", program.url)
        assertNull("GAPS sans indication valide : devinette", hintFor(budgets.presentation, "budgets", "GAPS"))
    }

    @Test
    fun rhetoric_card_follows_roles() {
        val r = rubric("rhetorique")
        val s = section(r, "RHET")
        val v = entryView(s.entries.single(), hintFor(r.presentation, r.id, s.path))
        assertEquals("Homme de paille", v.title)
        assertEquals(listOf("Définition", "Le repérer", "Exemple", "La question à poser"), v.fields.map { it.label })
        assertEquals("Est-ce vraiment ce qui a été dit ?", v.fields.last().text)
        assertTrue(v.tables.isEmpty())
    }

    @Test
    fun votes_sample_shows_vote_column() {
        val r = rubric("votes")
        val s = section(r, "AN_V")
        val v = entryView(s.entries.single(), hintFor(r.presentation, r.id, s.path))
        assertEquals("Loi fictive sur les étés", v.title)
        assertEquals(listOf("Date", "A voté"), v.fields.map { it.label })
        assertEquals("P · 10 pour ; C · 5 contre", v.fields[1].text)
        assertEquals(listOf("a", "b"), attachedIds(r, "COH", 0))
    }

    @Test
    fun bulle_follows_theme_and_camp_roles() {
        val aff = rubric("affirmations")
        val hint = aff.presentation.single { it.vue == "bulle" }
        val b = bulle(aff.contenu, hint)!!
        assertEquals(listOf("Énergie", "Santé"), b.map { it.theme })
        assertEquals(listOf("Candidat A"), b[0].camps.map { it.first })
        val claim = entryView(b[0].camps[0].second.single(), hint)
        assertEquals("Le prix de l'électricité a doublé.", claim.title)
        assertEquals("Exagéré", claim.verdict)
        // Noms de champs inattendus : seule l'indication les fait reconnaître.
        val odd = KairosJson.parseToJsonElement("""{"L":[{"s":"Dette","b":"Camp 1","x":"A1"},{"s":"Dette","b":"Camp 2","x":"A2"}]}""")
        assertNull(bulle(odd))
        val n = bulle(odd, PolHint("r", "L", "bulle", mapOf("theme" to "s", "camp" to "b", "titre" to "x")))!!
        assertEquals(listOf("Camp 1", "Camp 2"), n.single().camps.map { it.first })
    }

    @Test
    fun fiche_sample_entries_keep_rubric_and_path() {
        val f = sample("fiche-a", PolFiche.serializer())
        assertEquals("Candidat A", f.identite.nom)
        assertFalse(f.tronque)
        val tabs = ficheTabs(f)
        assertEquals(listOf("affirmations", "budgets", "niveau-de-vie", "votes"), tabs.map { it.key })
        val votes = tabs.single { it.key == "votes" }
        assertEquals(listOf("COH", "DV"), votes.entrees.map { it.chemin })
        // Une entrée de fiche se lit comme dans sa rubrique, avec la même indication.
        val claim = tabs[0].entrees.single()
        assertEquals("Exagéré", entryView(claim.entree, hintFor(hints, tabs[0].key, claim.chemin)).verdict)

        val d = ditVote(f, hints)
        val pair = d.pairs.single()
        assertEquals("Nous protégerons les étés.", pair.said)
        assertEquals("Contre la loi fictive sur les étés", pair.voted)
        assertEquals("https://example.org/dv-a", entryView(pair.rest, pair.hint).url)

        val c = ditVote(sample("fiche-c", PolFiche.serializer()), hints)
        assertTrue(c.pairs.isEmpty() && c.said.isEmpty() && c.voted.isEmpty())
    }

    @Test
    fun compare_samples_keep_not_given_and_sources() {
        val ab = compareTable(sample("comparer-a-b-budget", PolCompare.serializer()), listOf("a", "b"), home.identites)
        assertEquals("Budget des programmes", ab.theme)
        assertEquals(listOf("Candidat A", "Candidate B"), ab.columns.map { it.nom })
        assertEquals(listOf("Déficit (Md€)", "Effet par ménage (€/an)"), ab.rows.map { it.label })
        assertEquals(listOf("3.1 Md€", "4.2 Md€"), ab.rows[0].cells.map { it.text })
        val missing = ab.rows[1].cells[1]
        assertEquals(NOT_GIVEN, missing.text)
        assertEquals("https://example.org/budget-b", missing.url)

        val ac = compareTable(sample("comparer-a-c-niveau-de-vie", PolCompare.serializer()), listOf("a", "c"), home.identites)
        assertEquals("250 €/an", ac.rows[0].cells[0].text)
        assertEquals("Évaluation fictive, example.org", ac.rows[0].cells[0].source)
        assertNull("texte, pas une adresse", ac.rows[0].cells[0].url)
        val absent = ac.rows[0].cells[1]
        assertEquals(NOT_GIVEN, absent.text)
        assertNull(absent.source)
    }

    @Test
    fun search_sample_groups_by_rubric() {
        val s = sample("chercher-electricite", PolSearch.serializer())
        assertFalse(s.tronque)
        val g = boundedResults(s.resultats, hints).single()
        assertEquals("affirmations", g.key)
        assertEquals(RubricKind.Declarations, rubricKind(g.key, g.titre, hints))
        val e = g.entrees.single()
        assertEquals("CLAIMS", e.chemin)
        assertEquals("Le prix de l'électricité a doublé.", entryView(e.entree, hintFor(hints, g.key, e.chemin)).title)
        assertEquals(listOf("a"), identitiesOf(e.entree, home).map { it.id })
    }

    @Test
    fun every_sample_decodes() {
        val all = javaClass.getResource("/politique")!!.let { java.io.File(it.toURI()).list()!!.sorted() }
        assertEquals(11, all.size)
        for (n in all) assertTrue(n, KairosJson.parseToJsonElement(javaClass.getResource("/politique/$n")!!.readText()) is JsonObject)
        assertTrue(rubric("votes").rattachements["AN_G"] is JsonArray)
    }

    @Test
    fun html_in_data_is_never_rendered() {
        assertEquals("Hausse de 40 % & plus", stripTags("Hausse de <b>40 %</b> &amp; plus"))
        assertEquals("lien", stripTags("<a href=\"javascript:x\">lien</a>"))
        assertEquals("a < b", stripTags("a &lt; b"))
        assertEquals("texte coupé", stripTags("texte <i>coupé"))
        val e = KairosJson.parseToJsonElement(
            """{"q":"Le <b>prix</b> a doublé.","v":"<strong>Faux</strong>","facts":["Un <em>fait</em>."],"src":[["Rapport <b>fictif</b>","https://example.org/r"]],"note":"<br/>Autre"}""",
        )
        val v = entryView(e, PolHint("r", "L", "verifications", mapOf("titre" to "q", "verdict" to "v", "faits" to "facts", "source" to "src")))
        assertEquals("Le prix a doublé.", v.title)
        assertEquals("Faux", v.verdict)
        assertEquals("Un fait.", v.fields[0].text)
        assertEquals("Autre", v.fields[1].text)
        assertEquals("Rapport fictif", v.source)
        assertEquals(VerdictTone.Wrong, verdictTone(v.verdict!!))
    }

    @Test
    fun person_links_to_party_fiche() {
        val a = home.identites.single { it.id == "a" }
        assertEquals("parti-bleu", partyOf(a, home)?.id)
        assertNull("un parti ne renvoie pas à lui-même", partyOf(home.identites.single { it.id == "parti-bleu" }, home))
        assertNull("parti sans identité publiée", partyOf(home.identites.single { it.id == "b" }, home))
        assertEquals("par identifiant aussi", "parti-bleu", partyOf(a.copy(parti = "parti-bleu"), home)?.id)
    }
}
