package app.kairos.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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
import app.kairos.mobile.ui.agenda.agendaAfter
import app.kairos.mobile.ui.finances.financesAfter
import app.kairos.mobile.ui.marches.marchesAfter
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
    private val agendaFile = File(dir, "agenda.json")
    private val marchesFile = File(dir, "marches.json")
    private val loadLock = Mutex()
    private val maisonLock = Mutex()
    private val financesLock = Mutex()
    private val agendaLock = Mutex()
    private val marchesLock = Mutex()
    private var marchesTried: Long? = null
    private var agendaTried: Long? = null
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

    private val _agenda = MutableStateFlow(AgendaSnapshot())
    val agenda: StateFlow<AgendaSnapshot> = _agenda.asStateFlow()

    private val _marches = MutableStateFlow(MarchesSnapshot())
    val marches: StateFlow<MarchesSnapshot> = _marches.asStateFlow()

    /** Relit l'état enregistré (une seule fois) ; appelé d'office par [refresh] et [mark]. */
    suspend fun load() = loadLock.withLock {
        if (loaded) return@withLock
        read(snapshotFile, Snapshot.serializer())?.let { saved -> _snapshot.update { if (it.fetchedAt == null) saved else it } }
        read(maisonFile, MaisonSnapshot.serializer())?.let { saved -> _maison.update { if (it.fetchedAt == null) saved else it } }
        read(financesFile, FinancesSnapshot.serializer())?.let { saved -> _finances.update { if (it.fetchedAt == null) saved else it } }
        read(agendaFile, AgendaSnapshot.serializer())?.let { saved -> _agenda.update { if (it.fetchedAt == null) saved else it } }
        read(marchesFile, MarchesSnapshot.serializer())?.let { saved -> _marches.update { if (it.fetchedAt == null) saved else it } }
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
     * Sans [force], ignorée si la précédente date de moins de [FRESH_MS].
     */
    suspend fun refreshFinances(force: Boolean = true) = financesLock.withLock {
        val last = financesTried
        if (!force && last != null && System.nanoTime() - last < FRESH_MS * 1_000_000) return@withLock
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

    /**
     * Jour [day] et semaine à partir d'aujourd'hui ; avec [sync], resynchronise d'abord les
     * calendriers (le module n'en fait pas plus d'une par minute), puis lit la base locale
     * même si la synchronisation a échoué. Ce qui n'a pas répondu est gardé hors ligne.
     * Sans [force], ignorée si la dernière synchronisation date de moins de [FRESH_MS] :
     * une lecture sans synchronisation ne retarde pas celle de l'ouverture de l'espace.
     */
    suspend fun refreshAgenda(day: LocalDate, sync: Boolean = false, force: Boolean = true) = agendaLock.withLock {
        val last = agendaTried
        if (!force && last != null && System.nanoTime() - last < FRESH_MS * 1_000_000) return@withLock
        load()
        val synced = if (sync) attempt { api.tool(AGENDA, "app_agenda_sync", AgendaSync.serializer()) } else null
        val (d, w) = coroutineScope {
            val d = async { attempt { api.tool(AGENDA, "app_agenda_day", AgendaDay.serializer(), buildJsonObject { put("day", day.toString()) }) } }
            val w = async { attempt { api.tool(AGENDA, "app_agenda_week", AgendaWeek.serializer(), buildJsonObject { put("from", today().toString()) }) } }
            d.await() to w.await()
        }
        if (sync) agendaTried = System.nanoTime()
        val next = agendaAfter(_agenda.value, d, w, synced, LocalDateTime.now().withNano(0).toString(), today())
        _agenda.value = next
        if (d.isSuccess || w.isSuccess) write(agendaFile, AgendaSnapshot.serializer(), next)
    }

    /**
     * Questions suivies, scénarios, alertes et propositions, en parallèle ; ce qui n'a pas
     * répondu est gardé hors ligne. Sans [force], ignorée si la précédente date de moins de [FRESH_MS].
     */
    suspend fun refreshMarches(force: Boolean = true) = marchesLock.withLock {
        val last = marchesTried
        if (!force && last != null && System.nanoTime() - last < FRESH_MS * 1_000_000) return@withLock
        load()
        val (m, p) = coroutineScope {
            val m = async { attempt { api.tool(MARCHES, "app_markets", Markets.serializer()) } }
            val p = async { attempt { api.tool(MARCHES, "app_market_proposals", PROPOSALS) } }
            m.await() to p.await()
        }
        marchesTried = System.nanoTime()
        val next = marchesAfter(_marches.value, m, p, LocalDateTime.now().withNano(0).toString())
        _marches.value = next
        if (m.isSuccess || p.isSuccess) write(marchesFile, MarchesSnapshot.serializer(), next)
    }

    /**
     * Détail d'une question, gardé pour une relecture hors ligne. Rend vrai s'il est à jour ;
     * faux si le module n'a pas répondu (le dernier détail connu reste affiché).
     */
    suspend fun refreshMarket(id: String): Boolean {
        load()
        val detail = attempt { api.tool(MARCHES, "app_market", MarketDetail.serializer(), buildJsonObject { put("id", id) }) }
            .getOrElse { return false }
        val next = marchesLock.withLock {
            _marches.updateAndGet { it.copy(details = it.details + (id to detail)) }
        }
        write(marchesFile, MarchesSnapshot.serializer(), next)
        return true
    }

    /**
     * Retire une proposition triée sans attendre le module ; [ignore] la garde écartée
     * sur l'appareil. Un refus de suivi revient avec la prochaine actualisation.
     */
    suspend fun dropProposal(id: String, ignore: Boolean) {
        val next = _marches.updateAndGet { s ->
            s.copy(proposals = s.proposals.filterNot { it.id == id }, ignored = if (ignore) s.ignored + id else s.ignored)
        }
        write(marchesFile, MarchesSnapshot.serializer(), next)
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
        _agenda.value = AgendaSnapshot()
        _marches.value = MarchesSnapshot()
        marchesTried = null
        financesTried = null
        agendaTried = null
        withContext(Dispatchers.IO) {
            maisonFile.delete()
            financesFile.delete()
            agendaFile.delete()
            marchesFile.delete()
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
        const val AGENDA = "agenda"
        const val MARCHES = app.kairos.mobile.ui.marches.MARCHES
        const val FRESH_MS = 30_000L
        val ORDERS = ListSerializer(Order.serializer())
        val PROPOSALS = ListSerializer(MarketProposal.serializer())
        val MARKS = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Mark.serializer()))
    }
}
