package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.jeux.CAP
import app.kairos.mobile.ui.politique.jeux.Fin
import app.kairos.mobile.ui.politique.jeux.Invitation
import app.kairos.mobile.ui.politique.jeux.JEUX_REGISTRE
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.PARTIE
import app.kairos.mobile.ui.politique.jeux.Page
import app.kairos.mobile.ui.politique.jeux.Palier
import app.kairos.mobile.ui.politique.jeux.Partie
import app.kairos.mobile.ui.politique.jeux.Progression
import app.kairos.mobile.ui.politique.jeux.avanceJeu
import app.kairos.mobile.ui.politique.jeux.avancer
import app.kairos.mobile.ui.politique.jeux.carte
import app.kairos.mobile.ui.politique.jeux.damp
import app.kairos.mobile.ui.politique.jeux.dragOffset
import app.kairos.mobile.ui.politique.jeux.jeu.Exemple
import app.kairos.mobile.ui.politique.jeux.jeuxOf
import app.kairos.mobile.ui.politique.jeux.kit.Forme
import app.kairos.mobile.ui.politique.jeux.kit.GlyphMode
import app.kairos.mobile.ui.politique.jeux.kit.Glyphes
import app.kairos.mobile.ui.politique.jeux.kit.PIXEL_ICONS
import app.kairos.mobile.ui.politique.jeux.kit.Px
import app.kairos.mobile.ui.politique.jeux.kit.cadranNiveau
import app.kairos.mobile.ui.politique.jeux.kit.levierCran
import app.kairos.mobile.ui.politique.jeux.kit.pxButton
import app.kairos.mobile.ui.politique.jeux.nouvellePartie
import app.kairos.mobile.ui.politique.jeux.progressCells
import app.kairos.mobile.ui.politique.jeux.recommencer
import app.kairos.mobile.ui.politique.jeux.release
import app.kairos.mobile.ui.politique.jeux.reprendre
import app.kairos.mobile.ui.politique.jeux.resoudre
import app.kairos.mobile.ui.politique.jeux.restX
import app.kairos.mobile.ui.politique.jeux.themesOf
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.model.POL_FILES
import app.kairos.mobile.ui.politique.kit.verdictCode
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JeuxTest {
    private fun data(json: String) = PolData(mapOf("jeux" to PolJson.parseToJsonElement(json).jsonObject))
    private val extrait = data(javaClass.getResource("/jeux-extrait.json")!!.readText())

    // ---------- Données ----------

    @Test fun jeux_file_is_loaded() = assertTrue("jeux" in POL_FILES)

    @Test fun real_extract_decodes() {
        val jeux = jeuxOf(extrait)
        val m = jeux.getValue("independant").manches.single()
        assertEquals("fondapol", m.id)
        assertEquals(3, (m.d["bonne"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertNull(m.reponse.verdict)
        assertEquals("fondapol", m.reponse.refs.single().ev)
        assertTrue(jeux.getValue("quelle-gravite").params.containsKey("crans"))
        val themes = themesOf(extrait, jeux)
        assertEquals(listOf("Démêle", "Mesure", "Retrace", "Relie"), themes.map { it.nom })
        assertEquals(listOf("quelle-gravite", "le-juste-cout", "independant"), themes[1].jeux)
        // Rubriques : celles de la porte de même id.
        assertEquals(DOORS[1].rubrics, themes[1].rubrics)
    }

    @Test fun tolerant_decoding() {
        val d = data("""{"THEMES":[{"id":"bilan","jeux":[{"slug":"x"},"y",3]}],
            "JEUX":{"x":{"theme":"bilan","inconnu":1,"manches":[{"id":"a","d":{"k":1}},{"id":""},{"id":"a"},"z",{"id":"b","reponse":{"verdict":null,"sources":[["Org","https://example.org"]]}}]},"y":"pas un objet"}}""")
        val jeux = jeuxOf(d)
        assertEquals(setOf("x"), jeux.keys)
        assertEquals(listOf("a", "b"), jeux.getValue("x").manches.map { it.id })
        val t = themesOf(d, jeux)
        // Nom absent : celui de la porte ; jeux : slugs reconnus seulement.
        assertEquals(listOf("x", "y"), t.single { it.id == "bilan" }.jeux)
        assertEquals(DOORS.single { it.id == "bilan" }.kicker, t.single { it.id == "bilan" }.nom)
        // Aucun fichier : thèmes des portes, jeux vides.
        assertEquals(4, themesOf(PolData(emptyMap())).size)
        assertTrue(jeuxOf(PolData(emptyMap())).isEmpty())
    }

    @Test fun verdict_codes_and_labels() {
        assertEquals("plutot-faux", verdictCode("Plutôt faux"))
        assertEquals("coherent", verdictCode("coherent"))
        assertEquals("ecart", verdictCode("Incohérent"))
        assertEquals("ambigu", verdictCode("flou"))
        assertEquals("vrai", verdictCode("solide"))
        assertNull(verdictCode(""))
        assertNull(verdictCode("n'importe quoi"))
    }

    // ---------- Piste ----------

    @Test fun track_positions_and_drag() {
        val w = 300f
        val gap = 25f
        assertEquals(0f, restX(Page.Question, w, gap))
        assertEquals(-325f, restX(Page.Reponse, w, gap))
        assertEquals(-625f, restX(Page.Suivant, w, gap))
        // Question : suit le doigt vers la gauche jusqu'à la réponse ; vers la droite, résiste.
        assertEquals(-100f, dragOffset(Page.Question, -100f, w, gap).offset)
        assertEquals(-325f, dragOffset(Page.Question, -900f, w, gap).offset)
        assertEquals(35f, dragOffset(Page.Question, 100f, w, gap).offset, 0.01f)
        // Réponse vers « suivant » : 0,35 du doigt, butée dure à 1/4 de largeur, armée à la butée.
        val cap = CAP * w
        val small = dragOffset(Page.Reponse, -100f, w, gap)
        assertEquals(-35f, small.offset, 0.01f)
        assertFalse(small.armed)
        val far = dragOffset(Page.Reponse, -2000f, w, gap)
        assertEquals(-cap, far.offset, 0.01f)
        assertTrue(far.armed)
        assertEquals(1f, far.pull, 0.01f)
        // Amorti continu et borné.
        assertEquals(10f, damp(10f, 100f))
        assertEquals(100f, damp(1000f, 100f), 0.001f)
        assertTrue(damp(85f, 100f) > 80f && damp(85f, 100f) < damp(95f, 100f) && damp(95f, 100f) < 100f)
    }

    @Test fun release_targets() {
        val dp = 2f
        assertEquals(Page.Reponse, release(Page.Question, -120f, 0f, false, dp))
        assertEquals(Page.Question, release(Page.Question, -100f, 0f, false, dp))
        assertEquals(Page.Reponse, release(Page.Question, -50f, -1200f, false, dp))
        assertEquals(Page.Question, release(Page.Reponse, 120f, 0f, false, dp))
        assertEquals(Page.Suivant, release(Page.Reponse, -500f, 0f, true, dp))
        assertEquals(Page.Reponse, release(Page.Reponse, -500f, 0f, false, dp))
    }

    @Test fun invitation_schedule() {
        var i = Invitation()
        assertEquals(10_000L, i.delay(false))
        i = i.played()
        assertEquals(3_000L, i.delay(true))
        i = i.played().played()
        assertNull(i.delay(true))
        assertTrue(i.off)
        // Un tirage d'au moins 25 % de la butée arrête tout.
        assertNull(Invitation().pulled(0.3f).delay(false))
        assertEquals(10_000L, Invitation().pulled(0.2f).delay(false))
    }

    // ---------- Parties et progression ----------

    private val vivier = (1..10).map { "m$it" }

    @Test fun deck_draws_unseen_first() {
        val p = Progression(faites = setOf(carte("j", "m1"), carte("j", "m2")))
        val partie = nouvellePartie(p, "j", vivier, Random(1))!!
        assertEquals(PARTIE, partie.cartes.size)
        assertTrue(partie.cartes.none { it == "m1" || it == "m2" })
        // Cycle : les cartes déjà jouées sont exclues ; plus rien : null.
        val cyc = Progression(cycle = mapOf("j" to vivier.take(8)))
        assertEquals(setOf("m9", "m10"), nouvellePartie(cyc, "j", vivier, Random(1))!!.cartes.toSet())
        assertNull(nouvellePartie(Progression(cycle = mapOf("j" to vivier)), "j", vivier))
        // Reprise : la partie en cours, sauf si une carte a disparu du vivier.
        val saved = Progression(parties = mapOf("j" to Partie(listOf("m3", "m4"), 1)))
        assertEquals("m4", reprendre(saved, "j", vivier)!!.courante)
        assertEquals(PARTIE, reprendre(saved, "j", vivier.drop(4))!!.cartes.size)
    }

    @Test fun solving_counts_once_and_paliers() {
        val viviers = mapOf("a" to listOf("1", "2"), "b" to listOf("3", "4"))
        var p = Progression()
        var r = resoudre(p, "t", viviers, "a", "1", sansErreur = true)
        assertEquals(Palier.PremierCoup, r.second)
        p = r.first
        assertTrue(carte("a", "1") in p.premier)
        // Moitié franchie (2/4), une fois.
        r = resoudre(p, "t", viviers, "a", "2", sansErreur = false)
        assertEquals(Palier.Moitie, r.second)
        p = r.first
        assertFalse(carte("a", "2") in p.premier)
        // Déjà faite : rien, même sans erreur ; ne devient pas « premier coup ».
        r = resoudre(p, "t", viviers, "a", "2", sansErreur = true)
        assertNull(r.second)
        assertFalse(carte("a", "2") in r.first.premier)
        p = resoudre(p, "t", viviers, "b", "3", false).first
        r = resoudre(p, "t", viviers, "b", "4", false)
        assertEquals(Palier.Complet, r.second)
        assertTrue("t:complet" in r.first.paliers)
        assertEquals(2, avanceJeu(r.first, "a", viviers.getValue("a")).n)
        assertEquals(12, progressCells(app.kairos.mobile.ui.politique.jeux.Avance(4, 4, 1)))
        assertEquals(6, progressCells(app.kairos.mobile.ui.politique.jeux.Avance(2, 4, 0)))
    }

    @Test fun end_of_deck_and_restart() {
        val v = listOf("1", "2", "3")
        var p = Progression(parties = mapOf("j" to Partie(listOf("1", "2"))))
        p = resoudre(p, "t", mapOf("j" to v), "j", "1", true).first
        var (q, fin) = avancer(p, "j", v)
        assertNull(fin)
        assertEquals("2", q.parties.getValue("j").courante)
        q = resoudre(q, "t", mapOf("j" to v), "j", "2", true).first
        avancer(q, "j", v).let { (q2, f) -> assertEquals(Fin.Partie, f); assertNull(q2.parties["j"]); q = q2 }
        // Dernière carte du cycle : fin de vivier ; « Recommencer » vide le cycle, la progression reste.
        q = q.copy(parties = mapOf("j" to Partie(listOf("3"))))
        q = resoudre(q, "t", mapOf("j" to v), "j", "3", true).first
        val (q3, f3) = avancer(q, "j", v)
        assertEquals(Fin.Vivier, f3)
        val again = recommencer(q3, "j", v, Random(2))
        assertTrue(again.cycle["j"].isNullOrEmpty())
        assertEquals(3, again.faites.size)
        assertEquals(3, again.parties.getValue("j").cartes.size)
        // Persistance : aller-retour JSON.
        val s = PolJson.encodeToString(Progression.serializer(), again)
        assertEquals(again, PolJson.decodeFromString(Progression.serializer(), s))
    }

    // ---------- Contrat, registre, kit ----------

    @Test fun example_game_decodes_and_validates() {
        val ex = Exemple("x")
        fun m(d: String) = Manche("1", d = PolJson.parseToJsonElement(d).jsonObject)
        assertNotNull(ex.decoder(m("""{"choix":["A","B","C"],"bonne":1,"famille":"ignorée"}""").d))
        assertNotNull(ex.decoder(m("""{"choix":[{"id":"i","nom":"Nom A"},{"ville":"Ville B"}],"bonne":0}""").d))
        assertNull(ex.decoder(m("""{"choix":["A","B"],"bonne":5}""").d))
        assertNull(ex.decoder(m("""{"bonne":1}""").d))
        assertEquals(JEUX_REGISTRE.size, JEUX_REGISTRE.keys.toSet().size)
        JEUX_REGISTRE.forEach { (k, v) -> assertEquals(k, v.slug) }
    }

    @Test fun pixel_button_and_icons() {
        val b = pxButton(Forme.Valider.w, Forme.Valider.h, Forme.Valider.r, Glyphes.check, GlyphMode.Grave)
        assertEquals(22, b.w)
        assertEquals(25, b.h)
        assertTrue(b.top.any { it.k == Px.Ic })
        assertTrue(b.top.any { it.k == Px.Rf })
        // Le dessus reste dans la platine.
        assertTrue(b.top.all { it.x in 0 until b.w && it.y in 0 until b.h })
        val round = pxButton(Forme.Rond.w, Forme.Rond.h, Forme.Rond.r, Glyphes.minus, GlyphMode.Blanc)
        assertTrue(round.top.any { it.k == Px.Iw })
        listOf("citation", "coche", "suivante").forEach { assertTrue(it, it in PIXEL_ICONS) }
        PIXEL_ICONS.values.forEach { g -> assertEquals(16, g.size); assertTrue(g.all { it.length == 16 }) }
    }

    @Test fun dial_and_lever_notches() {
        assertEquals(1, cadranNiveau(10f, 30f))
        assertEquals(4, cadranNiveau(62f, 30f))
        assertEquals(2, cadranNiveau(30f, 5f))
        assertEquals(3, cadranNiveau(42f, 5f))
        // Sous le pivot : ramené à l'horizontale.
        assertEquals(1, cadranNiveau(0f, 44f))
        assertEquals(0, levierCran(0f, 300f, 5))
        assertEquals(2, levierCran(150f, 300f, 5))
        assertEquals(4, levierCran(500f, 300f, 5))
        assertEquals(0, levierCran(10f, 300f, 1))
    }
}
