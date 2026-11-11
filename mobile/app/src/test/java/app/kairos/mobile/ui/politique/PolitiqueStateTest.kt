package app.kairos.mobile.ui.politique

import androidx.compose.ui.graphics.Color
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.data.PolCompare
import app.kairos.mobile.data.PolFiche
import app.kairos.mobile.data.PolGroup
import app.kairos.mobile.data.PolHome
import app.kairos.mobile.data.PolRubric
import app.kairos.mobile.ui.theme.SceneColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolitiqueStateTest {
    private val homeJson = """
        {"verifie_le":"2026-11-09","inconnu":1,
         "identites":[{"id":"a","nom":"Candidat A","parti":"Parti Bleu","couleur":"#3B5C95","x":true},
                      {"id":"b","nom":"Candidat B"}],
         "rubriques":[{"id":"votes","titre":"Votes","resume":"Scrutins publics","entrees":4},
                      {"id":"hors_bulle","titre":"Hors de ma bulle","entrees":2},
                      {"id":"dit_vote","titre":"Dit / voté","entrees":1},
                      {"id":"rhetorique","titre":"Rhétorique","entrees":3}],
         "themes":[{"id":"budget","titre":"Budget"}]}
    """.trimIndent()

    @Test
    fun home_maps_contract() {
        val home = KairosJson.decodeFromString(PolHome.serializer(), homeJson)
        assertEquals("Données vérifiées le 9 novembre 2026", checkedLabel(home.checkedOn))
        assertEquals(listOf("Candidat A", "Candidat B"), home.identites.map { it.nom })
        assertNull("champ facultatif absent", home.identites[1].parti)
        val cards = homeCards(home)
        // Dit / voté composé en tête, la rubrique homonyme couverte, Hors de ma bulle en dernier et large.
        assertEquals(listOf("Dit / voté", "Votes", "Rhétorique", "Hors de ma bulle"), cards.map { it.titre })
        assertNull(cards.first().id)
        assertTrue(cards.last().wide)
        assertEquals("Scrutins publics", cards[1].resume)
        assertEquals("3 entrées", cards[2].resume)
        assertEquals(RubricKind.Rhetorique, cards[2].kind)
        // Accueil minimal : rien ne casse.
        val empty = KairosJson.decodeFromString(PolHome.serializer(), "{}")
        assertNull(checkedLabel(empty.checkedOn))
        assertTrue(homeCards(empty).isEmpty())
        assertEquals("Données vérifiées le bientôt", checkedLabel("bientôt"))
    }

    @Test
    fun compare_needs_two_to_three() {
        var s = toggleCompare(emptyList(), "a")
        assertFalse(compareReady(s, "budget"))
        s = toggleCompare(s, "b")
        assertTrue(compareReady(s, "budget"))
        assertFalse("thème requis", compareReady(s, null))
        s = toggleCompare(toggleCompare(s, "c"), "d")
        assertEquals("trois au plus", listOf("a", "b", "c"), s)
        assertTrue(compareReady(s, "budget"))
        assertEquals(listOf("a", "c"), toggleCompare(s, "b"))
        val home = KairosJson.decodeFromString(PolHome.serializer(), homeJson)
        assertTrue(HomeAction.Comparer in homeActions(HomeLoad.Ready(home)))
        assertFalse(HomeAction.Comparer in homeActions(HomeLoad.Ready(home.copy(identites = home.identites.take(1)))))
    }

    @Test
    fun missing_value_shown_as_not_given() {
        val c = KairosJson.decodeFromString(
            PolCompare.serializer(),
            """{"theme":{"id":"budget","titre":"Budget"},"identites":[{"id":"a","nom":"Candidat A"},"b","c"],
               "lignes":[{"libelle":"Coût net","unite":"Md€","valeurs":[
                  {"id":"a","valeur":42,"source":"https://example.org/a"},
                  {"id":"b","valeur":null,"source":"Rapport public"},
                  {"id":"c","valeur":0}]},
                 {"libelle":"Écart","valeurs":[{"id":"a","valeur":""}]}]}""",
        )
        val home = KairosJson.decodeFromString(PolHome.serializer(), homeJson)
        val t = compareTable(c, listOf("a", "b", "c"), home.identites)
        assertEquals("Budget", t.theme)
        assertEquals(listOf("Candidat A", "Candidat B", "c"), t.columns.map { it.nom })
        val cost = t.rows[0]
        assertEquals("Coût net (Md€)", cost.label)
        assertEquals("42 Md€", cost.cells[0].text)
        assertEquals("https://example.org/a", cost.cells[0].url)
        assertEquals(NOT_GIVEN, cost.cells[1].text)
        assertEquals("la source reste, même sans valeur", "Rapport public", cost.cells[1].source)
        assertEquals("zéro publié reste zéro", "0 Md€", cost.cells[2].text)
        assertEquals(listOf(NOT_GIVEN, NOT_GIVEN, NOT_GIVEN), t.rows[1].cells.map { it.text })
        assertFalse(t.rows[1].cells[2].given)
    }

    @Test
    fun search_debounced_and_bounded() = runBlocking {
        val asked = mutableListOf<String>()
        val s = DebouncedSearch(this, { q -> asked += q; "résultats $q" }, onUnauthorized = {}, delayMs = 50)
        s.type("r")
        s.type("re")
        s.type("ret")
        s.type("  retraites  ")
        delay(150)
        assertEquals("une seule requête, la dernière", listOf("retraites"), asked)
        assertEquals("résultats retraites", s.results)
        s.type("x")
        assertNull("trop court : rien ne part, rien d'ancien ne reste", s.results)
        delay(100)
        assertEquals(1, asked.size)
        assertEquals(SEARCH_MAX, searchTerm("a".repeat(500))!!.length)

        // Réponse lente dépassée par une nouvelle saisie : jamais affichée.
        val slow = CompletableDeferred<Unit>()
        val t = DebouncedSearch(this, { q -> if (q == "lent") slow.await(); q }, onUnauthorized = {}, delayMs = 10)
        t.type("lent")
        delay(40)
        assertTrue(t.searching)
        t.type("vite")
        slow.complete(Unit)
        delay(60)
        assertEquals("vite", t.results)
        assertFalse(t.searching)

        val groups = (1..20).map { i -> PolGroup(rubrique = "r$i", entrees = List(50) { JsonPrimitive(it) }) } +
            PolGroup(rubrique = "declarations", titre = "Déclarations vérifiées", entrees = listOf(JsonPrimitive("x"))) +
            PolGroup(rubrique = "vide")
        val b = boundedResults(groups)
        assertEquals(SEARCH_GROUPS, b.size)
        assertEquals("déclarations vérifiées d'abord", "declarations", b.first().key)
        assertTrue(b.all { it.entrees.size <= SEARCH_PER_GROUP && it.entrees.isNotEmpty() })
    }

    @Test
    fun search_failure_is_reported() = runBlocking {
        val s = DebouncedSearch<String>(this, { throw ApiError.Offline }, onUnauthorized = {}, delayMs = 1)
        s.type("dette")
        delay(50)
        yield()
        assertEquals("Hors ligne : serveur injoignable.", s.failed)
        assertFalse(s.searching)
    }

    @Test
    fun politique_unavailable_keeps_site_access() {
        val absent = politiqueIssue(ApiError.Server(404, "Module inconnu"))
        assertEquals("Module Politique absent du serveur.", absent)
        assertEquals("Serveur à mettre à jour.", politiqueIssue(ApiError.Server(404, "x", bare = true)))
        // Application plus récente que le module : réponse illisible, message clair.
        assertTrue(politiqueIssue(ApiError.Server(200, "Réponse illisible")).contains("mettre à jour"))
        for (load in listOf(HomeLoad.Loading, HomeLoad.Down(absent))) {
            val actions = homeActions(load)
            assertTrue(HomeAction.OuvrirSite in actions)
            assertTrue(HomeAction.Partager in actions)
            assertTrue(HomeAction.Acces in actions)
            assertFalse(HomeAction.Comparer in actions)
            assertFalse(HomeAction.Verifier in actions)
        }
    }

    @Test
    fun generic_entry_keeps_fields_tables_and_source() {
        val e = KairosJson.parseToJsonElement(
            """{"nom":"L'homme de paille","definition":"Déformer la position adverse.","verdict":"nuancé","score":3,
               "vide":null,"details":{"annee":2024,"lieu":"Ville Exemple"},
               "scrutins":[{"date":"2024-01-01","vote":"pour"},{"date":"2024-02-01","vote":"contre"}],
               "source":{"titre":"Rapport","url":"https://example.org/r"}}""",
        )
        val v = entryView(e)
        assertEquals("L'homme de paille", v.title)
        assertEquals("nuancé", v.verdict)
        assertEquals(listOf("Definition", "Score", "Details"), v.fields.map { it.label })
        assertEquals("Annee : 2024 · Lieu : Ville Exemple", v.fields[2].text)
        assertEquals(listOf("Date", "Vote"), v.tables.single().columns)
        assertEquals(2, v.tables.single().rows.size)
        assertEquals("Rapport", v.source)
        assertEquals("https://example.org/r", v.url)
        // Adresse non web : jamais ouverte.
        assertNull(sourceUrl(JsonPrimitive("javascript:alert(1)")))
        assertEquals(VerdictTone.Wrong, verdictTone("Faux"))
        assertEquals(VerdictTone.Mixed, verdictTone("nuancé"))
    }

    @Test
    fun sections_and_attachments() {
        val r = KairosJson.decodeFromString(
            PolRubric.serializer(),
            """{"id":"votes","titre":"Votes","contenu":{"periode":"2024","scrutins":[{"titre":"Loi X"},{"titre":"Loi Y"}]},
               "rattachements":{"scrutins":[["a","b"],[]]}}""",
        )
        val s = sections(r.contenu)
        assertEquals(listOf("", "Scrutins"), s.map { it.label })
        assertEquals("2024", s[0].notes.single().text)
        assertEquals("scrutins", s[1].path)
        assertEquals(listOf("a", "b"), attachedIds(r, "scrutins", 0))
        assertTrue(attachedIds(r, "scrutins", 1).isEmpty())
        assertTrue(attachedIds(r, "autre", 0).isEmpty())
    }

    @Test
    fun dit_vote_pairs_only_when_data_links() {
        fun fiche(json: String) = KairosJson.decodeFromString(PolFiche.serializer(), json)
        val linked = ditVote(fiche("""{"identite":{"id":"a"},"rubriques":[{"id":"dit_vote","entrees":[{"dit":"Nous protégerons X.","vote":"Contre X","source":"s"}]}]}"""))
        assertEquals("Contre X", linked.pairs.single().voted)
        val apart = ditVote(fiche("""{"identite":{"id":"a"},"rubriques":[{"id":"declarations","entrees":[{"titre":"Propos"}]},{"id":"votes","entrees":[{"titre":"V1"}]},{"id":"revirements","entrees":[{"titre":"R1"}]},{"id":"budgets","entrees":[{"titre":"B"}]}]}"""))
        assertTrue(apart.pairs.isEmpty())
        assertEquals(1, apart.said.size)
        assertEquals(2, apart.voted.size)
    }

    @Test
    fun bulle_groups_by_theme_then_camp() {
        val flat = KairosJson.parseToJsonElement("""[{"theme":"Dette","camp":"Camp 1","affirmation":"A1","verdict":"vrai"},{"theme":"Dette","camp":"Camp 2","affirmation":"A2"},{"theme":"Retraites","camp":"Camp 1","affirmation":"A3"}]""")
        val b = bulle(flat)!!
        assertEquals(listOf("Dette", "Retraites"), b.map { it.theme })
        assertEquals(listOf("Camp 1", "Camp 2"), b[0].camps.map { it.first })
        val nested = KairosJson.parseToJsonElement("""{"themes":[{"theme":"Dette","chiffre":"3 %","positions":[{"camp":"Camp 1","affirmation":"A1"},{"camp":"Camp 2","affirmation":"A2"}]}]}""")
        val n = bulle(nested)!!.single()
        assertEquals(2, n.camps.size)
        assertEquals("3 %", entryView(n.notes.single()).fields.single().text)
        assertNull("forme inconnue : affichage générique", bulle(KairosJson.parseToJsonElement("""[{"titre":"x"}]""")))
    }

    @Test
    fun avatar_decoding_and_roles() {
        val row = "..FFhhDDAACC...."
        fun json(frames: Int, rows: List<String> = List(16) { row }, ms: String = "300") = KairosJson.parseToJsonElement(
            """{"images":[${List(frames) { rows.joinToString(",", "[", "]") { "\"$it\"" } }.joinToString(",")}],"ms":$ms}""",
        )
        val a = avatarOf(json(2))
        assertNotNull(a)
        assertEquals(2, a!!.frames.size)
        assertEquals(300L, a.ms)
        assertEquals("cases vides omises", 10 * 16, a.frames[0].size)
        assertNull("5 images : refusé", avatarOf(json(5)))
        assertNull("0 image : refusé", avatarOf(json(0)))
        assertNull("ligne trop courte", avatarOf(json(1, List(16) { "..." })))
        assertNull("15 lignes", avatarOf(json(1, List(15) { row })))
        assertNull("caractère inconnu (teinte non prévue)", avatarOf(json(1, List(16) { row.replace('A', 'S') })))
        assertNull(avatarOf(KairosJson.parseToJsonElement("\"x\"")))
        assertEquals("durée bornée", 80L, avatarOf(json(1, ms = "1"))!!.ms)
        // Identité avec avatar cassé : l'accueil se lit quand même.
        val home = KairosJson.decodeFromString(PolHome.serializer(), """{"identites":[{"id":"a","nom":"A","avatar":{"images":42}}]}""")
        assertNull(avatarOf(home.identites.single().avatar))
        assertNotNull(avatarOf(KairosJson.parseToJsonElement("""{"images":[${List(16) { "\"$row\"" }.joinToString(",", "[", "]")}]}""")))

        val colors = SceneColors(Color(0xFF000001), Color(0xFF000002), Color(0xFF000003), Color(0xFF000004), Color(0xFF000005))
        val id = Color(0xFF3B5C95)
        val byChar = a.frames[0].associate { row[it and 0xFF] to avatarColor(it, colors, id) }
        assertEquals(colors['F'], byChar['F'])
        assertEquals(colors['h'], byChar['h'])
        assertEquals(colors['D'], byChar['D'])
        assertEquals(colors['A'], byChar['A'])
        assertEquals(id, byChar['C'])
        assertEquals(id, identityColor("#3B5C95"))
        assertNull(identityColor("bleu"))
    }

    @Test
    fun fold_ignores_accents_and_case() {
        assertEquals("rhetorique", fold("  Rhétorique "))
        assertEquals(RubricKind.Declarations, rubricKind("declarations", "Déclarations vérifiées"))
        assertEquals(RubricKind.Bulle, rubricKind("x", "Hors de ma bulle"))
        assertEquals(RubricKind.Votes, rubricKind("votes"))
    }
}
