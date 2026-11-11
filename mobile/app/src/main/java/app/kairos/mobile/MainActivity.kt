package app.kairos.mobile

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import app.kairos.mobile.data.AppRelease
import app.kairos.mobile.update.SystemInstaller
import app.kairos.mobile.update.UpdateOutcome
import app.kairos.mobile.update.checkDue
import app.kairos.mobile.update.offered
import app.kairos.mobile.update.runUpdate
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.CacheCodec
import app.kairos.mobile.data.ChatEntry
import app.kairos.mobile.data.Credentials
import app.kairos.mobile.data.KairosApi
import app.kairos.mobile.data.Repository
import app.kairos.mobile.data.SecureStore
import app.kairos.mobile.ui.KairosButtonCorner
import app.kairos.mobile.ui.Shell
import app.kairos.mobile.ui.Tab
import app.kairos.mobile.ui.overlay.Origin
import app.kairos.mobile.ui.overlay.Overlay
import app.kairos.mobile.ui.overlay.overlayAnchor
import app.kairos.mobile.ui.overlay.rememberOverlayState
import app.kairos.mobile.ui.overlay.underOverlays
import app.kairos.mobile.ui.decide.DeckScreen
import app.kairos.mobile.ui.maison.MaisonActionCorner
import app.kairos.mobile.ui.maison.MaisonScreen
import app.kairos.mobile.ui.maison.AddScreen
import app.kairos.mobile.ui.maison.UnpackScreen
import app.kairos.mobile.ui.maison.UnpackState
import app.kairos.mobile.ui.overlay.anchorTo
import app.kairos.mobile.data.Order
import app.kairos.mobile.ui.spaces.CardCorner
import app.kairos.mobile.ui.spaces.SpacesScreen
import app.kairos.mobile.ui.agenda.AgendaScreen
import java.time.LocalDate
import app.kairos.mobile.ui.finances.FinancesScreen
import app.kairos.mobile.ui.marches.MarchesScreen
import app.kairos.mobile.ui.memoire.laterIds
import app.kairos.mobile.ui.memoire.MemoireScreen
import app.kairos.mobile.ui.medias.MediasScreen
import app.kairos.mobile.ui.documents.DocumentsScreen
import app.kairos.mobile.ui.mimir.MimirScreen
import app.kairos.mobile.ui.kit.LocalAmountsMasked
import app.kairos.mobile.ui.kit.LocalNotifier
import app.kairos.mobile.ui.kit.NotifierHost
import app.kairos.mobile.ui.kit.rememberNotifier
import app.kairos.mobile.ui.mascot.LocalMascot
import app.kairos.mobile.ui.mascot.MascotKind
import app.kairos.mobile.ui.mascot.mascotDescription
import app.kairos.mobile.ui.pairing.PairingScreen
import app.kairos.mobile.ui.talk.ChatScreen
import app.kairos.mobile.ui.talk.Conversation
import app.kairos.mobile.ui.talk.TalkController
import app.kairos.mobile.ui.talk.TalkEffect
import app.kairos.mobile.ui.talk.TalkMode
import app.kairos.mobile.ui.talk.TalkPanel
import app.kairos.mobile.ui.talk.mascotFor
import app.kairos.mobile.ui.theme.KairosColors
import app.kairos.mobile.ui.theme.KairosTheme
import app.kairos.mobile.ui.theme.ThemeMode
import app.kairos.mobile.ui.theme.ThemePalette
import app.kairos.mobile.ui.theme.rememberDark
import app.kairos.mobile.ui.settings.SettingsScreen
import app.kairos.mobile.ui.settings.maskAddress
import app.kairos.mobile.data.Settings
import androidx.compose.runtime.DisposableEffect
import app.kairos.mobile.ui.today.DecisionsPeekCorner
import app.kairos.mobile.ui.today.SettingsButtonCorner
import app.kairos.mobile.ui.today.TodayScreen
import app.kairos.mobile.voice.Speech
import app.kairos.mobile.voice.Tts
import app.kairos.mobile.work.ReplyWatcher
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = rememberDark(model.theme.takeIf { model.loaded })
            // Icônes des barres système : sombres sur thème clair, claires sur thème sombre.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
                onDispose {}
            }
            // Ni capture d'écran ni aperçu dans les applications récentes, sauf choix contraire.
            val secure = model.hideFromCaptures
            DisposableEffect(secure) {
                if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose {}
            }
            KairosTheme(dark = dark, palette = model.palette) {
                CompositionLocalProvider(
                    LocalMascot provides model.mascot,
                    LocalAmountsMasked provides model.amountsMasked,
                    LocalNotifier provides rememberNotifier(),
                ) {
                    KairosRoot(model, dark)
                }
            }
        }
    }
}

