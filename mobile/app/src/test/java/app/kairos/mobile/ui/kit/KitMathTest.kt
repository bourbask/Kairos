package app.kairos.mobile.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KitMathTest {
    private val nnbsp = ' '
    private val nbsp = ' '

    @Test
    fun euro_format_french() {
        assertEquals("1${nnbsp}234,56${nbsp}€", formatEuro(1234.56))
        assertEquals("0,00${nbsp}€", formatEuro(0.0))
        assertEquals("-12,50${nbsp}€", formatEuro(-12.5))
        assertEquals("1${nnbsp}234${nnbsp}567,00${nbsp}€", formatEuro(1_234_567.0))
        // Arrondi commercial au centime, pas l'arrondi bancaire.
        assertEquals("2,35${nbsp}€", formatEuro(2.345))
        assertEquals("1${nnbsp}235${nbsp}€", formatEuro(1234.56, cents = false))
        assertEquals("0${nbsp}€", formatEuro(-0.2, cents = false))
    }

    @Test
    fun masked_amount_never_shows_digits() {
        val values = listOf(0.0, 7.0, -42.5, 1234.56, 9_999_999.99, Double.NaN)
        val fmt: (Double) -> String = { formatEuro(it) }
        val texts = values.map { amountText(it, masked = true) } +
            values.map { amountText(it, masked = true, cents = false) } +
            describeBar("Courses", 180.0, 200.0, fmt, masked = true) +
            describeBar("Loisirs", 250.0, 200.0, fmt, masked = true) +
            describeLine("Évolution", listOf(1000.0, 1200.0, 900.0), fmt, masked = true) +
            describeDonut("Répartition", listOf("A" to 30.0, "B" to 70.0), fmt, masked = true)
        for (t in texts) {
            assertFalse("chiffre visible dans « $t »", t.any { it.isDigit() })
            assertTrue(t.isNotBlank())
        }
        // Démasqué, les montants apparaissent bien.
        assertTrue(describeBar("Courses", 180.0, 200.0, fmt, masked = false).contains("180,00"))
    }

    @Test
    fun bar_tone_thresholds() {
        assertEquals(Tone.Neutral, barTone(0.0, 100.0))
        assertEquals(Tone.Neutral, barTone(80.0, 100.0))
        // Exactement 80 % reste neutre, quelle que soit l'échelle (centimes compris).
        assertEquals(Tone.Neutral, barTone(160.0, 200.0))
        assertEquals(Tone.Neutral, barTone(0.24, 0.3))
        assertEquals(Tone.Neutral, barTone(56.0, 70.0))
        assertEquals(Tone.Attention, barTone(80.01, 100.0))
        assertEquals(Tone.Attention, barTone(100.0, 100.0))
        assertEquals(Tone.Urgent, barTone(100.01, 100.0))
        // Sans plafond : tout usage est un dépassement.
        assertEquals(Tone.Neutral, barTone(0.0, 0.0))
        assertEquals(Tone.Urgent, barTone(5.0, 0.0))
        assertEquals(0.5f, fill(50.0, 100.0))
        assertEquals(1f, fill(150.0, 100.0))
        assertEquals(0f, fill(-5.0, 100.0))
    }

    @Test
    fun line_scale_handles_flat_and_single_point() {
        assertTrue(linePoints(emptyList()).isEmpty())
        assertEquals(listOf(0.5f to 0.5f), linePoints(listOf(42.0)))
        assertEquals(listOf(0f to 0.5f, 0.5f to 0.5f, 1f to 0.5f), linePoints(listOf(3.0, 3.0, 3.0)))
        // y = 0 en bas (minimum), 1 en haut (maximum).
        assertEquals(listOf(0f to 0f, 0.5f to 1f, 1f to 0.5f), linePoints(listOf(10.0, 30.0, 20.0)))
        assertTrue(linePoints(listOf(1.0, Double.NaN)).all { (x, y) -> x.isFinite() && y.isFinite() })
    }

    @Test
    fun donut_weights_normalized() {
        val w = donutWeights(listOf(1.0, 3.0))
        assertEquals(listOf(0.25f, 0.75f), w)
        assertEquals(1f, donutWeights(listOf(0.2, 0.3, 0.5)).sum(), 1e-6f)
        // Négatifs ignorés, tout à zéro : anneau vide plutôt qu'une division par zéro.
        assertEquals(listOf(0f, 1f), donutWeights(listOf(-5.0, 2.0)))
        assertEquals(listOf(0f, 0f), donutWeights(listOf(0.0, 0.0)))
        assertTrue(donutWeights(emptyList()).isEmpty())
    }

    private fun block(title: String, start: String, end: String, allDay: Boolean = false) =
        TimeBlock(title, minuteOf(start), minuteOf(end), allDay = allDay)

    private fun minuteOf(hhmm: String) = hhmm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }

    @Test
    fun blocks_positioned_by_minutes() {
        // Plage 7 h – 23 h : 960 minutes.
        val placed = timelineLayout(
            listOf(block("A", "09:00", "10:00"), block("Tôt", "06:00", "07:30"), block("Tard", "23:30", "23:59")),
            7, 23,
        ).placed
        val a = placed.single { it.index == 0 }
        assertEquals(120f / 960, a.top, 1e-6f)
        assertEquals(180f / 960, a.bottom, 1e-6f)
        // Débordement rogné à la plage ; bloc hors plage absent.
        val early = placed.single { it.index == 1 }
        assertEquals(0f, early.top, 1e-6f)
        assertEquals(30f / 960, early.bottom, 1e-6f)
        assertTrue(placed.none { it.index == 2 })
        assertEquals(0.5f, minuteFraction(15 * 60, 7, 23), 1e-6f)
        assertEquals("1 h 30", durationText(90))
        assertEquals("45 min", durationText(45))
        assertEquals("2 h", durationText(120))
    }

    @Test
    fun overlapping_events_side_by_side() {
        val placed = timelineLayout(
            listOf(block("A", "09:00", "10:00"), block("B", "09:30", "11:00"), block("C", "11:00", "12:00")),
            7, 23,
        ).placed.associateBy { it.index }
        assertEquals(0 to 2, placed.getValue(0).column to placed.getValue(0).columns)
        assertEquals(1 to 2, placed.getValue(1).column to placed.getValue(1).columns)
        // Bloc qui touche sans chevaucher : pleine largeur.
        assertEquals(0 to 1, placed.getValue(2).column to placed.getValue(2).columns)
    }

    @Test
    fun now_line_only_today() {
        val day = java.time.LocalDate.of(2026, 11, 8)
        assertEquals(0.5f, nowFraction(day, day.atTime(15, 0), 7, 23)!!, 1e-6f)
        assertEquals(null, nowFraction(day.plusDays(1), day.atTime(15, 0), 7, 23))
        assertEquals(null, nowFraction(day, day.atTime(6, 0), 7, 23))
        // Passé minuit, le jour affiché n'est plus aujourd'hui.
        assertEquals(null, nowFraction(day, day.plusDays(1).atTime(0, 5), 0, 24))
        assertEquals(30_000L, millisToNextMinute(day.atTime(23, 59, 30)))
        assertEquals(60_000L, millisToNextMinute(day.atTime(10, 0)))
    }

    @Test
    fun all_day_events_on_top() {
        val layout = timelineLayout(
            listOf(block("Réunion", "10:00", "11:00"), block("Congé", "00:00", "00:00", allDay = true)),
            7, 23,
        )
        assertEquals(listOf("Congé"), layout.allDay.map { it.title })
        assertEquals(listOf(0), layout.placed.map { it.index })
        assertEquals("Congé, toute la journée", describeBlock(layout.allDay.single()))
        assertEquals("Réunion, de 10 h à 11 h", describeBlock(block("Réunion", "10:00", "11:00")))
    }

    @Test
    fun short_block_keeps_min_height_for_overlap() {
        // 9 h – 9 h 05 occupe visuellement 20 min : 9 h 10 doit se ranger à côté.
        val layout = timelineLayout(listOf(block("A", "09:00", "09:05"), block("B", "09:10", "10:00")), 7, 23)
        val p = layout.placed.associateBy { it.index }
        assertEquals(0 to 2, p.getValue(0).column to p.getValue(0).columns)
        assertEquals(1 to 2, p.getValue(1).column to p.getValue(1).columns)
        // Le bas reste la vraie fin.
        assertEquals(125f / 960, p.getValue(0).bottom, 1e-6f)
        // Seuil désactivable.
        assertEquals(1, timelineLayout(listOf(block("A", "09:00", "09:05"), block("B", "09:10", "10:00")), 7, 23, minMinutes = 0).placed[1].columns)
    }

    @Test
    fun overlap_chain_reuses_first_column() {
        val p = timelineLayout(
            listOf(block("A", "09:00", "10:00"), block("B", "09:30", "10:30"), block("C", "10:00", "11:00")),
            7, 23,
        ).placed.associateBy { it.index }
        assertEquals(listOf(0, 1, 0), (0..2).map { p.getValue(it).column })
        assertEquals(listOf(2, 2, 2), (0..2).map { p.getValue(it).columns })
    }

    @Test
    fun edge_crossing_block_overlaps() {
        val p = timelineLayout(listOf(block("Tôt", "06:00", "07:30"), block("B", "07:00", "08:00")), 7, 23)
            .placed.associateBy { it.index }
        assertEquals(2, p.getValue(0).columns)
        assertEquals(0f, p.getValue(0).top, 1e-6f)
        assertEquals(1, p.getValue(1).column)
    }

    @Test
    fun block_description_mentions_tone() {
        val b = TimeBlock("Point", 600, 660, tone = Tone.Urgent)
        assertTrue(describeBlock(b).contains("important"))
        assertTrue(describeBlock(b.copy(tone = Tone.Attention)).contains("à surveiller"))
        assertEquals("Point, de 10 h à 11 h", describeBlock(b.copy(tone = Tone.Neutral)))
    }

    @Test
    fun block_description_names_source() {
        val b = TimeBlock("Point", 600, 660, source = "travail", subtitle = "Salle B")
        assertEquals("Point, travail, de 10 h à 11 h, Salle B", describeBlock(b))
    }

    @Test
    fun column_heights_relative_to_max() {
        assertEquals(listOf(0.5f, 1f, 0f, 0f), columnHeights(listOf(50.0, 100.0, -3.0, Double.NaN)))
        assertEquals(listOf(0f, 0f), columnHeights(listOf(0.0, 0.0)))
        assertEquals(emptyList<Float>(), columnHeights(emptyList()))
        assertEquals("Volume, aucune donnée", describeColumns("Volume", listOf(0.0), { it.toString() }))
    }

    @Test
    fun bar_note_reads_without_colour() {
        val f: (Double) -> String = { formatEuro(it, cents = false) }
        assertEquals(null, barNote(50.0, 100.0, f, masked = false))
        assertEquals(null, barNote(80.0, 100.0, f, masked = false))
        assertEquals("82$nbsp%", barNote(82.0, 100.0, f, masked = false))
        assertEquals("100$nbsp%", barNote(100.0, 100.0, f, masked = false))
        assertEquals("dépassé de 11$nbsp€", barNote(111.0, 100.0, f, masked = false))
        assertEquals("dépassé de 5$nbsp€", barNote(5.0, 0.0, f, masked = false))
        // Masqué : le mot, aucun chiffre.
        assertEquals("proche de la limite", barNote(82.0, 100.0, f, masked = true))
        assertEquals("dépassé", barNote(111.0, 100.0, f, masked = true))
    }

    @Test
    fun useful_hours_frame_the_day() {
        fun b(s: Int, e: Int, allDay: Boolean = false) = TimeBlock("x", s, e, allDay = allDay)
        // Une heure de part et d'autre, à l'heure ronde.
        assertEquals(9 to 13, usefulHours(listOf(b(10 * 60 + 30, 11 * 60), b(11 * 60, 12 * 60 - 15))))
        // Bornée à 7 h – 23 h ; un bloc jusqu'à minuit s'arrête à 23 h.
        assertEquals(7 to 23, usefulHours(listOf(b(7 * 60, 24 * 60))))
        // Journée entière ignorée ; rien de placé : plage par défaut.
        assertEquals(8 to 20, usefulHours(listOf(b(0, 24 * 60, allDay = true))))
        // L'heure actuelle est incluse quand elle est donnée.
        assertEquals(9 to 21, usefulHours(listOf(b(10 * 60, 11 * 60)), now = 19 * 60 + 10))
        // Bloc tout en fin de plage : au moins une heure affichée.
        assertEquals(22 to 23, usefulHours(listOf(b(23 * 60 + 10, 23 * 60 + 40))))
    }
}
