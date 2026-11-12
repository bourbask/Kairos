package app.kairos.mobile.ui.politique

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Repository
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.kit.SpaceScaffold
import app.kairos.mobile.ui.mimir.ShareDialog
import app.kairos.mobile.ui.mimir.SharesPanel
import app.kairos.mobile.ui.politique.kit.AtTable
import app.kairos.mobile.ui.politique.kit.BalanceSheet
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.PolSheet
import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolPage
import app.kairos.mobile.ui.politique.model.loadPolData
import app.kairos.mobile.ui.politique.screens.BilanScreen
import app.kairos.mobile.ui.politique.screens.BulleScreen
import app.kairos.mobile.ui.politique.screens.ClaimSheetAt
import app.kairos.mobile.ui.politique.screens.PhrasesScreen
import app.kairos.mobile.ui.politique.screens.QuizScreen
import app.kairos.mobile.ui.politique.screens.DitVoteScreen
import app.kairos.mobile.ui.politique.screens.RevirementsScreen
import app.kairos.mobile.ui.politique.screens.ScrutinSheetAt
import app.kairos.mobile.ui.politique.screens.VotesScreen
import app.kairos.mobile.ui.politique.screens.ArgentScreen
import app.kairos.mobile.ui.politique.screens.BudgetsScreen
import app.kairos.mobile.ui.politique.screens.MediasScreen
import app.kairos.mobile.ui.politique.screens.ProbiteScreen
import app.kairos.mobile.ui.politique.screens.LivingScreen
import app.kairos.mobile.ui.politique.screens.VillesScreen
import app.kairos.mobile.ui.politique.screens.CitySheet
import app.kairos.mobile.ui.politique.screens.ClusterSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Module de la tête et identifiant de l'espace (scène du personnage comprise). */
const val POLITIQUE = "politique"

/** Raison lisible d'un échec du module ; [ApiError.Unauthorized] remonte. */
fun politiqueIssue(e: ApiError): String = when (e) {
    ApiError.Unauthorized -> throw e
    ApiError.Offline -> "Hors ligne : serveur injoignable."
    ApiError.NoReply -> "Pas de réponse du serveur."
    is ApiError.Server -> when {
        e.outdated -> "${ApiError.Server.OUTDATED}."
        e.code == 404 -> "Module Politique absent du serveur."
        e.code == 422 -> e.message ?: "Refusé."
        e.unreadable -> "${ApiError.Server.UNREADABLE} : serveur ou application à mettre à jour."
        else -> "${e.unavailable()}."
    }
}

/**
 * Espace Politique : données publiques sourcées, en lecture seule, chargées en mémoire à
 * l'ouverture (fichiers paginés du module [POLITIQUE]). Accueil, rubriques, feuilles par-dessus.
 * Le site complet ([site]) et le partage restent accessibles même quand le module ne répond pas.
 * Retour : feuille, puis vue précédente, puis fermeture de l'espace.
 */