/** Un client HTTP pour toute l'application ; pas de journal des requêtes (le jeton y figurerait). */
internal val http = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/** Connexion appairée : client de l'API, dernier état connu et conversation. */
class Session(
    credentials: Credentials,
    stateDir: File,
    scope: CoroutineScope,
    onUnauthorized: () -> Unit,
    onReply: (ChatEntry, Boolean) -> Unit,
) {
    val address = credentials.baseUrl
    val api = KairosApi(credentials.baseUrl, credentials.token, http)
    val repository = Repository(api, stateDir, CacheCodec.Device)
    val conversation = Conversation(api, scope, onUnauthorized, onReply)
}

/** État de l'application ; survit aux rotations (pas de nouveau déchiffrement ni de nouvelle session). */
class AppModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val FINAL_TIMEOUT_MS = 4_000L
    }

    private val store = SecureStore(app)
    private val stateDir = File(app.filesDir, "etat")

    var loaded by mutableStateOf(false)
        private set
    var session by mutableStateOf<Session?>(null)
        private set
    var pairingMessage by mutableStateOf<String?>(null)
        private set

    val talk = TalkController(app.resources.displayMetrics.density)

    /** Niveau du micro (0..1), pour les ondes. */
    var level by mutableFloatStateOf(0f)
        private set
    private val speech = Speech(
        app,
        onPartial = talk::onPartial,
        onFinal = { dispatch(talk.onFinal(it)) },
        onError = { dispatch(talk.onError(it)) },
        onLevel = { level = it },
    )
    val micAvailable: Boolean get() = speech.available

    private val tts = Tts(app)
    private val settings = Settings(app)
    var speakReplies by mutableStateOf(false)
        private set
    var theme by mutableStateOf(ThemeMode.Clair)
        private set
    var palette by mutableStateOf(ThemePalette.Fougere)
        private set
    var mascot by mutableStateOf(MascotKind.Kairos)
        private set

    /** Montants masqués dans tous les espaces. */
    var amountsMasked by mutableStateOf(false)
        private set

    /** Fenêtre exclue des captures et des applications récentes (activé par défaut). */
    var hideFromCaptures by mutableStateOf(true)
        private set

    /** Lien de partage de l'espace Mimir, collé sur l'appareil ; null tant qu'il ne l'est pas. */
    var mimirLink by mutableStateOf<String?>(null)
        private set

    private val processStarted: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** Version installée, comparée à celle que la tête propose. */
    val version: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    /** Version plus récente disponible sur la tête, sinon null. */
    var update by mutableStateOf<AppRelease?>(null)
        private set

    /** Avancement 0..1 pendant le téléchargement puis l'installation, sinon null. */
    var updateProgress by mutableStateOf<Float?>(null)
        private set

    /** L'installation d'applications doit être autorisée dans les réglages du téléphone. */
    var askInstallPermission by mutableStateOf(false)

    var updateNotice by mutableStateOf<String?>(null)
        private set

    /** Installation en attente de confirmation : remontrer l'écran du système ou abandonner. */
    var installPending by mutableStateOf(false)

    private var checkedAt: Long? = null
    // Hors du premier plan, l'écran attend le retour (onStart) : `installer.awaiting` le garde.
    private val installer = SystemInstaller(app) { if (processStarted) showConfirm(it) }
    private val apkFile = File(app.cacheDir, "maj/kairos.apk")

    private fun showConfirm(intent: Intent) {
        getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Interroge la tête ; [force] ignore l'intervalle entre deux vérifications automatiques,
     * [report] dit le résultat (bouton « Vérifier »).
     */
    fun checkUpdate(force: Boolean = false, report: Boolean = false) {
        val api = session?.api ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && !checkDue(checkedAt, now)) return
        viewModelScope.launch {
            try {
                update = offered(api.appRelease(), version)
                checkedAt = now
                if (report && update == null) updateNotice = "Kairos est à jour ($version)."
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError.Unauthorized) {
                unauthorized()
            } catch (e: ApiError) {
                if (report) updateNotice = "Vérification impossible : ${e.message}."
            }
        }
    }

    fun startUpdate() {
        val release = update ?: return
        val api = session?.api ?: return
        if (updateProgress != null) {
            // Bandeau à 100 % touché : la confirmation du système a été quittée sans réponse.
            if (installer.awaiting != null) installPending = true
            return
        }
        updateProgress = 0f
        viewModelScope.launch {
            val out = try {
                runUpdate(release, apkFile, api::downloadApp, installer) { updateProgress = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError.Unauthorized) {
                unauthorized()
                null
            } finally {
                updateProgress = null
            }
            when (out) {
                UpdateOutcome.NeedsPermission -> askInstallPermission = true
                UpdateOutcome.Cancelled -> updateNotice = "Mise à jour annulée."
                is UpdateOutcome.Failed -> updateNotice = out.message
                UpdateOutcome.Installed, null -> Unit
            }
        }
    }

    fun resumeInstall() {
        installPending = false
        installer.awaiting?.let(::showConfirm)
    }

    fun abandonInstall() {
        installPending = false
        installer.abandon()
    }

    /** Retour de l'écran « Installer des applis inconnues » : on reprend si c'est autorisé. */
    fun installPermissionReturned() {
        if (installer.allowed()) startUpdate()
        else updateNotice = "Installation non autorisée : la mise à jour attendra."
    }

    fun consumeUpdateNotice() {
        updateNotice = null
    }

    // Premier plan de l'application (pas d'une activité : une rotation ne compte pas).
    private val lifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            ReplyWatcher.stop(getApplication())
            session?.conversation?.foreground(true)
            checkUpdate()
            // Confirmation quittée (Accueil, récents) ou arrivée hors du premier plan : remontrée au retour.
            // Délai : un « Annuler » sur l'écran du système ramène ici avant que son refus n'arrive.
            if (installer.awaiting != null) {
                viewModelScope.launch {
                    delay(1_000)
                    if (processStarted) installer.awaiting?.let(::showConfirm)
                }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            // Micro coupé hors du premier plan ; un texte mains libres reste à envoyer au retour.
            if (talk.mode == TalkMode.Locked) {
                speech.cancel()
                dispatch(talk.onError(retry = false))
            } else {
                dispatch(talk.cancel())
            }
            handOver()
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycle)
        // Reliquat d'une mise à jour interrompue (application tuée pendant le téléchargement).
        apkFile.delete()
        viewModelScope.launch {
            val saved = runCatching { settings.load() }.getOrNull()
            speakReplies = saved?.speakReplies ?: false
            theme = saved?.theme ?: ThemeMode.Clair
            palette = saved?.palette ?: ThemePalette.Fougere
            mascot = saved?.mascot ?: MascotKind.Kairos
            amountsMasked = saved?.amountsMasked ?: false
            hideFromCaptures = saved?.hideFromCaptures ?: true
            mimirLink = runCatching { store.loadMimir() }.getOrNull()
            session = runCatching { store.load() }.getOrNull()?.let(::open)
            loaded = true
            checkUpdate()
        }
    }

    private fun open(credentials: Credentials) =
        Session(credentials, stateDir, viewModelScope, ::unauthorized) { entry, handsFree ->
            if (speakReplies || handsFree) tts.speak(entry.text)
        }.also { it.conversation.foreground(processStarted) }

    /** Applique les effets du bouton Kairos. */
    fun dispatch(effects: List<TalkEffect>) {
        for (e in effects) when (e) {
            TalkEffect.StartListening -> {
                finalTimeout?.cancel()
                tts.stop()
                speech.start()
            }
            TalkEffect.StopListening -> stopListening()
            TalkEffect.CancelListening -> speech.cancel()
            is TalkEffect.Send -> session?.conversation?.send(e.text, e.handsFree)
            TalkEffect.Threshold -> Unit
        }
    }

    private var finalTimeout: Job? = null

    /** Arrête la dictée en cours ; le texte reconnu reste modifiable. */
    fun stopListening() {
        speech.stop()
        // Certains moteurs ne rendent jamais le texte final : on n'attend pas indéfiniment.
        finalTimeout?.cancel()
        finalTimeout = viewModelScope.launch {
            delay(FINAL_TIMEOUT_MS)
            if (talk.listening) {
                speech.cancel()
                dispatch(talk.onError(retry = false))
            }
        }
    }

    fun changeSpeakReplies(on: Boolean) {
        speakReplies = on
        if (!on) tts.stop()
        viewModelScope.launch { settings.saveSpeakReplies(on) }
    }

    fun changeTheme(mode: ThemeMode) {
        theme = mode
        viewModelScope.launch { settings.saveTheme(mode) }
    }

    fun changePalette(p: ThemePalette) {
        palette = p
        viewModelScope.launch { settings.savePalette(p) }
    }

    fun changeMascot(m: MascotKind) {
        mascot = m
        viewModelScope.launch { settings.saveMascot(m) }
    }

    fun changeHideFromCaptures(on: Boolean) {
        hideFromCaptures = on
        viewModelScope.launch { settings.saveHideFromCaptures(on) }
    }

    /** [link] déjà validé ([app.kairos.mobile.ui.mimir.normalizeMimirLink]) ; chiffré avant écriture. */
    fun saveMimirLink(link: String) {
        mimirLink = link
        viewModelScope.launch { runCatching { store.saveMimir(link) } }
    }

    fun clearMimirLink() {
        mimirLink = null
        viewModelScope.launch { runCatching { store.clearMimir() } }
    }

    fun toggleAmountsMasked() {
        amountsMasked = !amountsMasked
        val on = amountsMasked
        viewModelScope.launch { settings.saveAmountsMasked(on) }
    }

    /** Efface le jeton et revient à l'appairage. */
    fun logout() {
        val old = session ?: return
        session = null
        old.conversation.close()
        dispatch(talk.cancel())
        tts.stop()
        pairingMessage = null
        viewModelScope.launch {
            runCatching { store.clear() }
            old.repository.clear()
        }
    }

    /** Hors du premier plan : la surveillance de la réponse passe à une tâche bornée. */
    private fun handOver() {
        val conversation = session?.conversation ?: return
        conversation.foreground(false)
        // Sans notifications, la surveillance ne servirait à rien : la réponse attendra dans le fil.
        val app = getApplication<Application>()
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        conversation.pendingReply()?.let { ReplyWatcher.start(app, it) }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycle)
        handOver()
        session?.conversation?.close()
        speech.destroy()
        tts.shutdown()
    }

    /** Vérifie le code auprès du serveur puis l'enregistre ; rend un message d'erreur, ou null. */
    suspend fun pair(candidate: Credentials): String? = try {
        KairosApi(candidate.baseUrl, candidate.token, http).status()
        store.save(candidate)
        pairingMessage = null
        session = open(candidate)
        checkUpdate(force = true)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiError) {
        when (e) {
            ApiError.Unauthorized -> "Code refusé par le serveur. Génère-en un nouveau."
            ApiError.Offline -> "Serveur injoignable. Le téléphone est-il sur le bon réseau ?"
            ApiError.NoReply -> "Le serveur ne répond pas, réessaie."
            is ApiError.Server -> "Le serveur a répondu : ${e.message}"
        }
    } catch (_: Exception) {
        "Appairage impossible."
    }

    /** Jeton refusé : on oublie l'appareil et on revient à l'appairage. */
    fun unauthorized() {
        val old = session ?: return
        session = null
        old.conversation.close()
        dispatch(talk.cancel())
        pairingMessage = "Cet appareil n'est plus autorisé. Appaire-le à nouveau."
        viewModelScope.launch {
            runCatching { store.clear() }
            old.repository.clear()
        }
    }
}

