package app.kairos.mobile.ui.documents

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.DocDeadline
import app.kairos.mobile.data.DocHit
import app.kairos.mobile.data.Documents
import app.kairos.mobile.data.DocumentsSnapshot
import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.ui.kit.Tone
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DocumentsStateTest {
    /** Exemple du contrat (organismes fictifs), tel que le module le rend. */
    private val sample = KairosJson.decodeFromString(
        Documents.serializer(),
        """{
          "deadlines": [
            {"date": "2026-10-01", "kind": "fin", "what": "contrat d'abonnement", "org": "Fournisseur Exemple", "private": false, "overdue": true},
            {"date": "2026-11-14", "kind": "résiliation", "what": "contrat d'assurance habitation", "org": "Assureur Exemple", "private": false, "overdue": false},
            {"date": "2026-12-09", "kind": "expiration", "what": "carte nationale d'identité", "private": true, "overdue": false},
            {"date": "2027-01-20", "kind": "contrôle", "what": "procès-verbal de contrôle technique", "private": false, "overdue": false},
            {"date": "2026-11-01", "kind": "échéance", "what": "facture", "private": false, "overdue": true}
          ],
          "missing": [
            {"domain": "Véhicule", "item": "Certificat d'immatriculation", "present": true},
            {"domain": "Logement", "item": "Bail", "present": true},
            {"domain": "Logement", "item": "Attestation d'assurance", "present": false},
            {"domain": "Identité", "item": "Passeport", "present": false}
          ],
          "natures": [
            {"name": "Assurances", "count": 12, "unreadable": 2, "private": false},
            {"name": "Identité", "count": 3, "unreadable": 0, "private": true}
          ],
          "extraction": {"done": 40, "total": 52}
        }""",
    )
    private val today = LocalDate.parse("2026-11-09")

    @Test
    fun deadlines_overdue_first_with_time_left() {
        val rows = deadlineRows(sample, today)
        // Retards d'abord (du plus ancien), puis à venir par date.
        assertEquals(listOf("2026-10-01", "2026-11-01", "2026-11-14", "2026-12-09", "2027-01-20"), rows.map { it.date })
        assertEquals("En retard de 39 j", rows[0].left)
        assertEquals("En retard de 8 j", rows[1].left)
        assertEquals("Dans 5 j", rows[2].left)
        assertEquals("14/11/2026", rows[2].day)
        assertEquals("Contrat d'assurance habitation", rows[2].title)
        assertEquals("Résiliation · Assureur Exemple", rows[2].note)
        assertEquals("Aujourd'hui", timeLeftLabel(today, today))
        assertEquals("Demain", timeLeftLabel(today.plusDays(1), today))
        // Le drapeau du module prime : en retard même si la date ne l'est pas encore.
        val flagged = DocDeadline(date = "2026-11-20", kind = "fin", what = "bail", overdue = true)
        assertEquals("2026-11-20", deadlineRows(Documents(deadlines = listOf(sample.deadlines[1], flagged)), today).first().date)
        // Date illisible : gardée, en fin, sans temps restant.
        val odd = deadlineRows(Documents(deadlines = listOf(DocDeadline(date = "bientôt", what = "x"), sample.deadlines[1])), today)
        assertEquals(listOf("2026-11-14", "bientôt"), odd.map { it.date })
        assertNull(odd[1].left)
        assertEquals("bientôt", odd[1].day)
    }

    @Test
    fun deadline_tone_by_delay() {
        assertEquals(Tone.Urgent, deadlineTone(sample.deadlines[0], today))
        assertEquals(Tone.Attention, deadlineTone(sample.deadlines[1], today))
        assertEquals(Tone.Neutral, deadlineTone(sample.deadlines[3], today))
        // Ambre sous 30 jours (le jour même compris), neutre à 30 jours et au-delà.
        fun at(days: Long) = deadlineTone(DocDeadline(date = today.plusDays(days).toString()), today)
        assertEquals(Tone.Attention, at(0))
        assertEquals(Tone.Attention, at(29))
        assertEquals(Tone.Neutral, at(30))
        assertEquals(Tone.Urgent, at(-1))
        assertEquals(Tone.Urgent, deadlineTone(DocDeadline(date = "2026-12-31", overdue = true), today))
        assertEquals(Tone.Neutral, deadlineTone(DocDeadline(date = "?"), today))
    }

    @Test
    fun private_deadline_label() {
        // Nature privée : le texte libre (type extrait) n'est jamais affiché, seulement le motif calculé.
        val leak = DocDeadline(date = "2026-12-09", kind = "expiration", what = "passeport de Untel", org = "Organisme", private = true)
        val shown = deadlineRows(Documents(deadlines = listOf(leak)), today).single()
        assertEquals("Document privé · Expiration", shown.title)
        assertEquals("", shown.note)
        assertFalse("Untel" in shown.title || "Organisme" in shown.title + shown.note)
        assertFalse("Untel" in nextDeadline(Documents(deadlines = listOf(leak)), today)!!)
        assertEquals("Document privé", deadlineRows(Documents(deadlines = listOf(leak.copy(kind = ""))), today).single().title)
    }

    @Test
    fun deadline_without_date_is_tolerated() {
        val d = app.kairos.mobile.data.KairosJson.decodeFromString(Documents.serializer(), """{"deadlines":[{"kind":"fin"}]}""")
        assertEquals(1, d.deadlines.size)
    }

    @Test
    fun passage_markers_unpaired_or_empty() {
        assertEquals(listOf("a" to true), passageSpans("«a"))
        assertEquals(listOf("a" to false), passageSpans("a»"))
        assertEquals(emptyList<Pair<String, Boolean>>(), passageSpans("«»"))
    }

    @Test
    fun missing_first_then_present() {
        val p = pieces(sample)
        // Manquantes d'abord, ordre de la liste de contrôle (par domaine) gardé.
        assertEquals(listOf("Attestation d'assurance", "Passeport"), p.missing.map { it.item })
        assertEquals(listOf("Certificat d'immatriculation", "Bail"), p.present.map { it.item })
        assertEquals("2 à fournir · 2 présentes", piecesHeadline(p))
        assertEquals("Tout est là · 1 présente", piecesHeadline(pieces(Documents(missing = listOf(sample.missing[0])))))
    }

    @Test
    fun empty_index_label() {
        val empty = KairosJson.decodeFromString(
            Documents.serializer(),
            """{"deadlines": [], "missing": [{"domain": "Identité", "item": "Passeport", "present": false}],
               "natures": [], "extraction": {"done": 0, "total": 0}}""",
        )
        assertTrue(isEmptyIndex(empty))
        assertTrue(isEmptyIndex(sample.copy(extraction = sample.extraction.copy(done = 0, total = 0))))
        assertFalse(isEmptyIndex(sample))
        assertEquals("Aucun document indexé pour l'instant.", EMPTY_INDEX)
        // Avancement de l'extraction et classement.
        assertEquals("40 sur 52 documents lus", extractionLabel(sample.extraction))
        assertEquals("52 documents lus", extractionLabel(sample.extraction.copy(done = 52)))
        assertEquals("12 documents · 2 sans texte lisible", natureNote(sample.natures[0]))
        assertEquals("3 documents · privé", natureNote(sample.natures[1]))
        assertEquals("1 document", natureNote(sample.natures[0].copy(count = 1, unreadable = 0)))

        // Lecture réussie : remplace l'état ; serveur injoignable : dernier état gardé, raison donnée.
        val fresh = documentsAfter(DocumentsSnapshot(), Result.success(sample), "2026-11-09T10:00")
        assertEquals(sample, fresh.documents)
        val off = documentsAfter(fresh, Result.failure(ApiError.Offline), "2026-11-09T11:00")
        assertEquals(sample, off.documents)
        assertEquals("Hors ligne", off.issue)
        assertEquals("2026-11-09T10:00", off.fetchedAt)
    }

    @Test
    fun search_result_lines() {
        val hits = KairosJson.decodeFromString(
            ListSerializer(DocHit.serializer()),
            """[{"name": "conditions-generales.pdf", "nature": "Assurances", "date": "2026-03-14",
                 "passage": "…Garantie bris de glace : «franchise» de 150 euros par sinistre…"},
                {"name": "note.txt", "nature": "Divers", "passage": "sans marque"}]""",
        )
        assertEquals("Assurances · 14/03/2026", hitNote(hits[0]))
        assertEquals("Divers", hitNote(hits[1]))
        // Mots trouvés en évidence, marqueurs retirés.
        assertEquals(
            listOf("…Garantie bris de glace : " to false, "franchise" to true, " de 150 euros par sinistre…" to false),
            passageSpans(hits[0].passage),
        )
        assertEquals(listOf("sans marque" to false), passageSpans(hits[1].passage))
        // Marqueur orphelin : ouvrant coupé en fin, fermant coupé en début ; jamais affiché.
        assertEquals(listOf("…de " to false, "franch…" to true), passageSpans("…de «franch…"))
        assertEquals(listOf("ise de 150" to false), passageSpans("ise» de 150"))
        assertEquals(listOf("a" to true, "b" to false), passageSpans("«a»b»"))
        assertTrue(passageSpans("").isEmpty())
        assertFalse(canSearch("  "))
        assertTrue(canSearch("bail"))
    }

    @Test
    fun card_next_deadline_and_overdue_count() {
        assertEquals("Résiliation · contrat d'assurance habitation · dans 5 j", nextDeadline(sample, today))
        assertEquals(2, overdueCount(sample, today))
        assertEquals("2 en retard", overdueLabel(2))
        assertEquals("1 en retard", overdueLabel(1))
        assertNull(overdueLabel(0))
        // Prochaine échéance privée : jamais d'organisme ni de motif.
        val priv = Documents(deadlines = listOf(sample.deadlines[2].copy(org = "Organisme")))
        assertEquals("Document privé · Expiration · dans 30 j", nextDeadline(priv, today))
        assertNull(nextDeadline(Documents(deadlines = listOf(sample.deadlines[0])), today))
        assertEquals(0, overdueCount(Documents(), today))
    }
}
