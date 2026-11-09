package app.kairos.mobile.ui.today

import java.time.Duration
import java.time.LocalTime

/** Éléments repliés sous « N plus tôt » : tout ce qui précède l'élément en cours (aucun s'il n'y en a pas). */
fun earlierCount(items: List<TimelineItem>): Int = currentIndex(items).coerceAtLeast(0)

private fun parseTime(time: String?): LocalTime? = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

/** Libellé de la carte en cours : « maintenant » une fois l'heure atteinte, sinon « dans 25 min ». */
fun startsIn(time: String?, now: LocalTime): String? {
    val at = parseTime(time) ?: return null
    val minutes = Duration.between(now.withSecond(0).withNano(0), at).toMinutes()
    return when {
        minutes <= 0 -> "maintenant"
        minutes < 60 -> "dans $minutes min"
        minutes % 60 == 0L -> "dans ${minutes / 60} h"
        else -> "dans ${minutes / 60} h %02d".format(minutes % 60)
    }
}

/** « 15 h 30, Rendez-vous » pour le lecteur d'écran ; « sans heure » plutôt qu'un tiret. */
fun spokenItem(time: String?, title: String): String {
    val at = parseTime(time)
    val spoken = when {
        time.isNullOrBlank() -> "sans heure"
        at == null -> time
        at.minute == 0 -> "${at.hour} h"
        else -> "${at.hour} h ${at.minute}"
    }
    return "$spoken, $title"
}

/** Aperçu de la pile dit en une phrase : nombre, durée estimée, action. */
fun decisionsSpoken(count: Int): String {
    val minutes = maxOf(1, (count * 10 + 59) / 60)
    val what = if (count > 1) "décisions en attente" else "décision en attente"
    return "$count $what, environ $minutes minute${if (minutes > 1) "s" else ""}. Ouvrir."
}

/**
 * Texte du bandeau de mise à jour. Le bandeau est une région vivante : son texte ne change
 * qu'au début, à mi-parcours et à la fin (la barre de progression porte le détail).
 */
fun updateBannerText(version: String, progress: Float?): String = when {
    progress == null -> "Kairos $version disponible"
    progress >= 1f -> "Installation…"
    progress >= 0.5f -> "Téléchargement à moitié"
    else -> "Téléchargement commencé"
}
