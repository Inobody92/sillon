package io.github.inobody92.sillon.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Notes de la bibliothèque et profil de goûts, gardés sur le téléphone. Les notes viennent du NAS (relues au plus
 * une fois toutes les [REFRESH_MS]) ; une note donnée dans l'application est prise en compte tout de suite.
 */
object TasteStore {

    private const val FILE = "taste_ratings.json"
    private const val PAGE = 500
    private const val REFRESH_MS = 6L * 3600_000L
    private const val TAG = "SillonTaste"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var file: File
    private var rated: MutableMap<String, RatedSong> = mutableMapOf()

    private val _state = MutableStateFlow(TasteState())
    val state: StateFlow<TasteState> = _state.asStateFlow()

    val profile: TasteProfile get() = _state.value.profile

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, FILE)
        runCatching { load() }.onFailure { Log.w(TAG, "lecture impossible", it) }
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

    private fun publish(at: Long) {
        _state.value = TasteState(TasteProfile(rated.values.toList()), loading = false, loadedAt = at)
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
        runCatching {
            val json = JSONObject()
            map.forEach { (id, e) -> json.put(id, JSONArray(listOf(e.count, e.lastMs))) }
            context.applicationContext.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(json.toString().toByteArray()) }
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        cache = mutableMapOf()
        context.applicationContext.deleteFile(FILE)
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
