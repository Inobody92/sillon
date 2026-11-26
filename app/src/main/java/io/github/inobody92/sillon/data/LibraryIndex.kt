package io.github.inobody92.sillon.data

import android.content.Context
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class IndexState(val ready: Boolean = false, val building: Boolean = false, val size: Int = 0)

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList(),
) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

/**
 * Index local de la bibliothèque (titre, artiste, album, genre de chaque morceau), gardé sur le téléphone.
 * Le NAS ne sait chercher qu'un texte exact dans un seul champ ; ici la recherche est instantanée et souple :
 * insensible aux accents et à la casse, tolérante aux fautes de frappe, et chaque mot peut tomber dans un champ
 * différent (« rihanna james joint »).
 */
object LibraryIndex {

    private const val FILE = "library_index.json"
    private const val PAGE = 500
    private const val MAX_AGE_MS = 14L * 24 * 3600 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buildLock = Mutex()
    private lateinit var file: File

    @Volatile
    private var engine: SearchEngine = SearchEngine(emptyList())

    private val _state = MutableStateFlow(IndexState())
    val state: StateFlow<IndexState> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value.ready

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, FILE)
        scope.launch { loadFromDisk() }
    }

    /** Vérifie que l'index est à jour (même nombre de titres, pas trop ancien) et le reconstruit sinon, en arrière-plan. */
    fun ensure(client: SynoClient) {
        scope.launch {
            buildLock.withLock {
                try {
                    val total = client.freshSongTotal()
                    val age = System.currentTimeMillis() - (file.takeIf { it.exists() }?.lastModified() ?: 0L)
                    if (_state.value.ready && _state.value.size == total && age < MAX_AGE_MS) return@withLock
                    build(client, total)
                } catch (_: Exception) {
                    _state.value = _state.value.copy(building = false) // NAS injoignable : on garde l'index existant
                }
            }
        }
    }

    private suspend fun build(client: SynoClient, total: Int) {
        _state.value = _state.value.copy(building = true)
        val songs = coroutineScope {
            val gate = Semaphore(3)
            (0 until total step PAGE).map { offset ->
                async { gate.withPermit { client.libraryPage(offset, PAGE) } }
            }.flatMap { it.await() }
        }.distinctBy { it.id }
        publish(songs)
        save(songs)
    }

    private fun publish(all: List<Song>) {
        // Rien de ce qui est dans le dossier d'attente du bot : ni recherche, ni mix, ni radio, ni découvertes.
        val songs = all.filterNot { isTempDownloadAlbum(it.album) }
        engine = SearchEngine(songs)
        _state.value = IndexState(ready = true, building = false, size = songs.size)
    }

    /** Toute la bibliothèque connue de l'index (vide tant qu'il n'est pas prêt). */
    fun allSongs(): List<Song> = engine.allSongs()

    /** Titres de l'index pour ces identifiants (dans l'ordre donné ; les inconnus sont ignorés). */
    fun songsByIds(ids: List<String>): List<Song> = engine.songsByIds(ids)

    fun search(query: String): SearchResults = engine.search(query)

    // --- persistance ------------------------------------------------------------------

    private suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        buildLock.withLock {
            try {
                if (!file.exists()) return@withLock
                val arr = JSONArray(file.readText())
                val songs = List(arr.length()) { i ->
                    val a = arr.getJSONArray(i)
                    Song(
                        id = a.getString(0), title = a.getString(1), artist = a.getString(2), album = a.getString(3),
                        albumArtist = a.getString(4), track = a.getInt(5), disc = a.getInt(6), durationSec = a.getInt(7),
                        ext = a.getString(8), genre = a.getString(9), year = a.getInt(10),
                    )
                }
                if (songs.isNotEmpty()) publish(songs)
            } catch (_: Exception) {
                // Index illisible : il sera reconstruit à la prochaine connexion.
            }
        }
    }

    private fun save(songs: List<Song>) {
        try {
            val arr = JSONArray()
            songs.forEach { s ->
                arr.put(
                    JSONArray(
                        listOf(s.id, s.title, s.artist, s.album, s.albumArtist, s.track, s.disc, s.durationSec, s.ext, s.genre, s.year),
                    ),
                )
            }
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }
}
