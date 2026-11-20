package io.github.inobody92.sillon.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.inobody92.sillon.ui.plural
import kotlinx.coroutines.delay

/**
 * Suivi des demandes envoyées au serveur de téléchargement, même application fermée : une notification
 * annonce la fin de chaque demande (terminée, échouée ou annulée).
 *
 * On ne suit que les demandes lancées depuis l'application : leurs numéros sont mémorisés ici, et le travail
 * de fond interroge le serveur jusqu'à ce que toutes soient annoncées.
 */
object DownloadWatch {

    private const val WORK_NAME = "watch-server-downloads"
    private const val PREFS = "download_watch"
    private const val KEY = "numbers"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun watched(context: Context): Set<Int> =
        prefs(context).getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    internal fun forget(context: Context, numbers: Collection<Int>) {
        if (numbers.isEmpty()) return
        val left = watched(context) - numbers.toSet()
        prefs(context).edit().putStringSet(KEY, left.map { it.toString() }.toSet()).apply()
    }

    /** Suit la demande [number] : elle sera annoncée par une notification quand le serveur l'aura terminée. */
    fun watch(context: Context, number: Int) {
        val left = watched(context) + number
        prefs(context).edit().putStringSet(KEY, left.map { it.toString() }.toSet()).apply()
        start(context)
    }

    /** À appeler au lancement : reprend le suivi si l'application a été tuée alors que des demandes étaient en cours. */
    fun resume(context: Context) {
        if (watched(context).isNotEmpty()) start(context)
    }

    private fun start(context: Context) {
        val request = OneTimeWorkRequestBuilder<DownloadWatchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // APPEND_OR_REPLACE : si un suiveur est en train de se terminer, le nouveau prend le relais.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/** Texte d'une notification de fin de demande. */
internal data class FinishedNotice(val title: String, val text: String)

/** Les demandes terminées qui font partie de celles qu'on suit. */
internal fun finishedToAnnounce(queue: RemoteQueue, watched: Set<Int>): List<RemoteFinished> =
    queue.finished.filter { it.number in watched }

internal fun noticeFor(f: RemoteFinished): FinishedNotice = when (f.status) {
    "echec" -> FinishedNotice("Échec : ${f.name}", f.error ?: "Le téléchargement a échoué")
    "annule" -> FinishedNotice("Annulé : ${f.name}", "Téléchargement annulé")
    else -> {
        val text = when {
            f.downloaded == 0 -> "Déjà dans la bibliothèque"
            f.errors > 0 -> "${plural(f.downloaded, "titre")} ajouté${if (f.downloaded > 1) "s" else ""} · ${plural(f.errors, "échec")}"
            else -> "${plural(f.downloaded, "titre")} ajouté${if (f.downloaded > 1) "s" else ""} à la bibliothèque"
        }
        FinishedNotice(f.name, text)
    }
}

class DownloadWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        DownloadServerStore.init(ctx)
        val config = DownloadServerStore.config.value
        if (config == null) {
            DownloadWatch.forget(ctx, DownloadWatch.watched(ctx))
            return Result.success()
        }
        val client = DownloadServerClient.create(config)
        var failures = 0
        val absent = mutableMapOf<Int, Int>() // sondages consécutifs où une demande n'apparaît nulle part

        while (true) {
            val watched = DownloadWatch.watched(ctx)
            if (watched.isEmpty()) return Result.success()

            val queue = try {
                client.queue().also { failures = 0 }
            } catch (e: DownloadServerException) {
                // Jeton refusé : inutile d'insister. Réseau ou serveur indisponible : on réessaie ~10 minutes.
                if (e.kind == DownloadServerException.Kind.AUTH || ++failures >= 60) {
                    DownloadWatch.forget(ctx, watched)
                    return Result.success()
                }
                delay(10_000)
                continue
            }

            val done = finishedToAnnounce(queue, watched)
            done.forEach { notifyFinished(ctx, it) }
            DownloadWatch.forget(ctx, done.map { it.number })

            // Une demande introuvable (serveur redémarré, historique purgé) ne doit pas être attendue indéfiniment.
            val known = queue.tasks.map { it.number }.toSet() + queue.finished.map { it.number }
            val lost = (watched - known).filter { n -> (absent.merge(n, 1, Int::plus) ?: 0) >= 5 }
            absent.keys.retainAll(watched - known)
            DownloadWatch.forget(ctx, lost)

            val current = queue.tasks.filter { it.number in watched }.let { l -> l.firstOrNull { it.running } ?: l.firstOrNull() }
            if (current != null) {
                try {
                    setForeground(foregroundInfo(ctx, current))
                } catch (_: Exception) {
                    // Démarrage d'un service refusé en arrière-plan : on suit quand même, sans notification de progression.
                }
            }
            delay(4_000)
        }
    }

    private fun canNotify(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notifyFinished(ctx: Context, f: RemoteFinished) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Téléchargements terminés", NotificationManager.IMPORTANCE_DEFAULT))
        val notice = noticeFor(f)
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(ctx, DONE_CHANNEL)
            .setSmallIcon(if (f.status == "echec") android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(DONE_ID_BASE + f.number, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun foregroundInfo(ctx: Context, task: RemoteTask): ForegroundInfo {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "Suivi du serveur de téléchargement", NotificationManager.IMPORTANCE_LOW))
        val text = when {
            !task.running -> "En file d'attente"
            task.total > 0 -> "${task.done}/${task.total}" + (task.currentTrack?.let { " · $it" } ?: "")
            else -> task.currentTrack ?: "En cours"
        }
        val builder = NotificationCompat.Builder(ctx, PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.name)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (task.running && task.total > 0) builder.setProgress(task.total, task.done, false)
        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, notification)
        }
    }

    private companion object {
        const val DONE_CHANNEL = "server_downloads_done"
        const val PROGRESS_CHANNEL = "server_downloads_progress"
        const val PROGRESS_ID = 2002
        const val DONE_ID_BASE = 3000
    }
}
