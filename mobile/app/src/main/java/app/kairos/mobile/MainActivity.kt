package app.kairos.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import app.kairos.mobile.data.ChatEntry
import app.kairos.mobile.data.Credentials
import app.kairos.mobile.data.KairosApi
import app.kairos.mobile.data.Repository
import app.kairos.mobile.data.SecureStore
import app.kairos.mobile.ui.KairosButtonCorner
import app.kairos.mobile.ui.Shell
import app.kairos.mobile.ui.overlay.Origin
import app.kairos.mobile.ui.overlay.Overlay
import app.kairos.mobile.ui.overlay.overlayAnchor
import app.kairos.mobile.ui.overlay.rememberOverlayState
import app.kairos.mobile.ui.overlay.underOverlays
import app.kairos.mobile.ui.decide.DeckScreen
import app.kairos.mobile.ui.pairing.PairingScreen
import app.kairos.mobile.ui.talk.ChatScreen
import app.kairos.mobile.ui.talk.Conversation
import app.kairos.mobile.ui.talk.TalkController
import app.kairos.mobile.ui.talk.TalkEffect
import app.kairos.mobile.ui.talk.TalkMode
import app.kairos.mobile.ui.talk.TalkPanel
import app.kairos.mobile.ui.talk.mascotFor
import app.kairos.mobile.ui.theme.KairosTheme
import app.kairos.mobile.ui.theme.ThemeMode
import app.kairos.mobile.ui.theme.rememberDark
import app.kairos.mobile.ui.settings.SettingsScreen
import app.kairos.mobile.ui.settings.maskAddress
import app.kairos.mobile.data.Settings
import androidx.compose.runtime.DisposableEffect
import app.kairos.mobile.ui.today.DecisionsPeekCorner
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
            val dark = rememberDark(model.theme)
            // Icônes des barres système : sombres sur thème clair, claires sur thème sombre.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
                onDispose {}
            }
            KairosTheme(dark = dark) {
                KairosRoot(model)
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
    val repository = Repository(api, stateDir)
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

    private val processStarted: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    // Premier plan de l'application (pas d'une activité : une rotation ne compte pas).
    private val lifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            ReplyWatcher.stop(getApplication())
            session?.conversation?.foreground(true)
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
        viewModelScope.launch {
            val saved = runCatching { settings.load() }.getOrNull()
            speakReplies = saved?.speakReplies ?: false
            theme = saved?.theme ?: ThemeMode.Clair
            session = runCatching { store.load() }.getOrNull()?.let(::open)
            loaded = true
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
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiError) {
        when (e) {
            ApiError.Unauthorized -> "Code refusé par le serveur. Génère-en un nouveau."
            ApiError.Offline -> "Serveur injoignable. Le téléphone est-il sur le bon réseau ?"
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
private fun KairosRoot(model: AppModel) {
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
    val snackbar = remember { SnackbarHostState() }
    val conversation = session.conversation
    val talk = model.talk
    var decisionsOpen by rememberSaveable { mutableStateOf(false) }
    var chatOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(conversation.notice) {
        val notice = conversation.notice ?: return@LaunchedEffect
        conversation.consumeNotice()
        snackbar.showSnackbar(notice)
    }

    val mascot = mascotFor(talk.mode, conversation.busy || conversation.awaiting, conversation.decisions, conversation.unread)
    val deck = rememberOverlayState(decisionsOpen, Origin.Anchor)
    val chat = rememberOverlayState(chatOpen, Origin.Anchor)
    val settings = rememberOverlayState(settingsOpen, Origin.End)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val primary = MaterialTheme.colorScheme.primary
    // Fond révélé quand l'écran principal recule sous un panneau.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant)) {
        Shell(
            modifier = Modifier.underOverlays(rtl, deck, chat, settings),
            kairosModifier = Modifier.overlayAnchor(chat, KairosButtonCorner),
            mascotState = mascot,
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
                    onUnauthorized = model::unauthorized,
                    decisionsAnchor = Modifier.overlayAnchor(deck, DecisionsPeekCorner),
                )
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
                onNotice = { scope.launch { snackbar.showSnackbar(it) } },
                onClose = { decisionsOpen = false },
            )
        }
        Overlay(chat, chatOpen, onClose = { chatOpen = false }, anchorColor = primary) {
            ChatScreen(
                conversation = conversation,
                mascot = mascot,
                focusInput = focusInput,
                onMic = { if (canTalk()) run(talk.startLocked()) },
                onSend = { conversation.send(it, handsFree = false) },
                onClose = { chatOpen = false },
            )
        }
        Overlay(settings, settingsOpen, onClose = { settingsOpen = false }) {
            SettingsScreen(
                theme = model.theme,
                onTheme = model::changeTheme,
                speakReplies = model.speakReplies,
                onSpeakReplies = model::changeSpeakReplies,
                address = maskAddress(session.address),
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
        SnackbarHost(
            snackbar,
            Modifier
                .align(if (chatOpen) Alignment.TopCenter else Alignment.BottomCenter)
                .then(if (chatOpen) Modifier.statusBarsPadding().padding(top = 64.dp) else Modifier.navigationBarsPadding().padding(bottom = 112.dp)),
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
