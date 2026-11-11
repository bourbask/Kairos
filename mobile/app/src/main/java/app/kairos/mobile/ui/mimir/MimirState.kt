package app.kairos.mobile.ui.mimir

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Lien de partage Mimir collé par l'utilisateur, nettoyé : espaces et retours à la ligne
 * retirés (copier-coller), `https://` obligatoire, adresse bien formée. Le jeton se place
 * après `#` (jamais envoyé au serveur) : un lien qui porte des paramètres après `?` est
 * refusé. Le message d'échec est affiché tel quel ; il ne reprend jamais le lien.
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
    require(url.encodedQuery == null) { "Lien d'ancienne forme : demande un nouveau lien de partage." }
    url.toString()
}

/** Origine du lien (`https://hôte[:port]/`) : sans chemin, paramètres, fragment ni identifiants. */
fun mimirOrigin(link: String): String? = link.toHttpUrlOrNull()?.origin()?.toString()

private fun HttpUrl.origin(): HttpUrl =
    newBuilder().username("").password("").encodedPath("/").query(null).fragment(null).build()

/** Même origine : schéma, hôte et port identiques (port implicite compris). */
fun sameOrigin(a: String, b: String): Boolean {
    val x = a.toHttpUrlOrNull() ?: return false
    val y = b.toHttpUrlOrNull() ?: return false
    return x.scheme == y.scheme && x.host == y.host && x.port == y.port
}

/**
 * Ce qu'il faut garder sur l'appareil : le lien complet tant que la session n'est pas ouverte
 * (pour réessayer), puis seulement l'origine, le cookie de session portant l'accès.
 */
fun mimirStoredLink(link: String, sessionOpen: Boolean): String =
    if (sessionOpen) mimirOrigin(link) ?: link else link

/**
 * Adresse chargée dans la vue : `embed=1` ajouté au fragment, pour que le site se présente en
 * mode intégré. Le fragment n'est jamais envoyé au serveur ; aucun marqueur ne quitte l'appareil.
 */
fun mimirEmbedUrl(link: String): String {
    val base = link.substringBefore('#')
    val frag = link.substringAfter('#', missingDelimiterValue = "")
    if (frag.split('&').contains("embed=1")) return link
    return if (frag.isEmpty()) "$base#embed=1" else "$base#$frag&embed=1"
}

/** Où ouvrir une navigation demandée par la page. */
enum class MimirRoute { InApp, External, Blocked }

/**
 * Reste dans l'espace : les pages de la même origine que le lien configuré ([base]) et
 * `about:blank`. Part vers le système : les autres pages web, `mailto:` et `tel:`. Tout le
 * reste (`javascript:`, `data:`, `blob:`, `file:`, `intent:`, schémas d'applications) est bloqué.
 */
fun mimirRoute(base: String, target: String): MimirRoute {
    if (target.equals("about:blank", ignoreCase = true)) return MimirRoute.InApp
    return when (target.substringBefore(':', missingDelimiterValue = "").lowercase()) {
        "https", "http" -> if (sameOrigin(base, target)) MimirRoute.InApp else MimirRoute.External
        "mailto", "tel" -> MimirRoute.External
        else -> MimirRoute.Blocked
    }
}
