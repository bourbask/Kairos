package app.kairos.mobile.ui.mimir

import app.kairos.mobile.data.ApiError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

/** Module de la tête qui émet les liens de partage. */
const val PARTAGE = "partage"

/** Libellé du lien que l'application demande pour l'accès du propriétaire. */
const val OWNER_LABEL = "moi"

/** Longueur maximale d'un libellé, alignée sur le module. */
const val SHARE_LABEL_MAX = 40

/** Lien émis : à transmettre aussitôt, jamais gardé ni journalisé. */
@Serializable
data class ShareLink(val lien: String, @SerialName("expire_le") val expiresAt: String)

/**
 * Partage connu du module ; [expiresAt] : fin de la fenêtre où le lien peut ouvrir un appareil,
 * [accessUntil] : fin de la dernière session ouverte, [devices] : appareils dont l'accès court.
 */
@Serializable
data class Share(
    val id: Long,
    val libelle: String,
    @SerialName("cree_le") val createdAt: String,
    @SerialName("expire_le") val expiresAt: String,
    @SerialName("ouvert_le") val openedAt: String? = null,
    val actif: Boolean = false,
    @SerialName("acces_jusqu_au") val accessUntil: String? = null,
    @SerialName("appareils") val devices: Int = 0,
    @SerialName("max_appareils") val maxDevices: Int = 1,
)

/** Demande d'un lien ; [owner] : accès du propriétaire, qui remplace ses liens jamais ouverts. */
fun shareRequest(label: String, owner: Boolean = false): JsonObject = buildJsonObject {
    put("libelle", label)
    if (owner) put("proprietaire", true)
}

/** Libellé proposé : « Partage du JJ/MM ». */
fun defaultShareLabel(day: LocalDate): String = "Partage du %02d/%02d".format(day.dayOfMonth, day.monthValue)

/** Libellé saisi : espaces resserrés, non vide, [SHARE_LABEL_MAX] caractères au plus. */
fun cleanShareLabel(raw: String): Result<String> = runCatching {
    val text = raw.trim().split(Regex("\\s+")).joinToString(" ")
    require(text.isNotEmpty()) { "Donne un nom à ce partage." }
    require(text.codePointCount(0, text.length) <= SHARE_LABEL_MAX) { "$SHARE_LABEL_MAX caractères au plus." }
    text
}

private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")

/** Date du module (RFC 3339) en heure locale « JJ/MM HH:mm » ; illisible : telle quelle. */
fun shareTime(at: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { OffsetDateTime.parse(at).atZoneSameInstant(zone).format(DAY_TIME) }.getOrDefault(at)

/** État d'un partage en une ligne. */
fun shareStatus(s: Share, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    val created = "Créé le ${shareTime(s.createdAt, zone)}"
    return when {
        !s.actif -> if (s.openedAt != null) "Inactif · ouvert le ${shareTime(s.openedAt, zone)}" else "Inactif · jamais ouvert"
        s.openedAt == null -> "$created · à ouvrir avant le ${shareTime(s.expiresAt, zone)}"
        // Module antérieur aux sessions multiples : l'échéance y était la fin d'accès.
        s.accessUntil == null -> "Ouvert le ${shareTime(s.openedAt, zone)} · accès jusqu'au ${shareTime(s.expiresAt, zone)}"
        else -> {
            val count = "${s.devices} appareil${if (s.devices > 1) "s" else ""} sur ${s.maxDevices}"
            val windowOpen = runCatching { OffsetDateTime.parse(s.expiresAt).toInstant().isAfter(now) }.getOrDefault(false)
            val more = if (windowOpen && s.devices < s.maxDevices) " · autre appareil avant le ${shareTime(s.expiresAt, zone)}" else ""
            "Ouvert sur $count · accès jusqu'au ${shareTime(s.accessUntil, zone)}$more"
        }
    }
}

/** Partages actifs d'abord, puis les plus récents. */
fun sortedShares(list: List<Share>): List<Share> =
    list.sortedWith(compareByDescending<Share> { it.actif }.thenByDescending { it.createdAt })

/** Raison lisible d'un échec du module de partage ; [ApiError.Unauthorized] remonte. */
fun shareIssue(e: ApiError): String = when (e) {
    ApiError.Unauthorized -> throw e
    ApiError.Offline -> "Serveur injoignable."
    ApiError.NoReply -> "Pas de réponse du serveur."
    is ApiError.Server -> when {
        e.code == 404 && !e.bare -> "Partage non configuré sur le serveur."
        e.code == 422 -> e.message ?: "Refusé."
        else -> "${e.unavailable()}."
    }
}
