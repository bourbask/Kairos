package app.kairos.mobile.ui.mimir

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebStorage
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Repository
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.kit.SpaceScaffold
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.kit.Tone
import kotlinx.coroutines.CoroutineScope

/**
 * Espace Mimir : un site web affiché dans une WebView, à partir d'un lien de partage collé
 * sur l'appareil (jamais dans le code). Sans lien, l'espace en demande un à la tête pour
 * son propriétaire, et à défaut demande de le coller. « Partager » émet un lien neuf à
 * usage unique ; « Accès partagés » liste et révoque les partages. Le lien
 * porte un jeton : il n'est ni journalisé, ni affiché, ni gardé dans l'état sauvegardé, et
 * il est remplacé par la seule origine du site dès que la session est ouverte.
 * Retour : page précédente du site s'il y en a une, sinon fermeture de l'espace.
 */
@Composable
fun MimirScreen(
    link: String?,
    padding: PaddingValues,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    repository: Repository,
    appScope: CoroutineScope,
    onUnauthorized: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var sharesOpen by rememberSaveable { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    // Lien du propriétaire demandé une fois par ouverture de l'espace ; échec : saisie manuelle.
    var ownerTried by remember { mutableStateOf(false) }
    var ownerBusy by remember { mutableStateOf(false) }
    var ownerNote by remember { mutableStateOf<String?>(null) }
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val save by rememberUpdatedState(onSave)
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // Nouveau lien d'une autre origine, en attente de confirmation (jamais sauvegardé).
    var pendingSwitch by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // Incrémenté par « Recharger » : la page repart du lien configuré.
    var reload by remember { mutableIntStateOf(0) }
    val showForm = link == null || editing
    val bottom = padding.calculateBottomPadding()

    // Modification abandonnée : retour à la page, l'espace reste ouvert.
    BackHandler(enabled = editing && link != null) { editing = false }
    BackHandler(enabled = sharesOpen) { sharesOpen = false }

    LaunchedEffect(link == null) {
        if (link != null || ownerTried) return@LaunchedEffect
        ownerTried = true
        ownerBusy = true
        try {
            // Le lien rejoint le parcours habituel : gardé jusqu'à l'ouverture de session, puis réduit à l'origine.
            save(createShareLink(repository, OWNER_LABEL))
        } catch (_: ApiError.Unauthorized) {
            unauthorized()
        } catch (e: ApiError) {
            ownerNote = "Lien automatique indisponible : ${shareIssue(e)}"
        } finally {
            ownerBusy = false
        }
    }

    SpaceScaffold(
        "Mimir",
        onClose,
        padding,
        subtitle = "Site partagé",
        actions = {
            IconButton(onClick = { sharing = true }) {
                Icon(KairosIcons.Share, contentDescription = "Partager", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(KairosIcons.Gear, contentDescription = "Lien Mimir", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (sharesOpen) "Revenir au site" else "Accès partagés") }, onClick = {
                        menu = false
                        sharesOpen = !sharesOpen
                    })
                    if (!showForm && !sharesOpen) DropdownMenuItem(text = { Text("Recharger") }, onClick = {
                        menu = false
                        reload++
                    })
                    if (!showForm && !sharesOpen) DropdownMenuItem(text = { Text("Modifier le lien") }, onClick = {
                        menu = false
                        editing = true
                    })
                    if (!showForm && !sharesOpen) DropdownMenuItem(text = { Text("Effacer le lien") }, onClick = {
                        menu = false
                        confirmClear = true
                    })
                }
            }
        },
    ) { _ ->
        if (sharesOpen) {
            SharesPanel(repository, appScope, bottom, onUnauthorized)
        } else if (link == null && ownerBusy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "Ouverture de Mimir" })
            }
        } else if (showForm) {
            LinkForm(
                bottom = bottom,
                note = ownerNote.takeIf { link == null },
                onSave = {
                    if (link != null && !sameOrigin(link, it)) {
                        pendingSwitch = it
                    } else {
                        onSave(it)
                        editing = false
                    }
                },
                onCancel = if (link != null) {
                    { editing = false }
                } else {
                    null
                },
            )
        } else {
            MimirWeb(link!!, reload, bottom, onSettled = onSave)
        }
    }

    if (sharing) ShareDialog(repository, onDismiss = { sharing = false }, onUnauthorized = onUnauthorized)

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer le lien Mimir ?") },
            text = { Text("Le lien et la session du site seront oubliés sur ce téléphone. Il faudra le coller à nouveau.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    forgetSiteData(context)
                    onClear()
                }) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }

    pendingSwitch?.let { next ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("Changer de site ?") },
            text = { Text("Ce lien mène à un autre site. La session, les cookies, le cache et l'historique du site actuel seront effacés.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitch = null
                    forgetSiteData(context)
                    onSave(next)
                    editing = false
                }) { Text("Changer") }
            },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text("Annuler") } },
        )
    }
}

