package app.kairos.mobile.scan

import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth

/**
 * Dates lues sur une étiquette (texte reconnu, donc bruité). Interprétation
 * prévisible, toujours confirmée ensuite par l'utilisateur :
 * - « 12/11/2026 », « 12.11.26 », « 12-11-2026 » : jour, mois, année ;
 * - « 12 NOV 2026 », « 12 nov. 26 » : mois en lettres (français) ;
 * - « 11/2027 » : mois et année seuls = DDM, fin du mois ;
 * - « 12/11 » : jour et mois seuls = prochaine occurrence à partir d'aujourd'hui.
 * - « 12 11 26 » : impression à points, séparée par des espaces (jour et mois sur deux chiffres).
 * Confusions de lecture corrigées dans les suites de chiffres : O → 0, I / l → 1.
 * Les dates passées (emballage, fabrication) et les dates à plus de dix ans
 * (numéros de lot) sont écartées. Résultat sans doublon, la plus proche d'abord.
 *
 * Cas ambigus, assumés (l'utilisateur confirme toujours) :
 * - une DDM mois/année sur deux chiffres (« 11/27 ») n'est pas reconnue : lue
 *   jour/mois, elle est invalide et ignorée ;
 * - un nombre comme « 2.10 kg » peut être proposé comme 2 octobre.
 */
object ExpiryParser {
    private const val SEP = """\s?[./-]\s?"""
    private const val MAX_YEARS = 10L

    private val dayMonthYear = Regex("""(?<!\d)(\d{1,2})$SEP(\d{1,2})$SEP(\d{4}|\d{2})(?!\d)""")
    private val spaced = Regex("""(?<!\d)(\d{2}) (\d{2}) (\d{4}|\d{2})(?!\d)""")
    private val monthYear = Regex("""(?<!\d)(\d{1,2})$SEP(\d{4})(?!\d)""")
    private val named = Regex("""(?<!\d)(\d{1,2})\s?([A-Z]{3,9})\.?\s?(\d{4}|\d{2})?(?![\d])""")
    // Mois sur deux chiffres : « 3.5 » (taux, poids) n'est pas une date.
    private val dayMonth = Regex("""(?<!\d)(\d{1,2})$SEP(\d{2})(?![\d.:/-])""")

    /** Mois en lettres, sans accents ; mot exact, pour ne pas lire « 12 MARQUE ». */
    private val months = listOf(
        listOf("JAN", "JANV", "JANVIER"), listOf("FEV", "FEVR", "FEVRIER", "FEB"), listOf("MAR", "MARS"), listOf("AVR", "AVRIL", "APR"),
        listOf("MAI", "MAY"), listOf("JUN", "JUIN"), listOf("JUL", "JUIL", "JUILLET"), listOf("AOU", "AOUT", "AUG"),
        listOf("SEP", "SEPT", "SEPTEMBRE"), listOf("OCT", "OCTOBRE"), listOf("NOV", "NOVEMBRE"), listOf("DEC", "DECEMBRE"),
    ).flatMapIndexed { i, words -> words.map { it to i + 1 } }.toMap()

    /** Suite de chiffres et séparateurs, avec les lettres que la reconnaissance confond avec des chiffres. */
    private val digitish = Regex("""(?<![\p{L}\d])[\dOIl][\dOIl./-]*(?![\p{L}\d])""")

    fun parse(text: String, today: LocalDate): List<LocalDate> {
        // Dans une suite qui contient un vrai chiffre seulement : « LOT » et « IOL » restent des mots.
        val fixed = digitish.replace(text) { m ->
            if (m.value.any(Char::isDigit)) m.value.replace('O', '0').replace('I', '1').replace('l', '1') else m.value
        }
        // Majuscules sans accents : « fév. » et « FEV » se lisent pareil.
        var rest = Normalizer.normalize(fixed.uppercase(), Normalizer.Form.NFD).replace(Regex("""\p{M}"""), "")
        val found = mutableListOf<LocalDate>()

        // Du plus précis au moins précis ; chaque motif lu (date valable ou non) est retiré du texte.
        fun scan(regex: Regex, read: (MatchResult) -> LocalDate?) {
            rest = regex.replace(rest) { m ->
                read(m)?.let { found += it }
                " "
            }
        }
        scan(dayMonthYear) { m -> date(m.int(3).let(::century), m.int(2), m.int(1)) }
        scan(spaced) { m -> date(m.int(3).let(::century), m.int(2), m.int(1)) }
        scan(monthYear) { m -> runCatching { YearMonth.of(m.int(2), m.int(1)).atEndOfMonth() }.getOrNull() }
        scan(named) { m ->
            val month = months[m.groupValues[2]] ?: return@scan null
            val year = m.groupValues[3].toIntOrNull()?.let(::century)
            if (year != null) date(year, month, m.int(1)) else next(month, m.int(1), today)
        }
        scan(dayMonth) { m -> next(m.int(2), m.int(1), today) }

        return found.distinct()
            .filter { !it.isBefore(today) && it.isBefore(today.plusYears(MAX_YEARS)) }
            .sorted()
    }

    private fun MatchResult.int(group: Int) = groupValues[group].toInt()

    private fun century(year: Int) = if (year < 100) 2000 + year else year

    private fun date(year: Int, month: Int, day: Int): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun next(month: Int, day: Int, today: LocalDate): LocalDate? {
        val d = date(today.year, month, day) ?: date(today.year + 1, month, day) ?: return null
        return if (d.isBefore(today)) date(today.year + 1, month, day) else d
    }
}
