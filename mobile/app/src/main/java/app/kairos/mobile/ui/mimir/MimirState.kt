package app.kairos.mobile.ui.mimir

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Suffixe ajouté à l'agent utilisateur de la WebView : le site masque alors son en-tête. */
const val MIMIR_USER_AGENT_SUFFIX = " KairosApp"

/**
 * Lien de partage Mimir collé par l'utilisateur, nettoyé : espaces et retours à la ligne
 * retirés (copier-coller), `https://` obligatoire, adresse bien formée. Le message d'échec
 * est affiché tel quel ; il ne reprend jamais le lien (qui porte un jeton).
 */
fun normalizeMimirLink(raw: String): Result<String> = runCatching {
    val text = raw.filterNot { it.isWhitespace() }
    require(text.isNotEmpty()) { "Colle ton lien de partage Mimir." }
    val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
    require(scheme == "https") {
        if (scheme == "http") "Lien non chiffré refusé : il doit commencer par https://" else "Le lien doit commencer par https://"
    }
    // Même analyseur que le client HTTP de l'application.
    val url = text.toHttpUrlOrNull()
    require(url != null && url.isHttps && url.host.isNotEmpty()) { "Ce lien n'est pas une adresse valide." }
    url.toString()
}

/** Où ouvrir une navigation demandée par la page. */
enum class MimirRoute { InApp, External }

/**
 * Reste dans l'espace : les pages `https` du même hôte que le lien configuré ([base]), et les
 * adresses internes au moteur (`about:`, `data:`, `blob:`, `javascript:`). Tout le reste
 * (autre domaine, `http`, `mailto:`, `tel:`, schémas d'applications) part vers le système.
 */
fun mimirRoute(base: String, target: String): MimirRoute {
    val scheme = target.substringBefore(':', missingDelimiterValue = "").lowercase()
    return when (scheme) {
        "about", "data", "blob", "javascript" -> MimirRoute.InApp
        "https" -> {
            val host = target.toHttpUrlOrNull()?.host
            val home = base.toHttpUrlOrNull()?.host
            if (host != null && host == home) MimirRoute.InApp else MimirRoute.External
        }
        else -> MimirRoute.External
    }
}
