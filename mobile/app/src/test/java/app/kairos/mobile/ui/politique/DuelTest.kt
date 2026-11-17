package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.model.IdKind
import app.kairos.mobile.ui.politique.model.LIST_KEY
import app.kairos.mobile.ui.politique.model.POL_FILES
import app.kairos.mobile.ui.politique.model.POL_OPTIONAL_FILES
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.model.SourceRef
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
      {"id":"ca","nom":"Camp A","type":"camp"},
      {"id":"cb","nom":"Camp B","type":"camp"},
      {"id":"cc","nom":"Camp C","type":"camp"},
      {"id":"cd","nom":"Camp D","type":"camp"}
    ]"""
    private val comps = """[
      {"id":"budget","titre":"Budget","rubrique":"budgets","chemin":"PROGRAMS","cle_identite":"who",
       "indicateurs":[{"champ":"debt","libelle":"Dette","unite":"% du PIB"},{"champ":"net","libelle":"Effet net","unite":"Md€/an"},{"champ":"hh","libelle":"Par ménage","unite":"€/an"}],
       "source":"Évaluateur fictif (https://example.com/a) ; note"},
      {"id":"ndv","titre":"Niveau de vie","rubrique":"niveau-de-vie","chemin":"MEASURES","cle_identite":"who",
       "indicateurs":[{"champ":"hh","libelle":"Par ménage","unite":"€/an"}],"source":"Autre"}
    ]"""
    private val full = data(
        "identites" to ids,
        "comparaisons" to comps,
        "budgets" to """{"PROGRAMS":[{"who":"Sans mesure","ref":true,"debt":120},{"who":"Alpha","debt":110,"hh":200,"lecture":{"verdict":"conteste","contrepartie_courte":"Coupe X","avis":[{"ev":"ev1","url":"https://example.com/av"}]}},{"who":"Beta","debt":"abc","hh":-50},{"who":"Autre","debt":130}],
                       "GAPS":[{"who":"Beta","what":"Mesure","claimed":10,"credited":5,"unit":"Md€","src":"Chiffrage fictif"}]}""",
        "niveau-de-vie" to """{"MEASURES":[{"who":"Alpha","hh":999}]}""",
        "bilan" to """{"MATRIX":[{"o":"Orientation 1","s":"Mandat fictif","c":{"Alpha":["keep","Garde"]}},{"o":"Orientation 2","c":{}}]}""",
        "votes" to """{"AN_G":["Groupe Bleu","Groupe Vert"],"AN_V":[{"t":"Loi 1","u":"https://example.com/s1","c":[["P","10 pour"],["P","8 pour"]]},{"t":"Loi 2","c":[["C",""],["P",""]]},{"t":"Loi 3","c":[["N",""],["P",""]]},{"t":"Loi 4","c":[["A",""],["P",""]]}]}""",
        "revirements" to """{"RV":[{"p":"Bleu","camp":"Camp A","s":"Sujet","a":"Avant fictif","b":"Après fictif","k":"R","src":[["Journal fictif","https://example.com/r"]]}]}""",
        "partis" to """{"PARTIS":[
          {"id":"b","nom":"Parti Bleu","rattachement":{"camp":"ca","src":[["Auteur fictif, « Titre », Éditeur, 2020","https://example.com/l","ev1"]]},
           "nuance_ministere":{"code":"BLE","bloc":"CENT","src":[["Circulaire fictive","https://example.com/m"]]},
           "creation":{"date":"2001-02-03"},"noms_precedents":[{"nom":"Ancien Bleu","jusqu_au":"2010-05"}],
           "fondateurs":{"valeur":null},"dirigeant":{"nom":"Exemple Dirigeant","titre":"Président","depuis":"2020-01-15"},
           "sieges":{"an":{"rattaches":null,"note":"Formation commune"},"senat":{"rattaches":0},"pe":{"elus":3,"groupe":"Groupe européen"}},
           "aide_publique":{"annee":2026,"total":null,"note":"Versée à une formation commune"},"adherents":{"nombre":null},"historique":[]},
          {"id":"r","nom":"Parti Rouge","rattachement":{"camp":null,"src":[],"note":"classement non établi par la littérature"},"nuance_ministere":{"code":null,"bloc":"DIV"}}
        ]}""",
        "camps" to """{"regle_src":[["Règle fictive","https://example.com/regle"]],"CAMPS":[
          {"id":"ca","nom":"Camp A","partis":["b","r","inconnu"],"candidats_declares":{"nombre":1,"liste":[{"id":"p1","date":"2026-05-03","src":[["Média fictif","https://example.com/c"]]}]},
           "sieges":{"an_rattaches":120,"senat_rattaches":null,"pe_elus":10},"aide_publique":{"annee":2026,"total":12500000},
           "europeennes_2024":{"listes":[{"liste":"Liste 1","pct_exprimes":10.5,"sieges":9},{"liste":"Liste 2","pct_exprimes":4.25,"sieges":3}],"pct_exprimes":null,"sieges":null}},
          {"id":"cb","nom":"Camp B","partis":["v"],"sieges":{"an_rattaches":80},"aide_publique":{"annee":2026,"total":null},
           "europeennes_2024":{"listes":[],"pct_exprimes":20.0,"sieges":18}},
          {"id":"cc","nom":"Camp C","partis":[],"sieges":{"an_rattaches":30},"aide_publique":{"total":2000000}}
        ]}""",
    )

    @Test fun optional_files_never_break_the_space() {
        assertTrue("comparaisons" in POL_FILES)
        assertTrue(listOf("partis", "camps").all { it in POL_FILES && it in POL_OPTIONAL_FILES })
        // Sans partis.json ni camps.json : pas de camp en duel, les partis restent comparables par leurs votes.
        val bare = data("identites" to ids, "votes" to """{"AN_G":["Groupe Bleu","Groupe Vert"],"AN_V":[]}""")
        assertNull(duelKind(bare, "ca"))
        assertEquals(listOf("b", "v"), duelRoster(bare, IdKind.Parti))
        assertTrue(duelRounds(bare, "b", "v").single() is VotesRound)
    }

    @Test fun duel_kinds_and_rosters() {
        assertEquals(IdKind.Personne, duelKind(full, "p1"))
        assertNull("aucun indicateur comparable", duelKind(full, "p3"))
        assertEquals(IdKind.Parti, duelKind(full, "r"))
        assertEquals(listOf("b", "r", "v"), duelRoster(full, IdKind.Parti))
        assertEquals("camp chiffré dans camps.json", listOf("ca", "cb", "cc"), duelRoster(full, IdKind.Camp))
        assertNull("camp absent de camps.json", duelKind(full, "cd"))
        assertTrue(duelReady(full, "ca", "cb"))
        assertFalse("familles différentes", duelReady(full, "p1", "b"))
        assertFalse("même adversaire", duelReady(full, "p1", "p1"))
        assertFalse(duelReady(full, null, "p1"))
        val empty = PolData(emptyMap())
        assertNull(duelKind(empty, "p1"))
        assertTrue(duelRounds(empty, "p1", "p2").isEmpty())
        val bad = data("identites" to ids, "comparaisons" to """{"x":1}""", "partis" to """{"PARTIS":"rien"}""", "camps" to """{"CAMPS":[7]}""", "votes" to """{"AN_G":5}""")
        assertNull(duelKind(bad, "p1"))
        assertNull(duelKind(bad, "b"))
        assertNull(duelKind(bad, "ca"))
    }

    @Test fun candidate_rounds() {
        val r = duelRounds(full, "p1", "p2")
        assertEquals(listOf("Finances publiques", "Porte-monnaie", "Annoncé contre crédité", "Face au bilan sortant", "Votes à l'Assemblée", "Revirements suivis"), r.map { it.title })
        val fin = r[0] as FinRound
        assertEquals(listOf("Dette", "Effet net"), fin.gauges.map { it.title })
        val debt = fin.gauges[0]
        assertEquals(110.0, debt.a!!, 0.0); assertNull("valeur mal typée : absente", debt.b)
        assertEquals(120.0, debt.base, 0.0); assertEquals("Sans mesure", debt.refName)
        assertEquals(listOf(130.0), debt.others)
        assertTrue(debt.pos(110.0) < debt.pos(120.0) && debt.pos(120.0) < debt.pos(130.0))
        assertEquals("trait : « Sans mesure » 120,0", debt.baseLabel)
        assertEquals("−10,0 pt par rapport à « Sans mesure »", debt.gap(110.0))
        assertEquals("sans référence : trait à zéro", "trait : zéro", fin.gauges[1].baseLabel)
        assertEquals(false to false, fin.gauges[1].avail)
        assertEquals("https://example.com/a", fin.sources.single().url)
        val w = r[1] as WalletRound
        assertEquals("ménage : dédoublonné, celui du budget", 200.0, w.gauge.a!!, 0.0)
        assertNull(w.gauge.gap(200.0))
        assertNotNull(w.la); assertNull(w.lb)
        assertEquals(true to true, w.avail)
        assertEquals(listOf("https://example.com/a", "https://example.com/av"), w.sources.map { it.url })
        val gap = r[2] as GapRound
        assertEquals(false to true, gap.avail)
        assertEquals(listOf("Chiffrage fictif"), gap.sources.map { it.label })
        val bilan = r[3] as BilanRound
        assertEquals("Mandat fictif", bilan.lines[0].s)
        assertEquals(true to false, bilan.avail)
        val votes = r[4] as VotesRound
        assertTrue(votes.viaParty)
        assertEquals("Groupe Bleu" to "Groupe Vert", votes.groupA to votes.groupB)
        assertEquals("tête : oppositions nettes sur scrutins comparables", 1 to 3, votes.clash to votes.both)
        val turn = r[5] as TurnRound
        assertEquals("revirements du parti du candidat", true to false, turn.avail)
        assertEquals(listOf("Journal fictif"), turn.sources.map { it.label })
        assertEquals(r.count { it.avail.first } to r.count { it.avail.second }, coverage(r))
    }

    @Test fun vote_cases() {
        assertEquals(VCase.Same, vcase("P", "P"))
        assertEquals(VCase.Same, vcase("S", "S"))
        assertEquals(VCase.Clash, vcase("P", "C"))
        assertEquals(VCase.Clash, vcase("C", "P"))
        assertEquals(VCase.Part, vcase("A", "P"))
        assertEquals(VCase.Part, vcase("S", "C"))
        assertEquals(VCase.None, vcase("N", "P"))
        assertEquals(VCase.None, vcase("C", "N"))
        assertEquals(listOf("eq", "bolt", "half", "box"), VCase.entries.map { it.icon })
        assertTrue(VCase.entries.all { it.icon in DUEL_ICONS })
    }

    @Test fun empty_rounds_are_hidden() {
        // Bilan sans position, aucun chiffrage, aucun revirement : rien de ces rounds.
        val d = data(
            "identites" to ids, "comparaisons" to comps,
            "budgets" to """{"PROGRAMS":[{"who":"Alpha","debt":1},{"who":"Beta","debt":2}],"GAPS":[]}""",
            "bilan" to """{"MATRIX":[{"o":"Orientation","c":{"Autre":["keep","x"]}}]}""",
        )
        assertEquals(listOf("Finances publiques"), duelRounds(d, "p1", "p2").map { it.title })
        // Même groupe pour les deux : pas de comparaison de votes.
        val same = data("identites" to ids, "votes" to """{"AN_G":["Groupe Bleu"],"AN_V":[{"t":"Loi","c":[["P",""]]}]}""",
            "revirements" to """{"RV":[{"p":"Vert","s":"x","k":"C"}]}""")
        assertTrue(duelRounds(same, "b", "v").none { it is VotesRound })
    }

    @Test fun party_cards_never_show_zero_for_null() {
        val r = duelRounds(full, "b", "r")
        assertEquals(listOf("Carte d'identité", "Positionnement", "Revirements suivis"), r.map { it.title })
        val card = r[0] as CardRound
        assertEquals(true to true, card.avail)
        val cols = card.blocks.filterIsInstance<Cols>()
        assertEquals(listOf("Origines", "Sièges", "Moyens", "Jalons"), cols.map { it.title })
        val origin = cols[0].a.filterIsInstance<Kv>().associateBy { it.k }
        assertEquals("03/02/2001", origin.getValue("Création").vals.single().v)
        assertEquals(KvVal("Ancien Bleu", "jusqu'en 05/2010"), origin.getValue("Prédécesseur").vals.single())
        assertNull("fondateurs inconnus : non renseigné", origin.getValue("Fondateurs").vals.single().v)
        assertEquals("Président, depuis le 15/01/2020", origin.getValue("Dirigeant").vals.single().small)
        val seats = cols[1].a.filterIsInstance<Kv>().associateBy { it.k }
        assertEquals(KvVal(null, "Formation commune"), seats.getValue("Assemblée nationale").vals.single())
        assertEquals("zéro publié : affiché", "0 rattachés", seats.getValue("Sénat").vals.single().v)
        assertEquals(KvVal("3 élus", "Groupe européen"), seats.getValue("Parlement européen").vals.single())
        val means = cols[2].a.filterIsInstance<Kv>().associateBy { it.k }
        assertEquals(KvVal(null, "Versée à une formation commune"), means.getValue("Aide publique 2026").vals.single())
        assertNull(means.getValue("Adhérents").vals.single().v)
        assertEquals(listOf(Note("Aucun jalon.")), cols[3].a)
        // Fiche sans aucun champ : tout est « non renseigné », jamais zéro.
        val bare = cols[2].b.filterIsInstance<Kv>()
        assertTrue(bare.all { kv -> kv.vals.all { it.v == null && it.nr == NOT_GIVEN } })
        val pos = (r[1] as CardRound).blocks.filterIsInstance<Cols>().single()
        assertEquals(CampLine("Camp retenu", "ca"), pos.a[0])
        assertEquals("Auteur fictif, « Titre »", (pos.a[1] as Kv).vals.single().small)
        assertEquals(KvVal("BLE", "bloc centre, pour information"), (pos.a[2] as Kv).vals.single())
        assertEquals(CampLine("Camp retenu", null), pos.b[0])
        assertEquals("classement non établi par la littérature", (pos.b[1] as Kv).vals.single().nr)
        assertNull("pas de code : non renseigné", (pos.b[2] as Kv).vals.single().v)
        // Une fiche manquante : « Fiche absente. » de ce côté, couverture fausse.
        val half = duelRounds(full, "b", "v")[0] as CardRound
        assertEquals(true to false, half.avail)
        assertEquals(listOf(Note("Fiche absente.")), half.blocks.filterIsInstance<Cols>()[0].b)
    }

    @Test fun camp_rounds_are_sums_and_counts() {
        val r = duelRounds(full, "ca", "cb")
        assertEquals(listOf("Composition", "Élus", "Financement public", "Urnes : européennes 2024"), r.map { it.title })
        assertTrue(r.all { it is CardRound })
        val comp = (r[0] as CardRound).blocks.filterIsInstance<Cols>()
        assertEquals("partis inconnus écartés", Tiles(listOf("b", "r")), comp[0].a.single())
        assertEquals(Big("1"), comp[1].a[0])
        assertEquals(Kv("Alice Alpha", "", "déclaré le 03/05/2026"), comp[1].a[1])
        assertEquals(listOf(Big(NO_DATA), Note("Aucun candidat déclaré.")), comp[1].b)
        assertTrue(r[0].sources.any { it.url == "https://example.com/regle" })
        val elus = (r[1] as CardRound).blocks.map { (it as GaugeBlock).gauge }
        assertEquals(120.0 to 80.0, elus[0].a to elus[0].b)
        assertEquals("repères : les autres camps", listOf(30.0), elus[0].others)
        assertEquals("120 sièges", elus[0].label(120.0))
        assertNull(elus[1].a)
        assertEquals("trait : zéro", elus[0].baseLabel)
        val money = ((r[2] as CardRound).blocks.single() as GaugeBlock).gauge
        assertEquals("Aide publique 2026", money.title)
        assertEquals(12.5, money.a!!, 1e-9); assertNull("total absent : jamais 0", money.b)
        assertEquals("12,5 M€", money.label(12.5))
        assertEquals(true to false, r[2].avail)
        val urnes = (r[3] as CardRound).blocks
        val votes = (urnes[0] as GaugeBlock).gauge
        assertEquals("somme des listes quand le total manque", 14.75, votes.a!!, 1e-9)
        assertEquals("total publié prioritaire", 20.0, votes.b!!, 0.0)
        assertEquals(12.0, (urnes[1] as GaugeBlock).gauge.a!!, 0.0)
        assertEquals(Kv("Liste 2", "4,25 %", "3 sièges"), (urnes[2] as Cols).a[1])
        assertEquals(14.75, campVotes(CampFiche(europeennes_2024 = Urnes(listOf(Liste(pct_exprimes = 10.5), Liste(pct_exprimes = 4.25))))) ?: 0.0, 1e-9)
        assertNull(campVotes(CampFiche()))
        assertNull(campSeats(CampFiche(europeennes_2024 = Urnes())))
        // Camp sans aucune aide publiée : le round reste, côté vide.
        val c = duelRounds(full, "cb", "cc")
        assertEquals(false to true, c.first { it.title == "Financement public" }.avail)
        assertEquals(true to false, c.first { it.title.startsWith("Urnes") }.avail)
        val noVote = data("identites" to ids, "camps" to """{"CAMPS":[{"id":"ca","partis":[]},{"id":"cb","partis":[]}]}""")
        assertEquals("aucune donnée des deux côtés : seule la composition reste", listOf("Composition"), duelRounds(noVote, "ca", "cb").map { it.title })
    }

    @Test fun pick_fills_active_then_free_slot() {
        var p = Pick()
        p = pickTap(p, "a")
        assertEquals(listOf("a", null), p.sel); assertEquals(1, p.active)
        p = pickTap(p, "b")
        assertEquals(listOf("a", "b"), p.sel); assertTrue(p.ready)
        p = pickTap(p, "a")
        assertEquals("retouché : sort, sa place devient active", listOf(null, "b"), p.sel); assertEquals(0, p.active)
        p = pickTap(p, RANDOM)
        assertEquals(listOf(RANDOM, "b"), p.sel)
        p = pickTap(p, RANDOM)
        assertEquals("la case ? se pose aussi en P2", listOf(RANDOM, RANDOM), p.sel)
        p = slotTap(p, 0)
        assertEquals(listOf(null, RANDOM), p.sel); assertEquals(0, p.active)
        assertEquals("deux places prises : P2 remplacé", listOf("a", "c"), pickTap(Pick(listOf("a", "b"), 0), "c").sel)
    }

    @Test fun random_draw_never_twice_nor_the_opponent() {
        assertEquals(listOf("x", "a"), resolvePick(listOf(RANDOM, "a"), listOf("a", "x", "y")))
        assertEquals(listOf("a", "x"), resolvePick(listOf("a", RANDOM), listOf("a", "x")))
        assertEquals(listOf("x", "y"), resolvePick(listOf(RANDOM, RANDOM), listOf("x", "y", "z")))
        assertNull("une seule identité : pas de tirage", resolvePick(listOf(RANDOM, RANDOM), listOf("x")))
        assertNull(resolvePick(listOf(null, "a"), listOf("x")))
    }

    @Test fun names_and_subtitles() {
        assertEquals("Alpha", duelName(full, "p1"))
        assertEquals("Parti Bleu", duelName(full, "b"))
        assertEquals("Bleu", duelSub(full, "p1"))
        assertEquals("Parti", duelSub(full, "b"))
        assertEquals("Camp", duelSub(full, "ca"))
        assertEquals("", duelName(full, "inconnu"))
    }

    @Test fun close_colors_hatch_second() {
        assertTrue(hatchSecond("#2255AA", "#2357AC"))
        assertFalse(hatchSecond("#2255AA", "#22AA55"))
        assertEquals(CLOSE_COLORS, colorDistance("#000000", "#004600"), 0.0)
        assertFalse("seuil exclu", hatchSecond("#000000", "#004600"))
        assertTrue(hatchSecond(null, "pas une couleur"))
    }

    @Test fun sources_and_rubrics() {
        val s = parseSrc("Évaluateur fictif (https://example.com/a) ; note")
        assertEquals("Évaluateur fictif", s.label); assertEquals("https://example.com/a", s.url)
        assertNull(parseSrc("Sans lien").url)
        assertEquals(listOf(SourceRef("Évaluateur fictif", "https://example.com/a")), rubricSources(full, "budgets"))
        assertTrue(rubricSources(full, "votes").isEmpty())
        val l = listOf(SourceRef("A", "https://e.com/1"), SourceRef("B", "https://e.com/1"), SourceRef("C"), SourceRef("C"), SourceRef(""), SourceRef("D"))
        assertEquals(listOf("A", "C"), footSources(l, 2).map { it.label })
        assertEquals(listOf("A", "C", "D"), footSources(l, 6).map { it.label })
    }

    @Test fun units_and_gaps() {
        assertEquals("+200 €/an", fmtUnit(200.0, "€/an"))
        assertEquals("110,0 % du PIB", fmtUnit(110.0, "% du PIB"))
        assertEquals("−1,5 Md€/an", fmtUnit(-1.5, "Md€/an"))
        assertEquals("3 sièges", fmtUnit(3.0, "sièges"))
        assertEquals("14,7 M€", fmtUnit(14.734, "M€"))
        assertEquals("31,6 %", fmtUnit(31.58, "%"))
        assertEquals("3,0", fmtUnit(3.0, ""))
        assertEquals("+0,7 pt par rapport à « Sans mesure »", gapLabel(8.3, 7.6, "% du PIB", "Sans mesure"))
        assertEquals("égal à « R »", gapLabel(7.64, 7.6, "% du PIB", "R"))
        assertEquals("−10,0 €/an par rapport à « R »", gapLabel(-5.0, 5.0, "€/an", "R"))
        assertEquals("05/11/2022", slashDate("2022-11-05"))
        assertEquals("12/2021", slashDate("2021-12"))
        assertEquals("1969", slashDate("1969"))
        assertNull(slashDate(null))
        assertEquals(fmtNum(14734296.0) + " €", euros(14734296.24))
        assertNull(euros(null))
        val g = Gauge("T", "sièges", 10.0, null, listOf(5.0, 30.0))
        assertEquals("bornes avec le trait à zéro", "0" to "30", g.scale)
        assertEquals(NO_DATA, g.label(null))
    }

    @Test fun pixel_text_and_logo() {
        val p1 = pxText("P1")
        assertEquals("5 + 1 + 5 cellules", 11 to 7, p1.w to p1.h)
        val bold = pxText("P1", bold = true, outline = true, shadow = true)
        assertEquals("gras : 7 cellules par caractère, puis contour et ombre", 13 + 2 + 1, bold.w)
        assertEquals(7 + 2 + 1, bold.h)
        // Ombre d'abord, puis contour, puis plein : l'ordre de dessin.
        val roles = bold.cells.map { it.third }
        assertEquals(roles.sortedBy { it.ordinal }, roles)
        assertTrue(roles.containsAll(listOf(PxRole.Shadow, PxRole.Outline, PxRole.Fill)))
        // Dessus éclairci : seules les rangées du haut (40 %) passent en clair.
        val two = pxText("I", two = true)
        assertTrue(two.cells.filter { it.third == PxRole.Light }.all { it.second < 3 })
        assertTrue(two.cells.filter { it.third == PxRole.Fill }.all { it.second >= 3 })
        assertEquals("caractère inconnu : espace", 3, pxText("é").w)
        val vs = vsImage()
        assertEquals(17 + 2 + 1, vs.w)
        assertEquals(10 + 2 + 1, vs.h)
        assertEquals(17, VS_GRID[0].length)
        val stairs = vsStairs()
        assertEquals(17, stairs.size)
        assertEquals(0.62f, stairs.first(), 1e-6f); assertEquals(0.38f, stairs.last(), 1e-6f)
    }

    @Test fun grip_and_window_snap() {
        assertEquals("paliers de 12", 24f, gripHeight(0f, 20f, 100f, 12f), 0f)
        assertEquals(0f, gripHeight(0f, -30f, 100f, 12f), 0f)
        assertEquals("borné au tiroir", 100f, gripHeight(96f, 30f, 100f, 12f), 0f)
        assertTrue(gripOpens(36f, 100f, fromOpen = false))
        assertFalse(gripOpens(34f, 100f, fromOpen = false))
        assertFalse("depuis ouvert, il faut garder plus de 60 %", gripOpens(59f, 100f, fromOpen = true))
        assertTrue(gripOpens(61f, 100f, fromOpen = true))
        assertEquals(1, windowRelease(0, -31f, 100f))
        assertEquals(0, windowRelease(0, -29f, 100f))
        assertEquals(0, windowRelease(1, 31f, 100f))
        assertEquals(1, windowRelease(1, 10f, 100f))
        // Au bord, le glissé n'est pas pris : il change de round.
        assertFalse(windowTakes(0, 5f)); assertTrue(windowTakes(0, -5f))
        assertFalse(windowTakes(1, -5f)); assertTrue(windowTakes(1, 5f))
    }
}
