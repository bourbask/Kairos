package app.kairos.mobile.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.kairos.mobile.MainActivity
import app.kairos.mobile.R
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.ChatEntry
import app.kairos.mobile.data.KairosApi
import app.kairos.mobile.data.SecureStore
import app.kairos.mobile.http
import app.kairos.mobile.ui.talk.PendingReply
import kotlinx.coroutines.delay

/**
 * Attente d'une réponse après que l'application a quitté le premier plan :
 * sondage borné à l'échéance de la demande (10 minutes au plus), puis
 * notification locale avec le début de la réponse. Aucun service permanent.
 */
class ReplyWatcher(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val after = inputData.getLong(AFTER, -1)
        val deadline = inputData.getLong(DEADLINE, 0)
        if (after < 0) return Result.success()
        val credentials = runCatching { SecureStore(applicationContext).load() }.getOrNull() ?: return Result.success()
        val api = KairosApi(credentials.baseUrl, credentials.token, http)
        // Au moins un sondage, même si l'échéance est passée pendant la mise en file.
        do {
            try {
                val entries = api.chat(after, PAGE)
                val reply = replyToNotify(entries, api.status().busy)
                if (reply != null) {
                    notify(reply.text)
                    return Result.success()
                }
            } catch (_: ApiError.Unauthorized) {
                return Result.success()
            } catch (_: ApiError) {
                // Serveur momentanément injoignable : on réessaie jusqu'à l'échéance.
            }
            if (System.currentTimeMillis() >= deadline) break
            delay(POLL_MS)
        } while (true)
        return Result.success()
    }

    private fun notify(text: String) {
        val context = applicationContext
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Réponses de Kairos", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val excerpt = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > EXCERPT) it.take(EXCERPT) + "…" else it }
        // Écran verrouillé : seulement « Kairos a répondu », sans le contenu.
        val public = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_kairos)
            .setContentTitle("Kairos a répondu")
            .build()
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_kairos)
            .setContentTitle("Kairos a répondu")
            .setContentText(excerpt)
            .setStyle(android.app.Notification.BigTextStyle().bigText(excerpt))
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NAME = "attente-reponse"
        private const val CHANNEL = "reponses"
        private const val NOTIFICATION_ID = 1
        private const val AFTER = "after"
        private const val DEADLINE = "deadline"
        private const val PAGE = 50
        private const val POLL_MS = 5_000L
        private const val EXCERPT = 160

        fun start(context: Context, reply: PendingReply) {
            val request = OneTimeWorkRequestBuilder<ReplyWatcher>()
                .setInputData(workDataOf(AFTER to reply.after, DEADLINE to reply.deadline))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}

/**
 * Réponse à notifier : la dernière de Kairos, une fois le traitement terminé.
 * Tant que [busy], un message d'attente (« je regarde… ») n'est pas la réponse.
 */
internal fun replyToNotify(entries: List<ChatEntry>, busy: Boolean): ChatEntry? =
    if (busy) null else entries.lastOrNull { it.role == "kairos" }
