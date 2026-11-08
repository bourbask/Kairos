package app.kairos.mobile.update

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.AppRelease
import java.io.File
import java.io.IOException

/** Au plus une vérification automatique par intervalle (démarrage, retour au premier plan). */
const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

fun checkDue(lastCheck: Long?, now: Long): Boolean = lastCheck == null || now - lastCheck >= CHECK_EVERY_MS

/** `X.Y.Z` en nombres ; null si illisible. */
private fun parts(version: String): List<Int>? =
    version.split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.size == 3 }

/** Vrai seulement si [available] est strictement plus récente ; une version illisible ne l'est jamais. */
fun newer(available: String, installed: String): Boolean {
    val a = parts(available) ?: return false
    val b = parts(installed) ?: return false
    return a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x > y } ?: false
}

/** La release du serveur si elle mérite d'être proposée. */
fun offered(release: AppRelease?, installed: String): AppRelease? = release?.takeIf { newer(it.version, installed) }

sealed interface UpdateOutcome {
    /** Installée, ou remise à l'installateur du système. */
    data object Installed : UpdateOutcome
    data object Cancelled : UpdateOutcome

    /** L'installation d'applications par Kairos n'est pas autorisée sur ce téléphone. */
    data object NeedsPermission : UpdateOutcome
    data class Failed(val message: String) : UpdateOutcome
}

/** Installateur du système, derrière une interface pour les tests. */
interface Installer {
    fun allowed(): Boolean
    suspend fun install(apk: File): UpdateOutcome
}

/**
 * Télécharge [release] dans [apk], vérifie sa taille, puis l'installe. Le fichier est
 * effacé quoi qu'il arrive. [ApiError.Unauthorized] remonte à l'appelant.
 */
suspend fun runUpdate(
    release: AppRelease,
    apk: File,
    download: suspend (File, (Long) -> Unit) -> Unit,
    installer: Installer,
    onProgress: (Float) -> Unit,
): UpdateOutcome {
    if (!installer.allowed()) return UpdateOutcome.NeedsPermission
    try {
        apk.parentFile?.mkdirs()
        var shown = -1
        try {
            download(apk) { bytes ->
                // Une mise à jour par pour-cent : pas un recalcul d'écran par bloc lu.
                val pct = if (release.size > 0) (bytes * 100 / release.size).toInt().coerceIn(0, 100) else 0
                if (pct != shown) {
                    shown = pct
                    onProgress(pct / 100f)
                }
            }
        } catch (e: ApiError) {
            if (e == ApiError.Unauthorized) throw e
            return UpdateOutcome.Failed(downloadMessage(e))
        } catch (_: IOException) {
            return UpdateOutcome.Failed("Espace insuffisant ou écriture impossible.")
        }
        if (apk.length() != release.size) {
            return UpdateOutcome.Failed("Téléchargement incomplet (${apk.length()} octets sur ${release.size}). Réessaie.")
        }
        return installer.install(apk)
    } finally {
        apk.delete()
    }
}

private fun downloadMessage(e: ApiError): String = when (e) {
    ApiError.Offline, ApiError.NoReply -> "Téléchargement interrompu : serveur injoignable. Réessaie."
    is ApiError.Server -> if (e.code == 404) "La mise à jour n'est plus disponible sur le serveur." else "Le serveur a refusé le téléchargement (${e.code})."
    ApiError.Unauthorized -> "Appareil non autorisé."
}
