package app.kairos.mobile.ui.medias

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.ToolMessage
import app.kairos.mobile.data.DownloadItem
import app.kairos.mobile.data.Downloads
import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.data.MediaResult
import app.kairos.mobile.data.Medias
import app.kairos.mobile.data.MediasSnapshot
import app.kairos.mobile.data.Release
import app.kairos.mobile.ui.today.DoneOutcome
import app.kairos.mobile.ui.today.completeCard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MediasStateTest {
    /** Exemple du contrat (titres fictifs), tel que le module le rend. */
    private val sample = KairosJson.decodeFromString(
        Medias.serializer(),
        """{
          "continue": [
            {"key": "702", "title": "Série Alpha S01E02 « Pilote »", "left_min": 30},
            {"key": "703", "title": "Film Gamma (2023)", "left_min": 100}
          ],
          "ready": [
            {"key": "701", "title": "Série Alpha S01E03 « Retour »", "kind": "episode", "subs": "st_fr", "minutes": 42},
            {"key": "640", "title": "Série Delta S02E01 « Départ »", "kind": "episode", "subs": "recherche", "minutes": 50},
            {"key": "800", "title": "Film Beta (2025)", "kind": "film", "subs": "absents", "minutes": 118},
            {"key": "703", "title": "Film Gamma (2023)", "kind": "film", "subs": "vf", "minutes": 120}
          ],
          "downloads": {
            "items": [
              {"title": "Série Alpha S01E08", "progress": 0.0, "state": "erreur", "why": "Archive incomplète"},
              {"title": "Série Alpha S01E07", "progress": 1.0, "state": "bloque", "why": "Aucun fichier vidéo trouvé"},
              {"title": "Série Alpha S01E04", "progress": 0.5, "eta_min": 12, "state": "en_cours"},
              {"title": "Série Alpha S01E05", "progress": 0.0, "state": "pause"},
              {"title": "Série Alpha S01E06", "progress": 0.0, "state": "attente"}
            ],
            "paused": false
          },
          "upcoming": [
            {"day": "2026-11-09", "time": "21:00", "title": "Série Omega", "what": "S01E01", "have": true},
            {"day": "2026-11-10", "time": "00:30", "title": "Série Alpha", "what": "S02E05", "have": false},
            {"day": "2026-11-11", "title": "Film Beta", "what": "sortie numérique", "have": false}
          ],
          "requests": [{"title": "Film Beta", "state": "en attente"}],
          "errors": []
        }""",
    )
    private val today = LocalDate.parse("2026-11-09")

    @Test
    fun ready_split_by_subtitles() {
        val w = toWatch(sample)
        // Prêts avec du français (VF ou ST FR) d'un côté, le reste regroupé à part.
        assertEquals(listOf("701"), w.ready.map { it.key })
        assertEquals(listOf("640", "800"), w.noFrench.map { it.key })
        assertEquals("ST FR · 42 min", w.ready.single().note)
        assertEquals("Sous-titres en recherche · 50 min", w.noFrench[0].note)
        assertEquals("Sans sous-titres FR · 1 h 58", w.noFrench[1].note)
        assertEquals("VF", subsLabel("vf"))
        assertEquals("ST FR", subsLabel("st_fr"))
        assertTrue(hasFrench("vf") && hasFrench("st_fr"))
        assertFalse(hasFrench("absents") || hasFrench("recherche") || hasFrench(""))
    }

    @Test
    fun continue_first_then_ready() {
        val w = toWatch(sample)
        // Reprises d'abord (temps restant), puis les prêts ; un élément repris n'apparaît qu'une fois.
        assertEquals(listOf("702", "703"), w.resume.map { it.key })
        assertEquals("Reste 30 min", w.resume[0].note)
        assertEquals("Reste 1 h 40", w.resume[1].note)
        assertTrue(w.ready.none { it.key == "703" })
        assertEquals(listOf("702", "703", "701"), w.main.map { it.key })
        // Éléments masqués (geste « vu » en cours) : retirés partout.
        assertEquals(listOf("703", "701"), toWatch(sample, hidden = setOf("702")).main.map { it.key })
        // La carte Espaces compte tout ce qui est prêt, reprises comprises.
        assertEquals(5, readyCount(sample))
        assertEquals(0, readyCount(Medias()))
    }

    @Test
    fun downloads_progress_and_eta_labels() {
        val items = sample.downloads!!.items
        assertEquals("Erreur : Archive incomplète", downloadLine(items[0]))
        assertEquals("Bloqué : Aucun fichier vidéo trouvé", downloadLine(items[1]))
        assertEquals("50 % · reste 12 min", downloadLine(items[2]))
        assertEquals("En pause", downloadLine(items[3]))
        assertEquals("En attente", downloadLine(items[4]))
        assertEquals("100 %", progressLabel(1.0))
        assertEquals("0 %", progressLabel(-0.2))
        assertEquals("33 %", progressLabel(0.333))
        assertEquals("100 %", progressLabel(1.7))
        assertEquals("0 %", progressLabel(Double.NaN))
        assertEquals("12 min", durationLabel(12))
        assertEquals("1 h", durationLabel(60))
        assertEquals("2 h 05", durationLabel(125))
        assertEquals("63 % · reste 2 h 05", downloadLine(DownloadItem("x", progress = 0.634, etaMin = 125, state = "en_cours")))
        assertEquals("63 %", downloadLine(DownloadItem("x", progress = 0.634, state = "en_cours")))
        assertEquals("Erreur", downloadLine(DownloadItem("x", state = "erreur")))
        // Problèmes signalés à part.
        assertEquals(2, stuck(sample.downloads!!))
    }

    @Test
    fun paused_empty_queue_label() {
        // File vide, clients en pause : « en pause », sans liste ; bouton pour reprendre.
        val paused = Downloads(items = emptyList(), paused = true)
        assertEquals("En pause", downloadsHeadline(paused))
        assertEquals("reprise", pauseAction(paused))
        assertEquals("Reprendre", pauseButton(paused))
        assertEquals("Rien en téléchargement", downloadsHeadline(Downloads()))
        assertEquals("pause", pauseAction(Downloads()))
        assertEquals("Mettre en pause", pauseButton(Downloads()))
        assertEquals("En pause · 5 éléments", downloadsHeadline(sample.downloads!!.copy(paused = true)))
        assertEquals("5 éléments · 2 bloqués", downloadsHeadline(sample.downloads!!))
        assertEquals("1 élément", downloadsHeadline(Downloads(listOf(DownloadItem("x", state = "attente")))))
        assertEquals("pause", pauseArgs(Downloads())["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun upcoming_grouped_by_day_with_today_tomorrow() {
        val days = upcomingDays(sample.upcoming!!, today)
        assertEquals(listOf("Aujourd'hui", "Demain", "Mercredi 11/11"), days.map { it.label })
        assertEquals("21:00 · S01E01 · déjà là", releaseNote(days[0].releases.single()))
        assertEquals("00:30 · S02E05", releaseNote(days[1].releases.single()))
        assertEquals("sortie numérique", releaseNote(days[2].releases.single()))
        assertEquals("2026-11-12", upcomingDays(listOf(Release("2026-11-12", title = "x")), today).single().day)
        // Carte Espaces : la prochaine sortie pas encore là.
        assertEquals("Série Alpha S02E05 · demain 00:30", nextRelease(sample, today))
        assertEquals("Film Beta · mercredi 11/11", nextRelease(sample.copy(upcoming = sample.upcoming!!.drop(1).drop(1)), today))
        assertNull(nextRelease(Medias(upcoming = listOf(Release("2026-11-09", title = "x", have = true))), today))
        assertNull(nextRelease(Medias(upcoming = listOf(Release("2026-11-08", title = "x"))), today))
    }

    @Test
    fun section_error_shown_others_kept() {
        val down = KairosJson.decodeFromString(
            Medias.serializer(),
            """{"continue": null, "ready": null,
               "downloads": {"items": [{"title": "A", "progress": 0.2, "state": "en_cours"}], "paused": false},
               "upcoming": [], "requests": [{"title": "B", "state": "en attente"}],
               "errors": [{"section":"continue","message":"lecteur injoignable"},{"section":"ready","message":"lecteur injoignable"}]}""",
        )
        // La section tombée donne sa raison ; les autres s'affichent.
        assertEquals("lecteur injoignable", sectionIssue(down, CONTINUE, READY))
        assertNull(sectionIssue(down, DOWNLOADS))
        assertEquals(1, down.downloads!!.items.size)
        assertEquals(1, down.requests!!.size)
        assertTrue(toWatch(down).main.isEmpty())
        // Bandeau : services en panne (une fois chacun) et téléchargements bloqués.
        assertEquals(listOf("Lecteur injoignable"), problems(down))
        assertEquals(listOf("2 téléchargements bloqués"), problems(sample))
        assertTrue(problems(Medias(downloads = Downloads())).isEmpty())

        // Lecture réussie : remplace l'état ; serveur injoignable : dernier état gardé, raison donnée.
        val fresh = mediasAfter(MediasSnapshot(), Result.success(down), "2026-11-09T10:00")
        assertEquals(down, fresh.medias)
        assertNull(fresh.issue)
        val off = mediasAfter(fresh, Result.failure(ApiError.Offline), "2026-11-09T11:00")
        assertEquals(down, off.medias)
        assertEquals("Hors ligne", off.issue)
        assertEquals("2026-11-09T10:00", off.fetchedAt)
    }

    @Test
    fun watched_removes_item_with_message() = runBlocking {
        val item = toWatch(sample).main.first()
        val hidden = mutableSetOf<String>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        fun mark(m: Any?) { if (m == null) hidden -= item.key else hidden += item.key }

        // Annulé dans les 4 s : rien ne part, l'élément revient.
        val undone = completeCard(watchedCard(item), null, mark = { mark(it) }, undoWindow = { assertTrue(item.key in hidden); true }, call = { _, t, a -> calls += t to a; ToolMessage() })
        assertEquals(DoneOutcome.Undone, undone)
        assertTrue(hidden.isEmpty() && calls.isEmpty())

        // Fenêtre écoulée : « vu » envoyé avec la clé, l'élément reste parti.
        val done = completeCard(watchedCard(item), null, mark = { mark(it) }, undoWindow = { false }, call = { module, t, a ->
            assertEquals(MEDIAS, module)
            calls += t to a
            ToolMessage("« Série Alpha S01E02 » marqué vu.", "done")
        })
        assertEquals(DoneOutcome.Done("« Série Alpha S01E02 » marqué vu.", "done"), done)
        assertEquals("app_media_watched", calls.single().first)
        assertEquals("702", calls.single().second["key"]!!.jsonPrimitive.content)
        assertEquals(setOf("702"), hidden)
        // Confirmation ordinaire : pas de message (il retarderait l'annulation du geste suivant).
        assertNull(watchedNotice(done as DoneOutcome.Done))
        // Décidé sur l'issue, pas sur le libellé.
        assertNull(watchedNotice(DoneOutcome.Done("Libellé reformulé.", "done")))
        assertEquals("Libellé reformulé.", watchedNotice(DoneOutcome.Done("Libellé reformulé.", "already")))
        // Module plus ancien, sans issue : repli sur le libellé.
        assertNull(watchedNotice(DoneOutcome.Done("« Série Alpha S01E02 » marqué vu.")))

        // Déjà vu ou disparu : message clair, l'élément part quand même.
        val gone = "Élément introuvable dans le lecteur (déjà retiré, ou lecteur injoignable)."
        hidden.clear()
        val goneOut = completeCard(watchedCard(item), null, mark = { mark(it) }, undoWindow = { false }, call = { _, _, _ -> ToolMessage(gone, "gone") })
        assertEquals(DoneOutcome.Done(gone, "gone"), goneOut)
        assertEquals(setOf("702"), hidden)
        assertEquals(gone, watchedNotice(goneOut as DoneOutcome.Done))
        assertEquals("« X » est déjà vu.", watchedNotice(DoneOutcome.Done("« X » est déjà vu.")))

        // Retiré de l'état gardé (reprises et prêts), sans attendre la relecture.
        val s = withoutWatched(MediasSnapshot(medias = sample), "703")
        assertTrue(s.medias!!.resume!!.none { it.key == "703" } && s.medias.ready!!.none { it.key == "703" })
        assertEquals(1, s.medias.resume!!.size)

        // Refus du serveur : l'élément revient, avec la raison.
        val refused = completeCard(watchedCard(item), null, mark = { mark(it) }, undoWindow = { false }, call = { _, _, _ -> throw ApiError.Offline })
        assertTrue(refused is DoneOutcome.Refused)
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun search_result_states() {
        val results = KairosJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(MediaResult.serializer()),
            """[{"id": 1, "media_type": "tv", "title": "Série Alpha", "year": 2024, "have": true, "requested": false},
                {"id": 2, "media_type": "movie", "title": "Film Beta", "have": false, "requested": true},
                {"id": 3, "media_type": "movie", "title": "Film Gamma", "year": 2023, "have": false, "requested": false}]""",
        )
        assertEquals(listOf(Ask.Have, Ask.Requested, Ask.Open), results.map { askState(it, asked = emptyMap()) })
        assertEquals(listOf("Déjà là", "Déjà demandé", "Demander"), results.map { askState(it, emptyMap()).label })
        assertFalse(Ask.Have.enabled || Ask.Requested.enabled)
        assertTrue(Ask.Open.enabled)
        // Demandé depuis l'appareil : l'état rendu par le module, sans attendre une nouvelle recherche.
        assertEquals(Ask.Requested, askState(results[2], asked = mapOf(askKey(results[2]) to Ask.Requested)))
        assertEquals(Ask.Have, askState(results[2], asked = mapOf(askKey(results[2]) to Ask.Have)))
        assertEquals("Série · 2024", resultNote(results[0]))
        assertEquals("Film", resultNote(results[1]))
        val args = requestArgs(results[2])
        assertEquals(3L, args["id"]!!.jsonPrimitive.long)
        assertEquals("movie", args["media_type"]!!.jsonPrimitive.content)
        // Moins de 2 caractères utiles : pas de recherche.
        assertFalse(canSearch(" a "))
        assertTrue(canSearch("ab"))
    }

    @Test
    fun request_answer_gives_the_real_state() {
        assertEquals(Ask.Have, askAfter("Film Alpha est déjà disponible."))
        assertEquals(Ask.Requested, askAfter("Film Alpha est déjà demandé."))
        assertEquals(Ask.Requested, askAfter("Film Alpha demandé ; suivi jusqu'à ce qu'il soit regardable."))
    }

    @Test
    fun pause_choices_without_known_state_offer_both() {
        assertEquals(listOf("Reprendre" to "reprise"), pauseChoices(Downloads(paused = true)))
        assertEquals(listOf("Mettre en pause" to "pause"), pauseChoices(Downloads()))
        // Section tombée : état inconnu, les deux gestes restent possibles.
        assertEquals(listOf("Mettre en pause" to "pause", "Reprendre" to "reprise"), pauseChoices(null))
    }
}
