package io.github.inobody92.sillon.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TasteState(val profile: TasteProfile = TasteProfile(emptyList()), val loading: Boolean = false, val loadedAt: Long = 0L)

/**
 * Notes de la bibliothèque et profil de goûts, gardés sur le téléphone. Le profil mêle, du plus fort au plus faible :
 * les notes (relues sur le NAS au plus une fois toutes les [REFRESH_MS]), les likes (= 5 étoiles), les titres masqués
 * (= 1 étoile) et l'écoute (titres écoutés en entier, titres passés).
 */
object TasteStore {

    private const val FILE = "taste_ratings.json"
    private const val PAGE = 500
    private const val REFRESH_MS = 6L * 3600_000L
    private const val REBUILD_DELAY_MS = 4_000L
    private const val TAG = "SillonTaste"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var file: File
    private lateinit var appContext: Context
    private var rated: MutableMap<String, RatedSong> = mutableMapOf()
    private var rebuildJob: Job? = null

    private val _state = MutableStateFlow(TasteState())
    val state: StateFlow<TasteState> = _state.asStateFlow()

    val profile: TasteProfile get() = _state.value.profile

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        appContext = context.applicationContext
        file = File(appContext.filesDir, FILE)
        Likes.init(appContext)
        Hidden.init(appContext)
        NasProfile.init(appContext)
        DiscoveryStore.init(appContext)
        ListenStats.init(appContext)
        runCatching { load() }.onFailure { Log.w(TAG, "lecture impossible", it) }
        // Un like, un masquage ou l'index qui devient prêt changent le profil.
        scope.launch {
            combine(
                Likes.state.map { it.ids }.distinctUntilChanged(),
                Hidden.state.map { it.ids }.distinctUntilChanged(),
                LibraryIndex.state.map { it.ready }.distinctUntilChanged(),
            ) { _, _, _ -> }.collect { requestRebuild() }
        }
    }

    /** Recalcule le profil bientôt (regroupe les demandes rapprochées : une écoute, un like…). */
    fun requestRebuild() {
        rebuildJob?.cancel()
        rebuildJob = scope.launch {
            delay(REBUILD_DELAY_MS)
            lock.withLock { publish(_state.value.loadedAt) }
        }
    }

    /** Relit les notes du NAS si elles datent de plus de [REFRESH_MS] (ou si [force]). */
    fun refresh(client: SynoClient, force: Boolean = false) {
        scope.launch {
            lock.withLock {
                val age = System.currentTimeMillis() - _state.value.loadedAt
                if (!force && age < REFRESH_MS) return@withLock
                _state.value = _state.value.copy(loading = true)
                try {
                    val total = client.freshSongTotal()
                    val pages = coroutineScope {
                        val gate = Semaphore(3)
                        (0 until total step PAGE).map { offset -> async { gate.withPermit { client.libraryRatingsPage(offset, PAGE) } } }
                            .flatMap { it.await() }
                    }
                    val next = HashMap<String, RatedSong>()
                    pages.forEach { (song, rating) ->
                        if (rating in 1..5) next[song.id] = RatedSong(song.id, rating, artistName(song), song.genre.trim())
                    }
                    Log.i(TAG, "${pages.size} titres lus, ${next.size} notés")
                    rated = next
                    publish(System.currentTimeMillis())
                    save()
                } catch (e: Exception) {
                    Log.w(TAG, "relecture des notes impossible", e)
                    _state.value = _state.value.copy(loading = false) // NAS injoignable : on garde ce qu'on avait
                }
            }
        }
    }

    /** Une note vient d'être donnée dans l'application (0 = retirée). */
    fun onRated(song: Song?, songId: String, rating: Int) {
        scope.launch {
            lock.withLock {
                if (rating !in 1..5) {
                    rated.remove(songId)
                } else {
                    val known = song ?: LibraryIndex.songsByIds(listOf(songId)).firstOrNull()
                    val previous = rated[songId]
                    when {
                        known != null -> rated[songId] = RatedSong(songId, rating, artistName(known), known.genre.trim())
                        previous != null -> rated[songId] = previous.copy(rating = rating)
                        else -> return@withLock // titre inconnu de l'index : pris en compte à la prochaine relecture
                    }
                }
                publish(_state.value.loadedAt)
                runCatching { save() }
            }
        }
    }

    /** Construit les signaux : notes > likes > masqués > écoute, un seul par titre. */
    private fun signals(): List<TasteSignal> {
        val out = ArrayList<TasteSignal>()
        rated.values.forEach { out.add(TasteSignal(it.id, it.rating.toDouble(), TasteTuning.EXPLICIT_WEIGHT, it.artistName, it.genreName)) }
        val taken = rated.keys.toMutableSet()
        val liked = Likes.state.value.ids.filter { taken.add(it) }
        val hidden = Hidden.state.value.ids.filter { taken.add(it) }
        val listens = ListenStats.snapshot().filterKeys { it !in taken }
        val infos = LibraryIndex.songsByIds(liked + hidden + listens.keys).associateBy { it.id }
        liked.forEach { id -> infos[id]?.let { out.add(TasteSignal(id, 5.0, TasteTuning.EXPLICIT_WEIGHT, artistName(it), it.genre.trim())) } }
        hidden.forEach { id -> infos[id]?.let { out.add(TasteSignal(id, 1.0, TasteTuning.EXPLICIT_WEIGHT, artistName(it), it.genre.trim())) } }
        listens.forEach { (id, l) ->
            val song = infos[id] ?: return@forEach
            implicitSignal(id, l.completes, l.skips, artistName(song), song.genre.trim())?.let { out.add(it) }
        }
        return out
    }

    private fun publish(at: Long) {
        _state.value = TasteState(TasteProfile(signals()), loading = false, loadedAt = at)
    }

    private fun save() {
        val arr = JSONArray()
        rated.values.forEach { arr.put(JSONArray(listOf(it.id, it.rating, it.artistName, it.genreName))) }
        val json = JSONObject().put("at", _state.value.loadedAt).put("rated", arr)
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        val arr = json.getJSONArray("rated")
        rated = HashMap<String, RatedSong>().also { map ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                map[a.getString(0)] = RatedSong(a.getString(0), a.getInt(1), a.getString(2), a.getString(3))
            }
        }
        publish(json.optLong("at"))
    }
}

