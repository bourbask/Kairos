package app.kairos.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.kairos.mobile.ui.finances.financesAfter
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Dernier état connu du fil et des décisions, gardé en mémoire et dans le
 * stockage privé : sans serveur, l'application reste lisible.
 * Les marques « fait » / « plus tard » sont locales à l'appareil, par jour,
 * indexées par [Card.key].
 * Accès disque hors du fil principal ; une erreur disque n'interrompt jamais l'affichage.
 */
class Repository(
    private val api: KairosApi,
    private val dir: File,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val snapshotFile = File(dir, "snapshot.json")
    private val marksFile = File(dir, "marks.json")
    private val maisonFile = File(dir, "maison.json")
    private val financesFile = File(dir, "finances.json")
    private val loadLock = Mutex()
    private val maisonLock = Mutex()
    private val financesLock = Mutex()
    private var financesTried: Long? = null
    private var loaded = false

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _marks = MutableStateFlow<Map<String, Map<String, Mark>>>(emptyMap())
    val marks: StateFlow<Map<String, Map<String, Mark>>> = _marks.asStateFlow()

    private val _maison = MutableStateFlow(MaisonSnapshot())
    val maison: StateFlow<MaisonSnapshot> = _maison.asStateFlow()

    private val _finances = MutableStateFlow(FinancesSnapshot())
    val finances: StateFlow<FinancesSnapshot> = _finances.asStateFlow()

    /** Relit l'état enregistré (une seule fois) ; appelé d'office par [refresh] et [mark]. */
    suspend fun load() = loadLock.withLock {
        if (loaded) return@withLock
        read(snapshotFile, Snapshot.serializer())?.let { saved -> _snapshot.update { if (it.fetchedAt == null) saved else it } }
        read(maisonFile, MaisonSnapshot.serializer())?.let { saved -> _maison.update { if (it.fetchedAt == null) saved else it } }
        read(financesFile, FinancesSnapshot.serializer())?.let { saved -> _finances.update { if (it.fetchedAt == null) saved else it } }
        val savedMarks = read(marksFile, MARKS).orEmpty()
        _marks.update { current -> (savedMarks + current).filterKeys { it >= today().toString() } }
        loaded = true
    }

    /**
     * Affiche d'abord l'état enregistré, puis recharge la semaine qui commence
     * aujourd'hui et les décisions. Serveur injoignable ou en erreur : l'état
     * précédent reste, marqué hors ligne. [ApiError.Unauthorized] remonte à
     * l'appelant (retour à l'appairage).
     */
    suspend fun refresh() {
        load()
        try {
            // Clés uniques garanties : les listes de l'interface en dépendent.
            val days = api.days(today(), WEEK).distinctBy { it.day }
                .map { d -> d.copy(cards = d.cards.distinctBy { it.key }) }
            val decisions = api.decisions()
            val fresh = Snapshot(days, decisions, LocalDateTime.now().withNano(0).toString(), offline = false)
            _snapshot.value = fresh
            write(snapshotFile, Snapshot.serializer(), fresh)
        } catch (e: ApiError) {
            if (e is ApiError.Unauthorized) throw e
            _snapshot.update { it.copy(offline = true) }
        }
    }

    /**
     * Inventaire et commandes à ranger ; même règle hors ligne que [refresh].
     * Une actualisation à la fois : une réponse ancienne n'écrase jamais une plus récente.
     */
    suspend fun refreshMaison() = maisonLock.withLock {
        load()
        try {
            val inventory = api.tool(ALIMENTATION, "app_inventory", Inventory.serializer())
            val orders = api.tool(ALIMENTATION, "app_orders", ORDERS)
            val fresh = MaisonSnapshot(inventory, orders, LocalDateTime.now().withNano(0).toString(), offline = false)
            _maison.value = fresh
            write(maisonFile, MaisonSnapshot.serializer(), fresh)
        } catch (e: ApiError) {
            if (e is ApiError.Unauthorized) throw e
            val reason = (e as? ApiError.Server)?.let { if (it.outdated) ApiError.Server.OUTDATED else "Module indisponible" }
            _maison.update { it.copy(offline = true, reason = reason) }
        }
    }

    /**
     * Cycle et patrimoine, en parallèle ; ce qui a répondu remplace l'ancien, le reste
     * est gardé hors ligne. Une actualisation à la fois, comme [refreshMaison].
     * Sans [force], ignorée si la précédente date de moins de [FINANCES_FRESH_MS].
     */
    suspend fun refreshFinances(force: Boolean = true) = financesLock.withLock {
        val last = financesTried
        if (!force && last != null && System.nanoTime() - last < FINANCES_FRESH_MS * 1_000_000) return@withLock
        load()
        val (cycle, wealth) = coroutineScope {
            val c = async { attempt { api.tool(FINANCES, "app_finance_cycle", FinanceCycle.serializer()) } }
            val w = async { attempt { api.tool(FINANCES, "app_finance_wealth", FinanceWealth.serializer()) } }
            c.await() to w.await()
        }
        financesTried = System.nanoTime()
        val next = financesAfter(_finances.value, cycle, wealth, LocalDateTime.now().withNano(0).toString())
        _finances.value = next
        if (cycle.isSuccess || wealth.isSuccess) write(financesFile, FinancesSnapshot.serializer(), next)
    }

    /** Verdict « puis-je me permettre » pour [amount] euros, sur [poste] s'il est donné. */
    suspend fun afford(amount: Double, poste: String?): Affordability =
        api.tool(FINANCES, "app_afford", Affordability.serializer(), buildJsonObject {
            put("amount", amount)
            poste?.let { put("poste", it) }
        })

    /** Échec réseau ou module rendu en valeur ; [ApiError.Unauthorized] remonte. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: ApiError) {
        if (e is ApiError.Unauthorized) throw e
        Result.failure(e)
    }

    /** Écrit dans l'inventaire ; rend le message du module. */
    suspend fun maisonTool(name: String, args: JsonObject): String = tool(ALIMENTATION, name, args)

    /** Outil d'écriture `app_*` d'un module ; rend son message. */
    suspend fun tool(module: String, name: String, args: JsonObject): String =
        api.tool(module, name, ToolMessage.serializer(), args).message

    /** Pose ou retire ([mark] = null) la marque de l'élément [key] du jour [day]. */
    suspend fun mark(day: String, key: String, mark: Mark?) {
        load()
        _marks.update { all ->
            val forDay = all[day].orEmpty().let { if (mark == null) it - key else it + (key to mark) }
            (if (forDay.isEmpty()) all - day else all + (day to forDay))
                .filterKeys { it >= today().toString() }
        }
        write(marksFile, MARKS, _marks.value)
    }

    /**
     * Retire une décision déjà envoyée, sans attendre le serveur : rouvrir la pile
     * ne la repropose pas. Un refus revient avec la prochaine actualisation.
     */
    fun drop(module: String, id: String) {
        _snapshot.update { s -> s.copy(decisions = s.decisions.filterNot { it.module == module && it.id == id }) }
    }

    /** Oublie tout ce qui a été mis en cache (changement d'appareil ou de serveur). */
    suspend fun clear() {
        _snapshot.value = Snapshot()
        _marks.value = emptyMap()
        _maison.value = MaisonSnapshot()
        _finances.value = FinancesSnapshot()
        financesTried = null
        withContext(Dispatchers.IO) {
            maisonFile.delete()
            financesFile.delete()
            snapshotFile.delete()
            marksFile.delete()
        }
    }

    private suspend fun <T> read(file: File, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        runCatching { KairosJson.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    private suspend fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeText(KairosJson.encodeToString(serializer, value))
                check(tmp.renameTo(file))
            }
        }
    }

    private companion object {
        const val WEEK = 7
        const val ALIMENTATION = "alimentation"
        const val FINANCES = "finances"
        const val FINANCES_FRESH_MS = 30_000L
        val ORDERS = ListSerializer(Order.serializer())
        val MARKS = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Mark.serializer()))
    }
}