@Composable
private fun KairosRoot(model: AppModel, dark: Boolean) {
    if (!model.loaded) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val session = model.session
    if (session == null) {
        PairingScreen(model.pairingMessage, model::pair)
        return
    }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    val snackbar = notifier.host
    val conversation = session.conversation
    val talk = model.talk
    var decisionsOpen by rememberSaveable { mutableStateOf(false) }
    var chatOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var maisonOpen by rememberSaveable { mutableStateOf(false) }
    var financesOpen by rememberSaveable { mutableStateOf(false) }
    var financesAfford by rememberSaveable { mutableStateOf(false) }
    var agendaOpen by rememberSaveable { mutableStateOf(false) }
    var agendaDay by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(Tab.Aujourdhui) }
    var marchesOpen by rememberSaveable { mutableStateOf(false) }
    var memoireOpen by rememberSaveable { mutableStateOf(false) }
    var mediasOpen by rememberSaveable { mutableStateOf(false) }
    var documentsOpen by rememberSaveable { mutableStateOf(false) }
    var mimirOpen by rememberSaveable { mutableStateOf(false) }
    var unpackOrder by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var focusInput by remember { mutableStateOf(false) }
    var askMic by remember { mutableStateOf(false) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }

    fun run(effects: List<TalkEffect>) {
        model.dispatch(effects)
        when {
            effects.any { it is TalkEffect.Threshold } -> haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            effects.any { it is TalkEffect.Send } -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    fun openKeyboard(message: String) {
        chatOpen = true
        focusInput = true
        scope.launch { snackbar.showSnackbar(message) }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) run(talk.startLocked()) else openKeyboard("Sans micro, écris-moi plutôt.")
    }

    /** Micro utilisable maintenant ; sinon explique, demande, ou bascule sur le clavier. */
    fun canTalk(): Boolean = when {
        !model.micAvailable -> {
            openKeyboard("Pas de reconnaissance vocale sur ce téléphone : écris-moi.")
            false
        }
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> true
        else -> {
            askMic = true
            false
        }
    }

    // Prévenir de la réponse suppose les notifications : demandées au premier message.
    LaunchedEffect(conversation.pending.size) {
        if (conversation.pending.isNotEmpty() && !askedNotifications &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(model.updateNotice) {
        val notice = model.updateNotice ?: return@LaunchedEffect
        model.consumeUpdateNotice()
        snackbar.showSnackbar(notice)
    }
    val unknownSources = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.installPermissionReturned()
    }
    LaunchedEffect(conversation.notice) {
        val notice = conversation.notice ?: return@LaunchedEffect
        conversation.consumeNotice()
        snackbar.showSnackbar(notice)
    }

    val mascot = mascotFor(talk.mode, conversation.busy || conversation.awaiting, conversation.decisions, conversation.unread)
    val deck = rememberOverlayState(decisionsOpen, Origin.Anchor)
    val chat = rememberOverlayState(chatOpen, Origin.Anchor)
    val settings = rememberOverlayState(settingsOpen, Origin.Anchor)
    val unpack = rememberOverlayState(unpackOrder != null, Origin.Anchor)
    val add = rememberOverlayState(adding, Origin.Anchor)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val primary = MaterialTheme.colorScheme.primary
    // Fond révélé quand l'écran principal recule sous un panneau.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant)) {
        Shell(
            modifier = Modifier.underOverlays(rtl, deck, chat, settings, unpack, add),
            kairosModifier = Modifier.overlayAnchor(chat, KairosButtonCorner),
            mascotState = mascot,
            kairosState = mascotDescription(mascot, conversation.decisions),
            tab = tab,
            onTab = { tab = it },
            onTap = {
                focusInput = false
                chatOpen = true
            },
            onPressStart = { if (canTalk()) run(talk.pressStart()) },
            onPressEnd = { run(talk.pressEnd()) },
            onDrag = { run(talk.drag(it.x, it.y)) },
            onSpeak = { if (canTalk()) run(talk.startLocked()) },
            today = { padding ->
                TodayScreen(
                    repository = session.repository,
                    padding = padding,
                    onOpenDecisions = { decisionsOpen = true },
                    onOpenSettings = { settingsOpen = true },
                    // Un jour de la semaine ouvre l'Agenda sur ce jour, dans son espace.
                    onOpenAgenda = { day ->
                        agendaDay = day.toString()
                        tab = Tab.Espaces
                        agendaOpen = true
                    },
                    onUnauthorized = model::unauthorized,
                    appScope = model.viewModelScope,
                    update = model.update,
                    updateProgress = model.updateProgress,
                    onUpdate = model::startUpdate,
                    decisionsAnchor = Modifier.overlayAnchor(deck, DecisionsPeekCorner),
                    settingsAnchor = Modifier.overlayAnchor(settings, SettingsButtonCorner),
                )
            },
            spaces = { padding ->
                val maison by session.repository.maison.collectAsState()
                val finances by session.repository.finances.collectAsState()
                val agenda by session.repository.agenda.collectAsState()
                val marches by session.repository.marches.collectAsState()
                val memoire by session.repository.memoire.collectAsState()
                val medias by session.repository.medias.collectAsState()
                val documents by session.repository.documents.collectAsState()
                val marks by session.repository.marks.collectAsState()
                // Tous les espaces en parallèle ; sans [force], ceux lus il y a moins de 30 s sont gardés.
                fun refreshSpaces(force: Boolean) {
                    val refreshes = listOf<suspend () -> Unit>(
                        { session.repository.refreshMaison() },
                        { session.repository.refreshFinances(force) },
                        { session.repository.refreshAgenda(LocalDate.now(), force = force) },
                        { session.repository.refreshMarches(force) },
                        { session.repository.refreshMemoire(force) },
                        { session.repository.refreshMedias(force) },
                        { session.repository.refreshDocuments(force) },
                    )
                    for (refresh in refreshes) {
                        scope.launch {
                            try {
                                refresh()
                            } catch (_: ApiError.Unauthorized) {
                                model.unauthorized()
                            }
                        }
                    }
                }
                // Une actualisation à l'arrivée sur l'onglet : les états des rangées sont à jour.
                LaunchedEffect(Unit) { refreshSpaces(force = false) }
                SpacesScreen(
                    memoireLater = laterIds(marks[LocalDate.now().toString()].orEmpty().keys),
                    padding = padding,
                    onRetry = { refreshSpaces(force = true) },
                    onOpenSettings = { settingsOpen = true },
                    settingsAnchor = Modifier.overlayAnchor(settings, CardCorner),
                    maison = maison,
                    maisonOpen = maisonOpen,
                    onOpenMaison = { maisonOpen = true },
                    onCloseMaison = { maisonOpen = false },
                    finances = finances,
                    financesOpen = financesOpen,
                    onOpenFinances = {
                        financesAfford = false
                        financesOpen = true
                    },
                    // Raccourci de la rangée : Finances s'ouvre sur « Puis-je me permettre ? ».
                    onAfford = {
                        financesAfford = true
                        financesOpen = true
                    },
                    onCloseFinances = { financesOpen = false },
                    financesContent = {
                        FinancesScreen(
                            repository = session.repository,
                            padding = padding,
                            masked = model.amountsMasked,
                            onToggleMasked = model::toggleAmountsMasked,
                            onClose = { financesOpen = false },
                            onUnauthorized = model::unauthorized,
                            openAfford = financesAfford,
                        )
                    },
                    agenda = agenda,
                    agendaOpen = agendaOpen,
                    onOpenAgenda = {
                        agendaDay = null
                        agendaOpen = true
                    },
                    onCloseAgenda = { agendaOpen = false },
                    agendaContent = {
                        AgendaScreen(
                            repository = session.repository,
                            padding = padding,
                            appScope = model.viewModelScope,
                            initialDay = agendaDay?.let(LocalDate::parse),
                            onClose = { agendaOpen = false },
                            onUnauthorized = model::unauthorized,
                        )
                    },
                    marches = marches,
                    marchesOpen = marchesOpen,
                    onOpenMarches = { marchesOpen = true },
                    onCloseMarches = { marchesOpen = false },
                    marchesContent = {
                        MarchesScreen(
                            repository = session.repository,
                            padding = padding,
                            appScope = model.viewModelScope,
                            onClose = { marchesOpen = false },
                            onUnauthorized = model::unauthorized,
                        )
                    },
                    memoire = memoire,
                    memoireOpen = memoireOpen,
                    onOpenMemoire = { memoireOpen = true },
                    onCloseMemoire = { memoireOpen = false },
                    memoireContent = {
                        MemoireScreen(
                            repository = session.repository,
                            padding = padding,
                            appScope = model.viewModelScope,
                            onToggleMasked = model::toggleAmountsMasked,
                            onClose = { memoireOpen = false },
                            onUnauthorized = model::unauthorized,
                        )
                    },
                    medias = medias,
                    mediasOpen = mediasOpen,
                    onOpenMedias = { mediasOpen = true },
                    onCloseMedias = { mediasOpen = false },
                    mediasContent = {
                        MediasScreen(
                            repository = session.repository,
                            padding = padding,
                            appScope = model.viewModelScope,
                            onClose = { mediasOpen = false },
                            onUnauthorized = model::unauthorized,
                        )
                    },
                    documents = documents,
                    documentsOpen = documentsOpen,
                    onOpenDocuments = { documentsOpen = true },
                    onCloseDocuments = { documentsOpen = false },
                    documentsContent = {
                        DocumentsScreen(
                            repository = session.repository,
                            padding = padding,
                            onClose = { documentsOpen = false },
                            onUnauthorized = model::unauthorized,
                        )
                    },
                    // Site externe : rien à demander à la tête, seulement le lien enregistré sur l'appareil.
                    mimirConfigured = model.mimirLink != null,
                    mimirOpen = mimirOpen,
                    onOpenMimir = { mimirOpen = true },
                    onCloseMimir = { mimirOpen = false },
                    mimirContent = {
                        MimirScreen(
                            link = model.mimirLink,
                            padding = padding,
                            onSave = model::saveMimirLink,
                            onClear = model::clearMimirLink,
                            onClose = { mimirOpen = false },
                        )
                    },
                ) {
                    MaisonScreen(
                        repository = session.repository,
                        padding = padding,
                        appScope = model.viewModelScope,
                        onClose = { maisonOpen = false },
                        onUnpack = { order, at ->
                            at?.let { unpack.anchorTo(it, MaisonActionCorner) }
                            unpackOrder = order.id
                        },
                        onAdd = { adding = true },
                        onUnauthorized = model::unauthorized,
                        addAnchor = Modifier.overlayAnchor(add, MaisonActionCorner),
                    )
                }
            },
        )
        Overlay(deck, decisionsOpen, onClose = { decisionsOpen = false }, anchorColor = primary) {
            val snapshot by session.repository.snapshot.collectAsState()
            DeckScreen(
                decisions = snapshot.decisions,
                offline = snapshot.offline,
                appScope = model.viewModelScope,
                decide = { session.api.decide(it.card.module, it.card.id, it.option) },
                onDispatch = { session.repository.drop(it.card.module, it.card.id) },
                onDecided = conversation::refreshStatus,
                onFinished = {
                    model.viewModelScope.launch {
                        try {
                            session.repository.refresh()
                        } catch (_: ApiError.Unauthorized) {
                            model.unauthorized()
                        }
                    }
                },
                onUnauthorized = model::unauthorized,
                onNotice = { scope.launch { notifier.show(it) } },
                onClose = { decisionsOpen = false },
            )
        }
        Overlay(unpack, unpackOrder != null, onClose = { unpackOrder = null }, anchorColor = primary) {
            val maison by session.repository.maison.collectAsState()
            // Gardée telle qu'ouverte : rangée entièrement, elle quitte la liste mais reste affichée jusqu'à la fermeture.
            var order by remember { mutableStateOf<Order?>(null) }
            LaunchedEffect(unpackOrder, maison.orders) {
                val id = unpackOrder ?: return@LaunchedEffect
                if (order?.id == id) return@LaunchedEffect
                val found = maison.orders.firstOrNull { it.id == id }
                // Commande disparue entre-temps (rangée ailleurs) : rien à montrer.
                if (found == null) unpackOrder = null else order = found
            }
            order?.let { shown ->
                UnpackScreen(
                    order = shown,
                    offline = maison.offline,
                    repository = session.repository,
                    appScope = model.viewModelScope,
                    onUnauthorized = model::unauthorized,
                    onNotice = { scope.launch { notifier.show(it) } },
                    onClose = { unpackOrder = null },
                    others = maison.orders.count { o -> o.id != shown.id && o.lines.any { !it.done } },
                    onOther = { UnpackState.nextOrder(maison.orders, shown.id)?.let { unpackOrder = it.id } },
                )
            }
        }
        Overlay(add, adding, onClose = { adding = false }, anchorColor = primary) {
            val maison by session.repository.maison.collectAsState()
            AddScreen(
                api = session.api,
                repository = session.repository,
                offline = maison.offline,
                appScope = model.viewModelScope,
                onUnauthorized = model::unauthorized,
                onClose = { adding = false },
            )
        }
        // La conversation naît du bouton Kairos : même couleur que lui.
        Overlay(chat, chatOpen, onClose = { chatOpen = false }, anchorColor = KairosColors.current.btn) {
            ChatScreen(
                conversation = conversation,
                mascot = mascot,
                focusInput = focusInput,
                onMic = { if (canTalk()) run(talk.startLocked()) },
                onSend = { conversation.send(it, handsFree = false) },
                onClose = { chatOpen = false },
            )
        }
        Overlay(settings, settingsOpen, onClose = { settingsOpen = false }, anchorColor = MaterialTheme.colorScheme.surface) {
            SettingsScreen(
                theme = model.theme,
                dark = dark,
                onTheme = model::changeTheme,
                palette = model.palette,
                onPalette = model::changePalette,
                mascot = model.mascot,
                onMascot = model::changeMascot,
                speakReplies = model.speakReplies,
                onSpeakReplies = model::changeSpeakReplies,
                hideFromCaptures = model.hideFromCaptures,
                onHideFromCaptures = model::changeHideFromCaptures,
                amountsMasked = model.amountsMasked,
                onToggleMasked = model::toggleAmountsMasked,
                address = maskAddress(session.address),
                version = model.version,
                update = model.update,
                updateProgress = model.updateProgress,
                onUpdate = model::startUpdate,
                onCheck = { model.checkUpdate(force = true, report = it) },
                onLogout = {
                    settingsOpen = false
                    model.logout()
                },
                onClose = { settingsOpen = false },
            )
        }
        TalkPanel(
            talk = talk,
            level = model.level,
            aboveDock = !chatOpen,
            onSend = { run(talk.send()) },
            onCancel = { run(talk.cancel()) },
            onMic = { if (talk.listening) model.stopListening() else run(talk.resume()) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        NotifierHost(
            notifier,
            Modifier
                .align(if (chatOpen) Alignment.TopCenter else Alignment.BottomCenter)
                .then(if (chatOpen) Modifier.statusBarsPadding().padding(top = 64.dp) else Modifier.navigationBarsPadding().padding(bottom = 112.dp)),
        )
    }

    if (model.askInstallPermission) {
        AlertDialog(
            onDismissRequest = { model.askInstallPermission = false },
            title = { Text("Autoriser la mise à jour") },
            text = {
                Text(
                    "Pour installer sa nouvelle version, Kairos doit être autorisé à installer des applications. " +
                        "Active « Autoriser cette source » sur l'écran qui s'ouvre, puis reviens : la mise à jour reprendra.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    model.askInstallPermission = false
                    unknownSources.launch(
                        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("Ouvrir les réglages") }
            },
            dismissButton = { TextButton(onClick = { model.askInstallPermission = false }) { Text("Plus tard") } },
        )
    }

    if (model.installPending) {
        AlertDialog(
            onDismissRequest = { model.installPending = false },
            title = { Text("Installation en attente") },
            text = { Text("Le téléphone attend ta confirmation pour installer la mise à jour.") },
            confirmButton = { TextButton(onClick = model::resumeInstall) { Text("Confirmer") } },
            dismissButton = { TextButton(onClick = model::abandonInstall) { Text("Abandonner") } },
        )
    }

    if (askMic) {
        AlertDialog(
            onDismissRequest = { askMic = false },
            title = { Text("Parler à Kairos") },
            text = {
                Text(
                    "Pour t'entendre, Kairos a besoin du micro. Ta voix est transcrite par la " +
                        "reconnaissance vocale du téléphone ; seul le texte est envoyé.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askMic = false
                    mic.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Autoriser le micro") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askMic = false
                    openKeyboard("D'accord, on s'écrit.")
                }) { Text("Écrire plutôt") }
            },
        )
    }
}