/** Titres écoutés en entier et titres passés, par titre. Local à l'appareil, comme l'historique d'écoute. */
data class ListenCounts(val completes: Int = 0, val skips: Int = 0)

object ListenStats {

    private const val FILE = "listen_stats.json"
    private const val MAX_ENTRIES = 5000
    private val lock = Any()
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private lateinit var appContext: Context
    private var cache: MutableMap<String, ListenCounts> = mutableMapOf()

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        synchronized(lock) {
            runCatching {
                val f = appContext.getFileStreamPath(FILE)
                if (f.exists()) {
                    val json = JSONObject(f.readText())
                    json.keys().forEach { k -> json.getJSONArray(k).let { cache[k] = ListenCounts(it.getInt(0), it.getInt(1)) } }
                }
            }
        }
    }

    fun snapshot(): Map<String, ListenCounts> = synchronized(lock) { cache.toMap() }

    /** Reprend les statistiques sauvegardées sur le NAS : le plus grand compte de chaque sorte l'emporte. */
    fun mergeFrom(remote: Map<String, ListenCounts>) {
        synchronized(lock) {
            val merged = NasProfile.mergeListen(cache, remote)
            cache.clear()
            cache.putAll(merged)
            val copy = cache.toMap()
            writer.launch { persist(copy) }
        }
    }

    private fun persist(copy: Map<String, ListenCounts>) {
        runCatching {
            val json = JSONObject()
            copy.forEach { (id, v) -> json.put(id, JSONArray(listOf(v.completes, v.skips))) }
            appContext.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(json.toString().toByteArray()) }
        }
    }

    fun record(songId: String, completed: Boolean) {
        synchronized(lock) {
            val c = cache[songId] ?: ListenCounts()
            cache.remove(songId) // réinséré en dernier : les plus anciens sortent en premier
            cache[songId] = if (completed) c.copy(completes = c.completes + 1) else c.copy(skips = c.skips + 1)
            while (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
            val copy = cache.toMap()
            // Écriture hors du fil appelant (le service de lecture), une à la fois.
            writer.launch { persist(copy) }
        }
        TasteStore.requestRebuild()
        NasProfile.requestPush()
    }
}