/** Saisie du lien de partage : validé avant d'être enregistré, refusé avec un message clair. */
@Composable
private fun LinkForm(bottom: Dp, note: String?, onSave: (String) -> Unit, onCancel: (() -> Unit)?) {
    // Pas de rememberSaveable : le jeton n'a pas à entrer dans l'état sauvegardé de l'activité.
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        normalizeMimirLink(text)
            .onSuccess { onSave(it) }
            .onFailure { error = it.message ?: "Lien invalide." }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        note?.let { StatusBanner(it, tone = Tone.Attention) }
        Text("Colle ton lien de partage Mimir", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Il commence par https:// et reste sur ce téléphone, chiffré.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            label = { Text("https://…") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Annuler") }
            Button(onClick = { submit() }, enabled = text.isNotBlank()) { Text("Enregistrer") }
        }
    }
}

/**
 * Le site, dans une WebView propre à l'espace (détruite à sa fermeture). Chargement : barre
 * de progression, et indicateur central tant que rien n'est affiché (pas d'écran blanc en
 * thème sombre). Erreur de la page principale : message et « Réessayer ».
 * Session ouverte (cookie posé par le site) : [onSettled] reçoit l'origine, qui remplace le
 * lien enregistré ; la même WebView continue, sans rechargement.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MimirWeb(link: String, reload: Int, bottom: Dp, onSettled: (String) -> Unit) {
    val context = LocalContext.current
    val background = MaterialTheme.colorScheme.background
    val origin = mimirOrigin(link) ?: link
    val current by rememberUpdatedState(link)
    val settled by rememberUpdatedState(onSettled)
    var progress by remember(origin) { mutableIntStateOf(0) }
    var loading by remember(origin) { mutableStateOf(true) }
    var shown by remember(origin) { mutableStateOf(false) }
    var failure by remember(origin) { mutableStateOf<String?>(null) }
    var canGoBack by remember(origin) { mutableStateOf(false) }

    // Lien complet gardé tant qu'aucune session n'existe, pour pouvoir réessayer. Vérifié
    // pendant la navigation seulement : à la fermeture, l'effacement des cookies est en cours.
    fun settle() {
        if (!sameOrigin(current, origin)) return
        val next = mimirStoredLink(current, CookieManager.getInstance().getCookie(origin) != null)
        if (next != current) settled(next)
    }

    val web = remember(origin) {
        WebView(context).apply {
            setBackgroundColor(background.toArgb())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Les liens « nouvelle fenêtre » passent par onCreateWindow, qui les aiguille comme une navigation.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            val self = this
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(self, false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    return when (mimirRoute(origin, request.url.toString())) {
                        MimirRoute.InApp -> false
                        MimirRoute.External -> {
                            openExternally(view.context, request.url)
                            true
                        }
                        MimirRoute.Blocked -> true
                    }
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loading = true
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    if (failure == null) {
                        shown = true
                        settle()
                    }
                    CookieManager.getInstance().flush()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    canGoBack = view.canGoBack()
                    if (failure == null) settle()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failure = "Page injoignable. Vérifie ta connexion."
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (!request.isForMainFrame || response.statusCode < 400) return
                    failure = when (response.statusCode) {
                        401, 403 -> "Lien refusé par le site : vérifie-le ou colle-en un nouveau."
                        else -> "Le site a répondu ${response.statusCode}."
                    }
                }

                // Jamais de passe-droit sur un certificat invalide.
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    failure = "Certificat du site refusé."
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }

                // Rien dans le journal système : les messages de la page peuvent citer l'adresse (et son jeton).
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true

                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    if (!isUserGesture) return false
                    // WebView jetable : elle ne sert qu'à connaître l'adresse demandée.
                    val probe = WebView(view.context)
                    var handled = false
                    fun hand(uri: Uri?) {
                        if (handled) return
                        handled = true
                        if (uri != null) {
                            when (mimirRoute(origin, uri.toString())) {
                                MimirRoute.InApp -> view.loadUrl(uri.toString())
                                MimirRoute.External -> openExternally(view.context, uri)
                                MimirRoute.Blocked -> Unit
                            }
                        }
                        probe.post { probe.destroy() }
                    }
                    probe.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                            hand(request.url)
                            return true
                        }

                        // Selon la version du moteur, la première adresse n'arrive qu'ici.
                        override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                            if (url == null || url == "about:blank") return
                            v.stopLoading()
                            hand(Uri.parse(url))
                        }
                    }
                    // Aucune adresse dans le délai : la WebView jetable est détruite quand même.
                    view.postDelayed({ hand(null) }, PROBE_TIMEOUT_MS)
                    (resultMsg.obj as WebView.WebViewTransport).webView = probe
                    resultMsg.sendToTarget()
                    return true
                }
            }
        }
    }

    DisposableEffect(web) {
        web.loadUrl(mimirEmbedUrl(link))
        onDispose {
            CookieManager.getInstance().flush()
            web.stopLoading()
        }
    }
    // « Recharger » du menu : repart du lien configuré.
    var lastReload by remember(origin) { mutableIntStateOf(reload) }
    LaunchedEffect(web, reload) {
        if (reload == lastReload) return@LaunchedEffect
        lastReload = reload
        failure = null
        web.loadUrl(mimirEmbedUrl(current))
    }
    LifecycleResumeEffect(web) {
        web.onResume()
        onPauseOrDispose {
            web.onPause()
            CookieManager.getInstance().flush()
        }
    }
    BackHandler(enabled = canGoBack && failure == null) { web.goBack() }

    // Au-dessus du dock ; le clavier ne masque pas les champs du site (bord à bord : pas de redimensionnement).
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottom)
            .consumeWindowInsets(PaddingValues(bottom = bottom))
            .imePadding()
            .background(background),
    ) {
        // Couleur suivie au changement de thème : pas d'éclair blanc avant que la page ne peigne.
        // Détruite une fois retirée de l'écran, à la fermeture de l'espace.
        AndroidView(
            factory = { web },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.destroy() },
            update = { it.setBackgroundColor(background.toArgb()) },
        )
        if (!shown && failure == null) {
            Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "Chargement de Mimir" })
            }
        }
        if (loading && failure == null) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                drawStopIndicator = {},
            )
        }
        failure?.let { message ->
            Column(
                Modifier.fillMaxSize().background(background).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusBanner(
                    "Mimir indisponible",
                    tone = Tone.Urgent,
                    detail = message,
                    actionLabel = "Réessayer",
                    onAction = {
                        failure = null
                        loading = true
                        if (web.url == null) web.loadUrl(mimirEmbedUrl(link)) else web.reload()
                    },
                )
                Text(
                    "Le lien se modifie depuis l'icône en haut à droite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

/** Ouvre [uri] hors de l'application (navigateur, messagerie, téléphone) ; sans application capable, rien. */
private fun openExternally(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Aucun gestionnaire pour ce schéma : la page reste où elle est.
    }
}

/** Délai laissé à une nouvelle fenêtre pour révéler son adresse. */
private const val PROBE_TIMEOUT_MS = 3_000L

/**
 * Lien effacé ou site changé : session, cookies, stockage, cache, historique et formulaires
 * oubliés. Seul Mimir utilise une WebView : tout effacer revient à effacer ce site.
 */
@Suppress("DEPRECATION")
private fun forgetSiteData(context: Context) {
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        flush()
    }
    WebStorage.getInstance().deleteAllData()
    WebView(context).apply {
        clearCache(true)
        clearHistory()
        clearFormData()
        destroy()
    }
    WebViewDatabase.getInstance(context).apply {
        clearFormData()
        clearHttpAuthUsernamePassword()
    }
}
