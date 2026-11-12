package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.model.IdKind
import app.kairos.mobile.ui.politique.model.LIST_KEY
import app.kairos.mobile.ui.politique.model.POL_FILES
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.screens.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class DuelTest {
    private fun file(s: String) = PolJson.parseToJsonElement(s).jsonObject
    private fun list(s: String) = file("""{"$LIST_KEY":$s}""")
    private fun data(vararg f: Pair<String, String>) = PolData(f.associate { (k, v) -> k to if (v.trimStart().startsWith("[")) list(v) else file(v) })

    private val ids = """[
      {"id":"p1","nom":"Alice Alpha","parti":"Bleu","type":"personne","alias":["Alpha"],"couleur":"#2255AA"},
      {"id":"p2","nom":"Bruno Beta","parti":"Vert","type":"personne","alias":["Beta"],"couleur":"#22AA55"},
      {"id":"p3","nom":"Carla Gamma","parti":"Bleu","type":"personne","alias":["Gamma"]},
      {"id":"b","nom":"Parti Bleu","parti":"Bleu","type":"parti","alias":["Bleu","Groupe Bleu"],"couleur":"#2255AA"},
      {"id":"v","nom":"Parti Vert","parti":"Vert","type":"parti","alias":["Vert","Groupe Vert"],"couleur":"#2357AC"},
      {"id":"r","nom":"Parti Rouge","parti":"Rouge","type":"parti","alias":["Rouge"]},
      {"id":"c","nom":"Le centre","type":"camp"}
    ]"""
    private val comps = """[
      {"id":"budget","titre":"Budget","rubrique":"budgets","chemin":"PROGRAMS","cle_identite":"who",
       "indicateurs":[{"champ":"debt","libelle":"Dette","unite":"% du PIB"},{"champ":"hh","libelle":"Par ménage","unite":"€/an"}],
       "source":"Évaluateur fictif (https://example.com/a) ; note"},
      {"id":"ndv","titre":"Niveau de vie","rubrique":"niveau-de-vie","chemin":"MEASURES","cle_identite":"who",
       "indicateurs":[{"champ":"hh","libelle":"Par ménage","unite":"€/an"}],"source":"Autre"}
    ]"""
    private val full = data(
        "identites" to ids,
        "comparaisons" to comps,
        "budgets" to """{"PROGRAMS":[{"who":"Sans mesure","ref":true,"debt":120},{"who":"Alpha","debt":110,"hh":200,"lecture":{"verdict":"tenable","contrepartie":"Coupe X"}},{"who":"Beta","debt":"abc","hh":-50},{"who":"Autre","debt":130}],
                       "GAPS":[{"who":"Beta","what":"Mesure","claimed":10,"credited":5,"unit":"Md€"}]}""",
        "niveau-de-vie" to """{"MEASURES":[{"who":"Alpha","hh":999}]}""",
        "bilan" to """{"MATRIX":[{"o":"Orientation 1","c":{"Alpha":["keep","Garde"]}},{"o":"Orientation 2","c":{}}]}""",
        "argent" to """{"AIDE":{"rows":[{"l":"Bleu","v":4.5},{"l":"Autre","v":2}]},"PARTIS":[["Vert",1.2,3.4,-0.5]]}""",
        "votes" to """{"AN_G":["Groupe Bleu","Groupe Vert"],"AN_V":[{"t":"Loi 1","c":[["P","10 pour"],["P","8 pour"]]},{"t":"Loi 2","c":[["C",""],["P",""]]},{"t":"Loi 3","c":[["N",""],["P",""]]}],"PE_G":["Groupe Bleu"],"PE_V":[{"t":"Texte","c":[["A",""]]}]}""",
        "dit-vote" to """{"SCRUTINS":[{"id":"s1","verdicts":[{"qui":"b","v":"sans-parole"},{"qui":"v","v":"coherent"},{"qui":"v","v":"ecart"}]}]}""",
        "revirements" to """{"RV":[{"p":"Alpha","camp":"Bleu","s":"Sujet","k":"R"},{"p":"","camp":"Vert","s":"Autre","k":"C"}]}""",
    )

    @Test fun comparisons_file_is_loaded() {
        assertTrue("comparaisons" in POL_FILES)
    }

    @Test fun duel_kind_requires_comparable_data() {
        assertEquals(IdKind.Personne, duelKind(full, "p1"))
        assertEquals(IdKind.Personne, duelKind(full, "p2"))
        assertNull("aucun indicateur comparable", duelKind(full, "p3"))
        assertEquals(IdKind.Parti, duelKind(full, "b"))
        assertEquals(IdKind.Parti, duelKind(full, "v"))
        assertNull("ni aide, ni comptes, ni groupe", duelKind(full, "r"))
        assertNull(duelKind(full, "c"))
        assertNull(duelKind(full, "inconnu"))
        assertNull(duelKind(full, null))
        assertEquals(listOf("p1", "p2"), duelRoster(full, IdKind.Personne))
        assertEquals(listOf("b", "v"), duelRoster(full, IdKind.Parti))
        assertTrue(duelReady(full, "p1", "p2"))
        assertFalse("types différents", duelReady(full, "p1", "b"))
        assertFalse("même adversaire", duelReady(full, "p1", "p1"))
        assertFalse(duelReady(full, null, "p1"))
        // Données vides ou mal typées : rien de comparable, aucun plantage.
        val empty = PolData(emptyMap())
        assertNull(duelKind(empty, "p1"))
        assertTrue(duelRoster(empty, IdKind.Personne).isEmpty())
        assertTrue(duelRounds(empty, "p1", "p2").isEmpty())
        val bad = data("identites" to ids, "comparaisons" to """{"x":1}""", "budgets" to """{"PROGRAMS":"rien"}""", "argent" to """{"AIDE":[]}""", "votes" to """{"AN_G":5}""")
        assertNull(duelKind(bad, "p1"))
        assertNull(duelKind(bad, "b"))
        val badInd = data("identites" to ids, "comparaisons" to """[{"rubrique":"budgets","chemin":"PROGRAMS","cle_identite":"who","indicateurs":"x"},7]""", "budgets" to """{"PROGRAMS":[{"who":"Alpha","debt":1}]}""")
        assertNull("sans indicateur, rien à comparer", duelKind(badInd, "p1"))
    }

    @Test fun rounds_availability_per_side() {
        val r = duelRounds(full, "p1", "p2")
        val bars = r.filterIsInstance<BarRound>()
        // Indicateurs dédoublonnés par champ et unité : « Par ménage » une seule fois.
        assertEquals(listOf("Dette", "Par ménage"), bars.map { it.title })
        val debt = bars[0]
        assertEquals(110.0, debt.a!!, 0.0); assertNull("valeur mal typée : non trouvée", debt.b)
        assertEquals(true to false, debt.avail)
        assertEquals("référence « sans mesure »", 120.0, debt.base, 0.0)
        assertEquals(listOf(130.0), debt.others)
        assertEquals(110.0 to 130.0, debt.min to debt.max)
        assertTrue(debt.pos(110.0) < debt.pos(120.0) && debt.pos(120.0) < debt.pos(130.0))
        assertEquals("https://example.com/a", debt.sources.single().url)
        assertNotNull("balance au premier indicateur", debt.sacrifice)
        assertNull(bars[1].sacrifice)
        assertEquals(0.0, bars[1].base, 0.0)
        val bilan = r.filterIsInstance<BilanRound>().single()
        assertEquals("Face au bilan sortant", bilan.title)
        assertEquals(true to false, bilan.avail)
        assertEquals(false to true, r.filterIsInstance<GapRound>().single().avail)
        val votes = r.filterIsInstance<VotesRound>().single()
        assertEquals("AN", votes.chambre); assertTrue(votes.viaParty)
        assertEquals(1, votes.same); assertEquals(2, votes.both)
        val dv = r.filterIsInstance<DvRound>().single()
        assertEquals("b" to "v", dv.partyA to dv.partyB)
        assertEquals("rien de comptable côté A : jamais 0 / 0", false to true, dv.avail)
        assertNull(publicRateLabel(dvCount(dv.a)))
        assertEquals("1 / 2", publicRateLabel(dvCount(dv.b))!!.figure)
        assertEquals(true to false, r.filterIsInstance<TurnRound>().single().avail)
        assertEquals(r.count { it.avail.first } to r.count { it.avail.second }, coverage(r))

        val p = duelRounds(full, "b", "v")
        assertEquals(listOf("Aide publique", "Dons et cotisations", "Dette du parti", "Situation nette"), p.filterIsInstance<BarRound>().map { it.title.substringBefore(" en ") })
        val aide = p.filterIsInstance<BarRound>()[0]
        assertEquals(4.5, aide.a!!, 0.0); assertNull(aide.b)
        assertEquals(listOf("AN", "PE"), p.filterIsInstance<VotesRound>().map { it.chambre })
        assertEquals(true to false, p.filterIsInstance<VotesRound>()[1].avail)
        assertEquals(false to true, p.filterIsInstance<TurnRound>().single().avail)
    }

    @Test fun close_colors_hatch_second() {
        assertTrue(hatchSecond("#2255AA", "#2357AC"))
        assertFalse(hatchSecond("#2255AA", "#22AA55"))
        assertEquals(0.0, colorDistance("#000000", "#000000"), 0.0)
        assertEquals(CLOSE_COLORS, colorDistance("#000000", "#004600"), 0.0)
        assertFalse("seuil exclu", hatchSecond("#000000", "#004600"))
        // Couleur absente ou illisible : gris neutre des deux côtés, donc proches.
        assertTrue(hatchSecond(null, "pas une couleur"))
        val r = duelRounds(full, "b", "v")
        assertTrue(r.isNotEmpty())
    }

    @Test fun names_and_sources() {
        assertEquals("Alpha", duelName(full, "p1"))
        assertEquals("Parti Bleu", duelName(full, "b"))
        assertEquals("", duelName(full, "inconnu"))
        val s = parseSrc("Évaluateur fictif (https://example.com/a) ; note")
        assertEquals("Évaluateur fictif", s.label); assertEquals("https://example.com/a", s.url)
        assertNull(parseSrc("Sans lien").url)
    }

    @Test fun rubric_sources_from_comparisons() {
        assertEquals(listOf(app.kairos.mobile.ui.politique.model.SourceRef("Évaluateur fictif", "https://example.com/a")), rubricSources(full, "budgets"))
        assertEquals(listOf("Autre"), rubricSources(full, "niveau-de-vie").map { it.label })
        assertNull(rubricSources(full, "niveau-de-vie").single().url)
        assertTrue(rubricSources(full, "votes").isEmpty())
        assertTrue(rubricSources(data(), "budgets").isEmpty())
        // Lien non https : libellé seul, jamais ouvrable.
        val http = data("comparaisons" to """[{"id":"x","rubrique":"budgets","chemin":"PROGRAMS","cle_identite":"who","indicateurs":[{"champ":"debt"}],"source":"Site (http://example.com/b)"}]""")
        assertNull(rubricSources(http, "budgets").single().url)
    }

    @Test fun pick_toggle_keeps_two() {
        assertEquals(listOf("a"), pickToggle(emptyList(), "a"))
        assertEquals(listOf("a", "b"), pickToggle(listOf("a"), "b"))
        assertEquals(listOf("a", "c"), pickToggle(listOf("a", "b"), "c"))
        assertEquals(listOf("b"), pickToggle(listOf("a", "b"), "a"))
    }

    @Test fun coverage_hidden_without_rounds() {
        assertNull(coverageCaption(emptyList()))
        val r = duelRounds(full, "p1", "p2")
        val (ca, cb) = coverage(r)
        assertEquals("Données $ca/${r.size}" to "Données $cb/${r.size}", coverageCaption(r))
    }

    @Test fun units_kept_whole() {
        assertEquals("+200 €/an", fmtUnit(200.0, "€/an"))
        assertEquals("110,0 % du PIB", fmtUnit(110.0, "% du PIB"))
        assertEquals("−1,5 Md€/an", fmtUnit(-1.5, "Md€/an"))
        assertEquals("3,0 sièges", fmtUnit(3.0, "sièges"))
        assertEquals("3,0", fmtUnit(3.0, ""))
    }

    @Test fun signed_values_show_sign() {
        // Valeurs négatives : signe et unité conservés.
        assertEquals("−8,3 % du PIB", fmtUnit(-8.3, "% du PIB"))
        assertEquals("−58,2 Md€/an", fmtUnit(-58.2, "Md€/an"))
        assertEquals("+6,2 Md€/an", fmtUnit(6.2, "Md€/an"))
        // Barre tracée depuis la référence : l'écart dit son signe, son unité et sa référence.
        assertEquals("+0,7 pt par rapport à « Sans mesure »", gapLabel(8.3, 7.6, "% du PIB", "Sans mesure"))
        assertEquals("−1,4 pt par rapport à « Sans mesure »", gapLabel(6.2, 7.6, "% du PIB", "Sans mesure"))
        assertEquals("−10,0 €/an par rapport à « R »", gapLabel(-5.0, 5.0, "€/an", "R"))
        val r = duelRounds(full, "p1", "p2").filterIsInstance<BarRound>().first { it.title == "Dette" }
        assertEquals("−10,0 pt par rapport à « Sans mesure »", r.gap(r.a!!))
        // Sans référence (barre depuis zéro) : pas d'écart affiché, la valeur signée suffit.
        assertNull(duelRounds(full, "p1", "p2").filterIsInstance<BarRound>().first { it.title == "Par ménage" }.gap(200.0))
    }

    @Test fun shared_block_is_said_and_never_guessed() {
        val blocIds = """[
          {"id":"b","nom":"Parti Bleu","parti":"Bleu","type":"parti","alias":["Bleu","Groupe Bleu","Bleu et Vert (alliance)"]},
          {"id":"v","nom":"Parti Vert","parti":"Vert","type":"parti","alias":["Vert","Groupe Vert","Bleu et Vert (alliance)"]}
        ]"""
        val d = data(
            "identites" to blocIds,
            "argent" to """{"AIDE":{"rows":[{"l":"Bleu et Vert (alliance)","v":9},{"l":"Rouge","v":1}]},"PARTIS":[["Groupe Bleu",1,2,3],["Bleu",4,5,6],["Vert",7,8,9],["Groupe Vert",1,1,1]]}""",
        )
        val r = duelRounds(d, "b", "v").filterIsInstance<BarRound>()
        // Une seule ligne, au nom d'un bloc : le montant est celui du bloc, et c'est dit.
        val aide = r[0]
        assertEquals(9.0, aide.a!!, 0.0); assertEquals(9.0, aide.b!!, 0.0)
        assertTrue(aide.help, "montant du bloc « Bleu et Vert »" in aide.help)
        // Plusieurs lignes : celle qui porte le nom du parti.
        assertEquals(4.0, r[1].a!!, 0.0); assertEquals(7.0, r[1].b!!, 0.0)
        assertFalse(r[1].help.contains("bloc"))
        // Plusieurs lignes, aucune au nom du parti : valeur absente, jamais la première.
        val ambIds = """[{"id":"b","nom":"Parti Bleu","parti":"Bleu","type":"parti","alias":["Groupe Bleu","Bleu alliance"]},{"id":"v","nom":"Parti Vert","parti":"Vert","type":"parti","alias":["Groupe Vert"]}]"""
        val amb = data("identites" to ambIds, "argent" to """{"PARTIS":[["Groupe Bleu",1,2,3],["Bleu alliance",4,5,6]]}""", "votes" to """{"AN_G":["Groupe Vert","Groupe Bleu"]}""")
        assertNull(duelRounds(amb, "b", "v").filterIsInstance<BarRound>()[0].a)
    }

    @Test fun own_never_falls_back_to_first() {
        val label = { r: String -> r }
        assertEquals("Groupe Bleu", listOf("Groupe Bleu").own(full, "b") { label(it) })
        assertEquals("Bleu", listOf("Groupe Bleu", "Bleu").own(full, "b") { label(it) })
        assertNull("plusieurs lignes, aucune au nom", listOf("Groupe Bleu", "Autre bloc").own(full, "b") { label(it) })
        assertNull(emptyList<String>().own(full, "b") { label(it) })
    }

    @Test fun bloc_only_when_label_is_shared() {
        val blocIds = """[
          {"id":"b","nom":"Parti Bleu","parti":"Bleu","type":"parti","alias":["Liste Bleue","Union (commune)"]},
          {"id":"v","nom":"Parti Vert","parti":"Vert","type":"parti","alias":["Union (commune)"]}
        ]"""
        val d = data("identites" to blocIds, "argent" to """{"AIDE":{"rows":[{"l":"Liste Bleue","v":3},{"l":"Union (commune)","v":9}]}}""", "votes" to """{"AN_G":["Liste Bleue"]}""")
        val aide = duelRounds(d, "b", "v").filterIsInstance<BarRound>().single()
        // b : deux lignes le citent, aucune à son nom → absent ; v : une ligne, partagée → bloc.
        assertNull(aide.a); assertEquals(9.0, aide.b!!, 0.0)
        assertTrue(aide.help, "Parti Vert : montant du bloc « Union »" in aide.help)
        val own = data("identites" to blocIds, "argent" to """{"AIDE":{"rows":[{"l":"Liste Bleue","v":3}]}}""", "votes" to """{"AN_G":["Union (commune)"]}""")
        val r = duelRounds(own, "b", "v").filterIsInstance<BarRound>().single()
        assertEquals("libellé propre à b : pas de bloc", 3.0, r.a!!, 0.0)
        assertFalse(r.help, "bloc" in r.help)
    }

    @Test fun balance_round_uses_side_switcher() {
        fun bar(sac: Sacrifice?) = BarRound("Dette", "", 1.0, 2.0, emptyList(), 0.0, { it.toString() }, sacrifice = sac)
        assertEquals(RoundLayout.Switcher, layoutFor(bar(Sacrifice(app.kairos.mobile.ui.politique.model.Lecture(), "Alpha", null, null))))
        assertEquals(RoundLayout.SideBySide, layoutFor(bar(null)))
        assertEquals("deux balances absentes", RoundLayout.SideBySide, layoutFor(bar(Sacrifice(null, "Alpha", null, "Beta"))))
        assertEquals(RoundLayout.Switcher, layoutFor(bar(Sacrifice(null, null, app.kairos.mobile.ui.politique.model.Lecture(), "Beta"))))
        assertEquals(RoundLayout.Switcher, layoutFor(BilanRound(listOf(BilanLine("O", listOf("keep", "Garde"), null)))))
        assertEquals(RoundLayout.Switcher, layoutFor(GapRound(emptyList(), emptyList())))
        assertEquals(RoundLayout.SideBySide, layoutFor(VotesRound("AN", "G1", "G2", false, emptyList())))
        val short = Turn(s = "Retraites")
        assertEquals(RoundLayout.SideBySide, layoutFor(TurnRound(listOf(short), listOf(short))))
        assertEquals(RoundLayout.Switcher, layoutFor(TurnRound(List(12) { Turn(s = "Position suivie $it") }, listOf(short))))
        fun dv(v: String, titre: String) = DvItem(Scrutin(titre = titre), Verdict(v = v))
        assertEquals(RoundLayout.SideBySide, layoutFor(DvRound("b", "v", listOf(dv("ecart", "Court")), listOf(dv("coherent", "x".repeat(500))), false)))
        assertEquals(RoundLayout.Switcher, layoutFor(DvRound("b", "v", listOf(dv("ecart", "x".repeat(LONG_SIDE + 1))), emptyList(), false)))
    }
}