@Composable
fun PolitiqueScreen(
    repository: Repository,
    padding: PaddingValues,
    appScope: CoroutineScope,
    onClose: () -> Unit,
    onUnauthorized: () -> Unit,
    site: @Composable (onClose: () -> Unit) -> Unit,
) {
    val unauthorized by rememberUpdatedState(onUnauthorized)
    var reload by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<PolLoad>(PolLoad.Loading) }
    var nav by remember { mutableStateOf(PolBackStack()) }
    var table by remember { mutableStateOf<Pair<List<TableCard>, Int>?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val navigator = remember {
        object : PolNavigator {
            override fun go(r: PolRoute) { nav = nav.go(r) }
            override fun sheet(s: PolSheetState) { nav = nav.open(s) }
            override fun table(cards: List<TableCard>, start: Int) { table = cards to start }
        }
    }

    LaunchedEffect(reload) {
        // Les feuilles ouvertes portent des données qui vont être remplacées.
        nav = PolBackStack(nav.routes)
        table = null
        load = PolLoad.Loading
        load = try {
            PolLoad.Ready(
                loadPolData(fetch = { id, page ->
                    repository.lookup(POLITIQUE, "app_politique_fichier", PolPage.serializer(), buildJsonObject { put("id", id); put("page", page) })
                }),
            )
        } catch (_: ApiError.Unauthorized) {
            unauthorized()
            return@LaunchedEffect
        } catch (e: ApiError) {
            PolLoad.Down(politiqueIssue(e))
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            // Un fichier a changé pendant la lecture, deux fois de suite.
            PolLoad.Down("Données en cours de mise à jour sur le serveur.")
        }
    }
    val data = (load as? PolLoad.Ready)?.data
    val route = nav.top

    // Avant le site : ses propres retours (page précédente, formulaires) passent d'abord.
    BackHandler(enabled = nav.routes.isNotEmpty()) { nav = nav.back() ?: nav }
    if (route == PolRoute.Site) {
        site { nav = nav.back() ?: nav }
        return
    }

    SpaceScaffold(
        when (route) {
            PolRoute.Home, PolRoute.Site -> "Politique"
            is PolRoute.Rubric -> rubricTitle(route.id)
            PolRoute.Quiz -> "Vrai ou faux ?"
            is PolRoute.Duel -> "Duel"
            PolRoute.Shares -> "Accès partagés"
        },
        onBack = { nav.back()?.let { nav = it } ?: onClose() },
        padding = padding,
        subtitle = if (route == PolRoute.Home) verifiedLabel(data?.verifiedOn) else null,
        backLabel = if (route == PolRoute.Home) "Fermer l'espace Politique" else "Retour",
        actions = {
            IconButton(onClick = { sharing = true }) {
                Icon(KairosIcons.Share, contentDescription = "Partager le site", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(KairosIcons.Gear, contentDescription = "Site et accès", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Ouvrir le site") }, onClick = { menu = false; navigator.go(PolRoute.Site) })
                    if (route != PolRoute.Shares) DropdownMenuItem(text = { Text("Accès partagés") }, onClick = { menu = false; navigator.go(PolRoute.Shares) })
                    if (route == PolRoute.Home) DropdownMenuItem(text = { Text("Recharger") }, onClick = { menu = false; reload++ })
                }
            }
        },
    ) { inner ->
        val bottom = inner.calculateBottomPadding()
        if (route == PolRoute.Shares) {
            SharesPanel(repository, appScope, bottom, onUnauthorized)
            return@SpaceScaffold
        }
        when (val l = load) {
            PolLoad.Loading -> HomeLoading()
            is PolLoad.Down -> HomeDown(l, onRetry = { reload++ }, onSite = { navigator.go(PolRoute.Site) })
            is PolLoad.Ready -> when (route) {
                PolRoute.Home -> HomeScreen(l.data, navigator, bottom)
                else -> Box(Modifier.fillMaxSize().padding(bottom = bottom)) {
                    when (route) {
                        is PolRoute.Rubric -> RubricHost(route.id, l.data, navigator)
                        PolRoute.Quiz -> QuizScreen(l.data, navigator)
                        else -> Soon()
                    }
                }
            }
        }
    }

    if (data != null) nav.sheets.forEachIndexed { i, s ->
        key(i, s) { SheetHost(s, data, navigator, onDismiss = { nav = nav.back() ?: nav }) }
    }
    table?.let { (cards, start) -> AtTable(cards, start) { table = null } }
    if (sharing) ShareDialog(repository, onDismiss = { sharing = false }, onUnauthorized = onUnauthorized)
}

/** Écran d'une rubrique ; chaque écran ajoute ici sa branche. */
@Composable
fun RubricHost(id: String, data: PolData, nav: PolNavigator) {
    when (id) {
        "budgets" -> BudgetsScreen(data, nav)
        "niveau-de-vie" -> LivingScreen(data, nav)
        "bilan" -> BilanScreen(data, nav)
        "phrases" -> PhrasesScreen(data, nav)
        "bulle" -> BulleScreen(data, nav)
        "votes" -> VotesScreen(data, nav)
        "revirements" -> RevirementsScreen(data, nav)
        "dit-vote" -> DitVoteScreen(data, nav)
        "probite" -> ProbiteScreen(data, nav)
        "argent" -> ArgentScreen(data, nav)
        "medias" -> MediasScreen(data, nav)
        "villes" -> VillesScreen(data, nav)
        else -> Soon()
    }
}

/** Feuille ouverte ; chaque écran qui en ajoute une ajoute ici sa branche. */
@Composable
fun SheetHost(s: PolSheetState, data: PolData, nav: PolNavigator, onDismiss: () -> Unit) {
    when (s) {
        is PolSheetState.Identity -> IdentitySheet(data, s.id, nav, onDismiss)
        is PolSheetState.Claim -> ClaimSheetAt(data, s.index, nav, onDismiss)
        is PolSheetState.Scrutin -> ScrutinSheetAt(data, s.id, nav, onDismiss)
        is PolSheetState.Balance -> BalanceSheet(s.lecture, s.title, s.who, data, { nav.sheet(PolSheetState.Evaluator(it)) }, onDismiss)
        is PolSheetState.City -> CitySheet(data, s.name, nav, onDismiss)
        is PolSheetState.Cluster -> ClusterSheet(data, s.villes, nav, onDismiss)
        is PolSheetState.Evaluator -> PolSheet(full = false, onDismiss = onDismiss) { Soon() }
    }
}

@Composable
private fun Soon() = EmptyState("Bientôt", "Cet écran arrive avec une prochaine version de l'espace. Les autres rubriques restent ouvertes depuis l'accueil.", Modifier.padding(16.dp))
