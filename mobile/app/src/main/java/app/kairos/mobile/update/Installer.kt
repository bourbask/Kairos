package app.kairos.mobile.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Délai laissé à l'utilisateur pour confirmer ; au-delà, la session est abandonnée. */
private const val CONFIRM_TIMEOUT_MS = 10 * 60 * 1000L

/**
 * Installation par une session `PackageInstaller`. Toujours confirmée par l'utilisateur :
 * [confirm] reçoit l'écran de confirmation du système, à ouvrir au premier plan.
 */
class SystemInstaller(private val context: Context, private val confirm: (Intent) -> Unit) : Installer {
    private val action = "${context.packageName}.INSTALL_RESULT"

    /** Écran de confirmation tant que la session l'attend (quitté par Accueil : à remontrer). */
    @Volatile
    var awaiting: Intent? = null
        private set

    @Volatile
    private var results: Channel<Intent>? = null
    @Volatile
    private var sessionId = -1

    /** Renonce à l'installation en attente : [install] rend [UpdateOutcome.Cancelled]. */
    fun abandon() {
        val id = sessionId.takeIf { it >= 0 } ?: return
        awaiting = null
        runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        results?.trySend(Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED))
    }

    override fun allowed(): Boolean = context.packageManager.canRequestPackageInstalls()

    override suspend fun install(apk: File): UpdateOutcome = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        val results = Channel<Intent>(Channel.UNLIMITED)
        this@SystemInstaller.results = results
        sessionId = id
        // Non exporté : seuls le système (par le PendingIntent) et Kairos peuvent y répondre.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == id) results.trySend(intent)
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("kairos.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(callback.intentSender)
            }
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { await(results) } ?: run {
                runCatching { installer.abandonSession(id) }
                UpdateOutcome.Failed("Installation non confirmée : mise à jour abandonnée.")
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            if (e is kotlinx.coroutines.CancellationException) throw e
            UpdateOutcome.Failed("Installation impossible : ${e.message ?: "erreur du téléphone"}.")
        } finally {
            awaiting = null
            this@SystemInstaller.results = null
            sessionId = -1
            context.unregisterReceiver(receiver)
        }
    }

    private suspend fun await(results: Channel<Intent>): UpdateOutcome {
        var outcome: UpdateOutcome? = null
        while (outcome == null) {
            val result = results.receive()
            outcome = when (val status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val screen = result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    if (screen == null) {
                        UpdateOutcome.Failed("Installation impossible : le téléphone n'a pas demandé de confirmation.")
                    } else {
                        awaiting = screen
                        withContext(Dispatchers.Main) { confirm(screen) }
                        null
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> UpdateOutcome.Installed
                PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateOutcome.Cancelled
                else -> UpdateOutcome.Failed(installFailure(status))
            }
        }
        return outcome
    }
}

/** Message d'échec pour un statut `PackageInstaller`. */
internal fun installFailure(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_CONFLICT ->
        "Cette version ne peut pas remplacer l'application installée (signature différente)."
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Version incompatible avec ce téléphone."
    PackageInstaller.STATUS_FAILURE_STORAGE -> "Espace insuffisant sur le téléphone pour installer la mise à jour."
    PackageInstaller.STATUS_FAILURE_INVALID -> "Fichier de mise à jour invalide."
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation bloquée par le téléphone."
    else -> "L'installation a échoué."
}
