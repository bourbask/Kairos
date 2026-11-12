package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.screens.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ContextTest {
    private fun file(s: String) = PolJson.parseToJsonElement(s).jsonObject
    private fun data(vararg f: Pair<String, String>) = PolData(f.associate { (k, v) -> k to file(v) })

    @Test fun procedure_stage_index() {
        assertEquals(1, procedureStage("enq")!!.step)
        assertEquals(2, procedureStage("mex")!!.step)
        assertEquals(3, procedureStage("proc")!!.step)
        assertEquals(4, procedureStage("nd")!!.step)
        assertEquals(5, procedureStage("def")!!.step)
        assertTrue(procedureStage("rel")!!.relaxe)
        assertFalse(procedureStage("nd")!!.relaxe)
        assertNull(procedureStage("inconnu")); assertNull(procedureStage(null))
        assertEquals("solide", stageTone("rel")); assertEquals("fragile", stageTone("def")); assertNull(stageTone("enq"))
    }

    @Test fun money_rows_tolerate_missing_values() {
        val d = data("argent" to """{"PARTIS":[["Parti Bleu",17.4,5.3,34.5],["Parti Vert",4.3,null,null],["Parti Rouge"],[],"x",["",1,2,3]],
            "C22":[["Candidat A","27 %",16.5,10.4,"Note"],["Candidat B","5 %",null,null],["Candidat C"]],
            "AIDE":{"rows":[{"l":"Parti Bleu","v":14.8},{"l":"Parti Vert"},{"v":3},5],"scale":{"max":17}}}""")
        val p = partisOf(d)
        assertEquals(listOf("Parti Bleu", "Parti Vert", "Parti Rouge"), p.map { it.parti })
        assertEquals("non trouvé", moneyLabel(p[1].dette)); assertEquals("non trouvé", netLabel(p[1].net))
        assertEquals("+34,5 M€", netLabel(p[0].net)); assertEquals("17,4 M€", moneyLabel(p[0].dons)); assertEquals("4,30 M€", moneyLabel(4.3))
        assertEquals("−11,0 M€", netLabel(-11.0))
        val c = campagnesOf(d)
        assertEquals(3, c.size); assertNull(c[1].depense); assertEquals("", c[2].score)
        assertEquals(16.5, campagnesMax(c), 1e-9)
        val a = aideOf(d)
        assertEquals(2, a.rows.size); assertNull(a.rows[1].v); assertEquals(17 * 1.28, a.max, 1e-9)
        assertEquals(0f, barFraction(null, 10.0)); assertEquals(0f, barFraction(5.0, 0.0)); assertEquals(1f, barFraction(50.0, 10.0)); assertEquals(0.5f, barFraction(5.0, 10.0))
    }

    @Test fun context_states_survive_empty_and_wrong_types() {
        val empty = PolData(emptyMap())
        assertTrue(affairesOf(empty).isEmpty() && agendaOf(empty).isEmpty() && lobbyOf(empty).isEmpty() && financingOf(empty).isEmpty())
        assertTrue(partisOf(empty).isEmpty() && campagnesOf(empty).isEmpty() && aideOf(empty).rows.isEmpty())
        val m = mediasState(empty)
        assertTrue(m.owners.isEmpty() && m.unsellable.isEmpty() && m.audience.rows.isEmpty() && m.reg.isEmpty() && m.studies.isEmpty() && m.polls.isEmpty())
        assertEquals(1.0, aideOf(empty).max, 0.0); assertEquals(1.0, m.audience.max, 0.0)
        val bad = data(
            "probite" to """{"AFF":"x","AGENDA":{"a":1},"LOBBY":[1,"a",{"t":[1]}]}""",
            "argent" to """{"AIDE":3,"PARTIS":7,"C22":{"a":1},"FIN":[null]}""",
            "medias" to """{"OWNERS":"x","PROTECTED":[1],"AUD":{"rows":"x","max":"y"},"REG":[1,["d"]],"STUDIES":3,"POLLS":[[null,2,3],{"a":1}]}""",
        )
        assertTrue(affairesOf(bad).isEmpty() && agendaOf(bad).isEmpty() && lobbyOf(bad).isEmpty() && financingOf(bad).isEmpty())
        assertTrue(partisOf(bad).isEmpty() && campagnesOf(bad).isEmpty() && aideOf(bad).rows.isEmpty())
        val mb = mediasState(bad)
        assertTrue(mb.owners.isEmpty() && mb.unsellable.isEmpty() && mb.audience.rows.isEmpty() && mb.reg.isEmpty() && mb.studies.isEmpty() && mb.polls.isEmpty())
    }

    @Test fun affaires_keep_all_fields_and_cards_carry_presumption() {
        val d = data("probite" to """{"AFF":[["Candidat A","Parti Bleu","Faits A","enq","Enquête ouverte","03/2026","https://example.org/a"],["Candidat B","","Faits B","zzz","","",""]]}""")
        val a = affairesOf(d)
        assertEquals(2, a.size); assertEquals("Enquête", a[0].stage!!.label); assertNull(a[1].stage)
        assertTrue(PRESUMPTION in affaireTableCard(a[0]).lines)
        assertEquals("Candidat B", affaireTableCard(a[1]).title)
    }

    @Test fun agenda_sorted_with_days_left() {
        val d = data(
            "meta" to """{"verifie_le":"2026-11-12"}""",
            "probite" to """{"AGENDA":[["18/04/2027","Premier tour de l'élection."],["Avant avril 2027","Décision attendue."],["30/11/2026","Arrêt d'appel."],["01/11/2026","Passé."],["x",""]]}""",
        )
        val a = agendaOf(d)
        assertEquals(listOf("01/11/2026", "30/11/2026", "18/04/2027", "Avant avril 2027"), a.map { it.date })
        assertNull(a[0].inDays); assertEquals(18, a[1].inDays); assertNull(a[3].inDays)
        assertTrue(a[2].key); assertFalse(a[1].key)
        assertNull(agendaOf(data("probite" to """{"AGENDA":[["30/11/2026","Arrêt."]]}""")).single().inDays)
        assertNull(parseDay("31/02/2026"))
    }

    @Test fun medias_state_reads_rows_and_labels() {
        val d = data("medias" to """{"OWNERS":[{"o":"Groupe A","m":["Titre 1"]},{"o":""}],"PROTECTED":[{"o":"Titre P","n":"Fonds"}],
            "AUD":{"rows":[["Chaîne 1",3.4,3.6],["Chaîne 2",2.0]],"max":4},"REG":[["2024","Décision","note"],["2025","Autre"]],
            "STUDIES":[["causal","Auteur 2007","Pays","Résumé"],["autre","X","","Y"]],"POLLS":[["Institut A","Propriétaire","Clients"]]}""")
        val m = mediasState(d)
        assertEquals(listOf("Groupe A"), m.owners.map { it.o }); assertEquals(listOf("Titre 1"), m.owners[0].m)
        assertEquals(4.0, m.audience.max, 0.0); assertNull(m.audience.rows[1].now)
        assertEquals("", m.reg[1].note)
        assertEquals(listOf("Effet causal", "Étude"), m.studies.map { it.kindLabel })
        assertEquals("Effet causal · Pays", studyTableCard(m.studies[0]).kicker)
        assertEquals("Institut A", m.polls.single().institute)
    }

    @Test fun unique_id_refuses_shared_alias() {
        val ids = """[{"id":"a","nom":"Candidat A","alias":["Nom partagé"]},{"id":"b","nom":"Candidat B","alias":["Nom partagé"]}]"""
        val d = PolData(mapOf("identites" to file("""{"_liste":$ids}""")))
        assertEquals("a", d.uniqueId("Candidat A")); assertNull(d.uniqueId("Nom partagé")); assertNull(d.uniqueId("Inconnu"))
        assertEquals("CA", initials("Candidat Alpha")); assertEquals("", initials("x y"))
    }
}
