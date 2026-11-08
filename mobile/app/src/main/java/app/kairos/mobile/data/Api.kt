package app.kairos.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.io.IOException
import java.time.LocalDate

sealed class ApiError(message: String) : Exception(message) {
    /** Jeton refusé : l'appareil doit être appairé à nouveau. */
    data object Unauthorized : ApiError("Appareil non autorisé")
    data object Offline : ApiError("Serveur injoignable")
    class Server(val code: Int, message: String) : ApiError(message)
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
        } catch (_: IOException) {
            throw ApiError.Offline
        }
        when {
            code == 401 -> throw ApiError.Unauthorized
            code !in 200..299 -> throw ApiError.Server(code, message(text) ?: "Erreur du serveur ($code)")
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
