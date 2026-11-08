package app.kairos.mobile.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder

data class Credentials(val baseUrl: String, val token: String) {
    override fun toString() = "Credentials(baseUrl=$baseUrl, token=***)"
}

/** Lecture de l'adresse d'appairage `kairos://pair?url=<adresse encodée>&token=<jeton>`. */
object Pairing {
    fun parse(uri: String): Result<Credentials> = runCatching {
        val u = URI(uri.trim())
        require(u.scheme == "kairos" && u.host == "pair") { "Ce n'est pas un code d'appairage Kairos." }
        val params = (u.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }
        val url = params["url"].orEmpty().trim()
        // Même analyseur que le client HTTP : ce qui passe ici ne peut pas le faire échouer.
        require(url.toHttpUrlOrNull() != null) { "Adresse du serveur invalide." }
        val token = params["token"].orEmpty().trim()
        require(token.isNotEmpty()) { "Jeton absent." }
        Credentials(url, token)
    }
}
