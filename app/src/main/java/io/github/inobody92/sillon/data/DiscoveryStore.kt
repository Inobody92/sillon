package io.github.inobody92.sillon.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Ce qu'on ne reproposera pas : refus (pour toujours) et suggestions déjà faites (pendant [DiscoveryTuning.RENEW_AFTER_DAYS] jours). */
data class DiscoveryMemory(val dismissed: Map<String, Long> = emptyMap(), val proposed: Map<String, Long> = emptyMap())

data class DiscoveryState(val date: String? = null, val suggestions: List<Suggestion> = emptyList())

/** Suggestions du jour, refus et historique. Gardés sur le téléphone et sauvegardés sur le NAS avec le profil ([NasProfile]). */
object DiscoveryStore {

    private const val FILE = "discoveries.json"
    private const val DAY_MS = 24L * 3600_000L
    private const val MAX_MEMORY = 3000

    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private lateinit var file: File
    private var dismissed = LinkedHashMap<String, Long>()
    private var proposed = LinkedHashMap<String, Long>()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, FILE)
        runCatching {
            if (!file.exists()) return@runCatching
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("suggestions") ?: JSONArray()
            val list = List(arr.length()) { Suggestion.fromJson(arr.getJSONObject(it)) }.filterNotNull()
            _state.value = DiscoveryState(json.stringOrNull("date"), list)
            Log.d("SillonDiscovery", "relues : " + list.joinToString { "${it.title}(album=${it.albumId})" })
            json.optJSONObject("dismissed")?.let { o -> o.keys().forEach { dismissed[it] = o.getLong(it) } }
            json.optJSONObject("proposed")?.let { o -> o.keys().forEach { proposed[it] = o.getLong(it) } }
        }
    }

    /** Les suggestions de la journée sont-elles déjà calculées ? */
    fun isFresh(today: LocalDate = LocalDate.now()) = _state.value.date == today.toString() && _state.value.suggestions.isNotEmpty()

    @Synchronized
    fun set(list: List<Suggestion>, today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()) {
        // Ce qu'on affiche aujourd'hui compte comme proposé : on ne le remontrera pas demain.
        list.forEach { proposed[it.key] = now }
        _state.value = DiscoveryState(today.toString(), list)
        save()
        NasProfile.requestPush()
    }

    @Synchronized
    fun dismiss(s: Suggestion, now: Long = System.currentTimeMillis()) {
        dismissed[s.key] = now
        remove(s)
    }

    /** La suggestion a été téléchargée : elle quitte la liste et ne sera pas reproposée. */
    @Synchronized
    fun accept(s: Suggestion, now: Long = System.currentTimeMillis()) {
        proposed[s.key] = now
        remove(s)
    }

    private fun remove(s: Suggestion) {
        _state.value = _state.value.copy(suggestions = _state.value.suggestions.filterNot { it.key == s.key })
        save()
        NasProfile.requestPush()
    }

    @Synchronized
    fun excluded(now: Long = System.currentTimeMillis()): Set<String> =
        dismissed.keys + proposed.filterValues { now - it < DiscoveryTuning.RENEW_AFTER_DAYS * DAY_MS }.keys

    @Synchronized
    fun memory() = DiscoveryMemory(dismissed.toMap(), proposed.toMap())

    /** Reprend la mémoire sauvegardée sur le NAS (le plus récent l'emporte pour chaque clé). Vrai si quelque chose a changé ici. */
    @Synchronized
    fun mergeFrom(remote: DiscoveryMemory): Boolean {
        val d = mergeMemory(dismissed, remote.dismissed)
        val p = mergeMemory(proposed, remote.proposed)
        val changed = d != dismissed || p != proposed
        dismissed = LinkedHashMap(d); proposed = LinkedHashMap(p)
        // Un refus fait sur un autre appareil retire la suggestion d'ici aussi.
        val before = _state.value.suggestions
        val after = before.filterNot { it.key in dismissed }
        if (after.size != before.size) _state.value = _state.value.copy(suggestions = after)
        if (changed || after.size != before.size) save()
        return changed
    }

    internal fun mergeMemory(local: Map<String, Long>, remote: Map<String, Long>): Map<String, Long> {
        val out = LinkedHashMap(local)
        remote.forEach { (k, ts) -> if (ts > (out[k] ?: 0L)) out[k] = ts }
        while (out.size > MAX_MEMORY) out.remove(out.entries.minByOrNull { it.value }!!.key)
        return out
    }

    private fun save() {
        runCatching {
            val json = JSONObject().put("date", _state.value.date ?: JSONObject.NULL)
                .put("suggestions", JSONArray().also { a -> _state.value.suggestions.forEach { a.put(it.toJson()) } })
                .put("dismissed", JSONObject().also { o -> dismissed.forEach { (k, v) -> o.put(k, v) } })
                .put("proposed", JSONObject().also { o -> proposed.forEach { (k, v) -> o.put(k, v) } })
            file.writeText(json.toString())
        }
    }
}

/** Calcule les suggestions du jour à partir des goûts et de la bibliothèque. */
object DiscoveryRunner {

    private const val TAG = "SillonDiscovery"
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Vrai si les découvertes sont activées et possibles (il faut un serveur de téléchargement pour donner suite). */
    fun available(context: Context): Boolean {
        DownloadServerStore.init(context)
        return AppSettings.discovery.value && DownloadServerStore.config.value != null
    }

