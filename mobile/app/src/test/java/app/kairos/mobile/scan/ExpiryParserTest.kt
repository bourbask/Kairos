package app.kairos.mobile.scan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ExpiryParserTest {
    private val today = LocalDate.of(2026, 11, 8)
    private val nov12 = LocalDate.of(2026, 11, 12)

    private fun parse(text: String) = ExpiryParser.parse(text, today)

    @Test
    fun full_dates_any_separator() {
        assertEquals(listOf(nov12), parse("À consommer jusqu'au 12/11/2026"))
        assertEquals(listOf(nov12), parse("DLC 12.11.26 L4521"))
        assertEquals(listOf(nov12), parse("12-11-2026"))
    }

    @Test
    fun day_month_without_year_is_next_occurrence() {
        assertEquals(listOf(nov12), parse("12/11"))
        // Déjà passé cette année : l'an prochain.
        assertEquals(listOf(LocalDate.of(2027, 1, 3)), parse("03/01"))
        assertEquals(listOf(today), parse("08/11"))
    }

    @Test
    fun month_year_is_end_of_month() {
        assertEquals(listOf(LocalDate.of(2027, 11, 30)), parse("À consommer de préférence avant fin : 11/2027"))
    }

    @Test
    fun month_names() {
        assertEquals(listOf(nov12), parse("12 NOV 2026"))
        assertEquals(listOf(nov12), parse("12 NOV. 26"))
        assertEquals(listOf(LocalDate.of(2027, 2, 3)), parse("03 fév 2027"))
    }

    @Test
    fun no_date() {
        assertEquals(emptyList<LocalDate>(), parse("Lait demi-écrémé 1 L 4,2 % 12:30"))
        assertEquals(emptyList<LocalDate>(), parse("31/02/2027"))
    }

    @Test
    fun several_dates_sorted_nearest_first() {
        assertEquals(
            listOf(nov12, LocalDate.of(2026, 12, 1)),
            parse("Emballé le 01/11/2026 — à consommer jusqu'au 01/12/2026 ; 12/11/2026 ; 12/11/26"),
        )
    }

    @Test
    fun ocr_confusions_in_digits() {
        assertEquals(listOf(LocalDate.of(2026, 11, 10)), parse("DLC 1O/11/2O26"))
        assertEquals(listOf(nov12), parse("l2.I1.26"))
        // Les mots restent des mots.
        assertEquals(listOf(nov12), parse("LOT IOL 12/11/2026"))
    }

    @Test
    fun dot_matrix_spaces() {
        assertEquals(listOf(nov12), parse("12 11 26"))
        assertEquals(listOf(nov12), parse("12 11 2026"))
    }

    @Test
    fun english_months() {
        assertEquals(listOf(LocalDate.of(2027, 5, 12)), parse("12 MAY 2027"))
        assertEquals(listOf(LocalDate.of(2027, 2, 3)), parse("03 FEB 27"))
        assertEquals(listOf(LocalDate.of(2027, 4, 3)), parse("03 APR 27"))
        assertEquals(listOf(LocalDate.of(2027, 8, 3)), parse("03 AUG 27"))
    }
}