/** Mémoire des titres déjà tombés dans un mix ou une radio : le tirage s'en sert pour ne pas repasser sans cesse les mêmes. */
object ExposureStore {

    private const val FILE = "mix_exposure.json"
    private const val MAX_ENTRIES = 6000
    private val lock = Any()
    private var cache: MutableMap<String, ExposureEntry>? = null

    fun snapshot(context: Context): Map<String, ExposureEntry> = synchronized(lock) { load(context).toMap() }

    /** À appeler avec les titres qu'une sélection vient de retenir. */
    fun record(context: Context, ids: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        synchronized(lock) { recordLocked(context, ids, now) }
    }

    private fun recordLocked(context: Context, ids: Collection<String>, now: Long) {
        val map = load(context)
        ids.forEach { id ->
            // On part du compte atténué : un vieux titre qui retombe n'est pas puni comme s'il était tombé hier.
            val current = Exposure.effectiveCount(map[id], now)
            map[id] = ExposureEntry(count = (current + 1).let { Math.round(it).toInt().coerceAtLeast(1) }, lastMs = now)
        }
        if (map.size > MAX_ENTRIES) {
            map.entries.sortedBy { it.value.lastMs }.take(map.size - MAX_ENTRIES).forEach { map.remove(it.key) }
        }
        persist(context, map)
        NasProfile.requestPush()
    }

    /** Reprend l'exposition sauvegardée sur le NAS : pour chaque titre, l'entrée la plus récente l'emporte. */
    fun mergeFrom(context: Context, remote: Map<String, ExposureEntry>) = synchronized(lock) {
        val map = load(context)
        val merged = NasProfile.mergeExposure(map, remote)
        map.clear()
        map.putAll(merged)
        persist(context, map)
    }

    fun clear(context: Context) = synchronized(lock) {
        cache = mutableMapOf()
        context.applicationContext.deleteFile(FILE)
    }

    private fun persist(context: Context, map: Map<String, ExposureEntry>) {
        runCatching {
            val json = JSONObject()
            map.forEach { (id, e) -> json.put(id, JSONArray(listOf(e.count, e.lastMs))) }
            context.applicationContext.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(json.toString().toByteArray()) }
        }
    }

    private fun load(context: Context): MutableMap<String, ExposureEntry> {
        cache?.let { return it }
        val map = HashMap<String, ExposureEntry>()
        runCatching {
            val f = context.applicationContext.getFileStreamPath(FILE)
            if (f.exists()) {
                val json = JSONObject(f.readText())
                json.keys().forEach { k -> json.getJSONArray(k).let { map[k] = ExposureEntry(it.getInt(0), it.getLong(1)) } }
            }
        }
        cache = map
        return map
    }
}

/** Poids final d'un titre dans un tirage : goûts × exposition × écoute récente. */
class SongWeigher(context: Context, recentPlays: Set<String>, private val now: Long = System.currentTimeMillis()) {
    private val profile = TasteStore.profile
    private val exposure = ExposureStore.snapshot(context)
    private val recent = recentPlays

    fun weight(song: Song): Double {
        var w = profile.weight(song) * Exposure.factor(exposure[song.id], now)
        if (song.id in recent) w *= TasteTuning.RECENT_PLAY_FACTOR
        return w
    }
}