    /** Renvoie le nombre de suggestions du jour (0 si rien à faire ou rien trouvé). [force] recalcule même si c'est déjà fait. */
    suspend fun run(context: Context, source: DiscoverySource = DeezerSource(), force: Boolean = false): Int =
        // Un seul calcul à la fois : une deuxième demande attend, puis voit que c'est déjà fait.
        lock.withLock { compute(context, source, force) }

    private suspend fun compute(context: Context, source: DiscoverySource, force: Boolean): Int {
        val app = context.applicationContext
        AppSettings.init(app)
        if (!available(app)) return 0
        TasteStore.init(app)
        DiscoveryStore.init(app)
        if (!force && DiscoveryStore.isFresh()) return DiscoveryStore.state.value.suggestions.size

        LibraryIndex.init(app)
        var waited = 0
        while (!LibraryIndex.isReady && waited < 20_000) { delay(500); waited += 500 }
        val library = LibraryIndex.allSongs()
        if (library.isEmpty()) return 0

        val libraryArtists = HashSet<String>()
        val libraryAlbums = HashSet<String>()
        library.forEach { s ->
            libraryArtists.add(artistKey(s)); libraryArtists.add(normalizeKey(s.artist))
            libraryAlbums.add(songAlbumKey(s))
            libraryAlbums.add(albumKey(baseTitle(s.album), s.albumArtist.ifBlank { s.artist }))
        }
        HiddenRules.init(app)
        val filters = DiscoveryFilters(libraryArtists, libraryAlbums, { HiddenRules.isArtistHidden(it) }, DiscoveryStore.excluded())
        val recent = LibraryIndex.songsByIds(PlayHistory.recentOrdered(app, 60))
        val seeds = pickSeeds(TasteStore.profile, library, recent, isHidden = { HiddenRules.isArtistHidden(it) })
        if (seeds.isEmpty()) return 0
        return try {
            val list = DiscoveryEngine(source).compute(seeds, filters)
            Log.i(TAG, "${seeds.size} artistes de départ → ${list.size} suggestions : ${list.joinToString { "${it.kind.name.take(3)}:${it.title}" }}")
            if (list.isNotEmpty()) DiscoveryStore.set(list)
            list.size
        } catch (e: Exception) {
            Log.w(TAG, "découvertes impossibles : ${e.message}")
            0
        }
    }
}

/** Lance un téléchargement à partir d'une suggestion, via le serveur de téléchargement de l'utilisateur. */
object DiscoveryActions {
    /**
     * Un album pour une sortie récente. Pour un artiste, [count] titres parmi les plus populaires, ou toute sa
     * discographie studio si [count] vaut 0 (le choix est laissé à l'utilisateur).
     */
    fun requestFor(s: Suggestion, count: Int = 0): DownloadRequest =
        if (s.albumId != null) DownloadRequest.Album(s.albumId) else DownloadRequest.Artist(s.artistId, count)

    /** Renvoie le message à afficher. */
    suspend fun download(context: Context, s: Suggestion, count: Int = 0): String {
        val app = context.applicationContext
        DownloadServerStore.init(app)
        val config = DownloadServerStore.config.value ?: return "Serveur de téléchargement non configuré"
        val client = DownloadServerClient.create(config)
        val request = requestFor(s, count)
        return try {
            val task = when (request) {
                is DownloadRequest.Album -> client.downloadAlbum(request.id)
                is DownloadRequest.Artist -> client.downloadArtist(request.id, request.count)
                else -> error("demande inattendue")
            }
            DownloadRequestLog(app).remember(task.number, task.name, request)
            DownloadWatch.watch(app, task.number)
            DiscoveryStore.init(app)
            DiscoveryStore.accept(s)
            "Téléchargement lancé : ${task.name}"
        } catch (e: DownloadServerException) {
            e.message ?: "Téléchargement impossible"
        }
    }
}

/** Planification du calcul quotidien (au plus une fois par jour, quand le téléphone a du réseau). */
object DiscoveryScheduler {
    private const val WORK = "daily-discoveries"

    fun update(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (DiscoveryRunner.available(context)) {
            val request = PeriodicWorkRequestBuilder<DiscoveryWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }
}

class DiscoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        AppSettings.init(ctx)
        DiscoveryStore.init(ctx)
        val before = DiscoveryStore.isFresh()
        val count = DiscoveryRunner.run(ctx)
        // Notification seulement pour une nouvelle série du jour, pas si l'application l'avait déjà calculée.
        if (!before && count > 0 && AppSettings.discoveryNotify.value) notify(ctx, DiscoveryStore.state.value.suggestions)
        return Result.success()
    }

    private fun notify(ctx: Context, list: List<Suggestion>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Découvertes du jour", NotificationManager.IMPORTANCE_DEFAULT))
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val names = list.take(3).joinToString(", ") { it.artist }
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(if (list.size == 1) "1 découverte pour toi" else "${list.size} découvertes pour toi")
            .setContentText(names + if (list.size > 3) "…" else "")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { nm.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) {}
    }

    private companion object {
        const val CHANNEL = "daily_discoveries"
        const val NOTIFICATION_ID = 4001
    }
}
