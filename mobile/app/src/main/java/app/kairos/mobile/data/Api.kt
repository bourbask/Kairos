package app.kairos.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate

sealed class ApiError(message: String) : Exception(message) {
    /** Jeton refusé : l'appareil doit être appairé à nouveau. */
    data object Unauthorized : ApiError("Appareil non autorisé")
    data object Offline : ApiError("Serveur injoignable")

    /** Requête partie sans réponse à temps : elle a pu être appliquée. */
    data object NoReply : ApiError("Pas de réponse du serveur")

    /** [bare] : réponse sans raison (`message`) lisible. */
    class Server(val code: Int, message: String, val bare: Boolean = false) : ApiError(message) {
        /** 404 nu : la tête ne connaît pas la route, elle précède cette version de l'application. */
        val outdated: Boolean get() = code == 404 && bare

        /** Module hors d'atteinte, ou tête à mettre à jour. */
        fun unavailable(): String = if (outdated) OUTDATED else "Module indisponible, réessaie plus tard"

        companion object {
            const val OUTDATED = "Serveur à mettre à jour"
        }
    }
}

/** Client de l'API de l'application (`/app/v1`). Le jeton n'est jamais journalisé. */
class KairosApi(baseUrl: String, private val token: String, private val client: OkHttpClient) {
    private val root: HttpUrl = baseUrl.trimEnd('/').removeSuffix("/app/v1").toHttpUrl()
        .newBuilder().addPathSegments("app/v1").build()

    suspend fun status(): Status = get(url("status")) { KairosJson.decodeFromString(Status.serializer(), it) }

    suspend fun postChat(text: String) {
        post(url("chat"), buildJsonObject { put("text", text) }) {}
    }

    suspend fun chat(after: Long?, limit: Int): List<ChatEntry> {
        val u = url("chat").newBuilder().apply {
            if (after != null) addQueryParameter("after", after.toString())
            addQueryParameter("limit", limit.toString())
        }.build()
        return get(u) { KairosJson.decodeFromString(ListSerializer(ChatEntry.serializer()), it) }
    }

    suspend fun days(from: LocalDate, days: Int): List<Day> {
        val u = url("days").newBuilder()
            .addQueryParameter("from", from.toString())
            .addQueryParameter("days", days.toString())
            .build()
        return get(u) { KairosJson.decodeFromString(ListSerializer(Day.serializer()), it) }
    }

    suspend fun decisions(): List<Decision> =
        get(url("decisions")) { KairosJson.decodeFromString(ListSerializer(Decision.serializer()), it) }

    /** Rend le message du module ; [ApiError.Server] (422) si le module refuse. */
    suspend fun decide(module: String, id: String, choice: String): String =
        post(url("decisions").newBuilder().addPathSegment(module).addPathSegment(id).build(),
            buildJsonObject { put("choice", choice) }) { message(it) ?: "" }

    /**
     * Outil `app_*` d'un module, relayé tel quel par la tête ; [result] lit sa réponse.
     * [ApiError.Server] : 422 = refus du module (avec sa raison), 404 / 503 = module absent ou déconnecté.
     */
    suspend fun <T> tool(module: String, name: String, result: DeserializationStrategy<T>, args: JsonObject = JsonObject(emptyMap())): T =
        post(url("tools").newBuilder().addPathSegment(module).addPathSegment(name).build(), args) {
            KairosJson.decodeFromString(result, it)
        }

    /** Dernier APK déposé sur la tête ; null s'il n'y en a pas (ou si la tête précède ce service). */
    suspend fun appRelease(): AppRelease? = try {
        get(url("app")) { KairosJson.decodeFromString(AppRelease.serializer(), it) }
    } catch (e: ApiError.Server) {
        if (e.code == 404) null else throw e
    }

    /** Télécharge l'APK dans [to] ; [onBytes] reçoit le nombre d'octets écrits. */
    suspend fun downloadApp(to: File, onBytes: (Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("app.apk")).header("Authorization", "Bearer $token").get().build()
        try {
            client.newCall(request).executeAsync().use { r ->
                if (r.code == 401) throw ApiError.Unauthorized
                if (!r.isSuccessful) throw ApiError.Server(r.code, "Erreur du serveur (${r.code})", bare = true)
                r.body.byteStream().use { input ->
                    to.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            onBytes(total)
                        }
                    }
                }
            }
        } catch (_: InterruptedIOException) {
            throw ApiError.NoReply
        } catch (_: IOException) {
            throw ApiError.Offline
        }
    }

    private fun url(path: String) = root.newBuilder().addPathSegments(path).build()

    private suspend fun <T> get(url: HttpUrl, parse: (String) -> T): T =
        call(Request.Builder().url(url).get(), parse)

    private suspend fun <T> post(url: HttpUrl, body: JsonObject, parse: (String) -> T): T =
        call(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)), parse)

    private suspend fun <T> call(builder: Request.Builder, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val request = builder.header("Authorization", "Bearer $token").build()
        val (code, text) = try {
            // Annulée avec la coroutine appelante (écran quitté, actualisation abandonnée).
            client.newCall(request).executeAsync().use { it.code to it.body.string() }
        } catch (_: InterruptedIOException) {
            // Délai dépassé : la requête a pu partir.
            throw ApiError.NoReply
        } catch (_: IOException) {
            throw ApiError.Offline
        }
        when {
            code == 401 -> throw ApiError.Unauthorized
            code !in 200..299 -> throw message(text).let { ApiError.Server(code, it ?: "Erreur du serveur ($code)", bare = it == null) }
            else -> try {
                parse(text)
            } catch (e: IllegalArgumentException) {
                throw ApiError.Server(code, "Réponse illisible")
            }
        }
    }

    private fun message(text: String): String? = runCatching {
        KairosJson.parseToJsonElement(text).let { (it as JsonObject)["message"]?.jsonPrimitive?.content }
    }.getOrNull()

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
