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
import java.text.Normalizer
import kotlin.math.abs

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
    private var entries: List<Entry> = emptyList()
    @Volatile
    private var albumEntries: List<AlbumEntry> = emptyList()
    @Volatile
    private var artistEntries: List<ArtistEntry> = emptyList()

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

    private fun publish(songs: List<Song>) {
        val built = songs.map { Entry(it) }
        entries = built
        albumEntries = built.filter { it.song.album.isNotBlank() }
            .groupBy { it.song.album to it.song.albumArtist.ifBlank { it.song.artist } }
            .map { (key, list) ->
                val first = list.first().song
                AlbumEntry(
                    Album(name = key.first, albumArtist = first.albumArtist, artist = first.artist, year = list.maxOf { it.song.year }),
                )
            }
        artistEntries = built.filter { it.song.artist.isNotBlank() }
            .groupBy { norm(it.song.artist) }
            .map { (_, list) ->
                // Plusieurs graphies d'un même nom : on garde la plus fréquente pour l'affichage.
                ArtistEntry(list.groupingBy { it.song.artist }.eachCount().maxByOrNull { it.value }!!.key)
            }
        _state.value = IndexState(ready = true, building = false, size = songs.size)
    }

    /** Titres de l'index pour ces identifiants (dans l'ordre donné ; les inconnus sont ignorés). */
    fun songsByIds(ids: List<String>): List<Song> {
        val byId = entries.associateBy({ it.song.id }, { it.song })
        return ids.mapNotNull { byId[it] }
    }

    // --- recherche --------------------------------------------------------------------

    fun search(query: String): SearchResults {
        val q = norm(query)
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return SearchResults()

        val songs = entries.mapNotNull { e ->
            var total = 0.0
            for (t in tokens) {
                val s = e.tokenScore(t)
                if (s == 0.0) return@mapNotNull null // tous les mots doivent être trouvés quelque part
                total += s
            }
            if (e.title.startsWith(q)) total += 2.0
            if (e.title == q) total += 3.0
            e.song to total
        }.sortedWith(compareByDescending<Pair<Song, Double>> { it.second }.thenBy { it.first.title.lowercase() })
            .take(40).map { it.first }

        val albums = albumEntries.mapNotNull { a ->
            var total = 0.0
            for (t in tokens) {
                val s = maxOf(fieldScore(a.album, a.albumWords, t, 3.0), fieldScore(a.artist, a.artistWords, t, 2.0))
                if (s == 0.0) return@mapNotNull null
                total += s
            }
            a.value to total
        }.sortedByDescending { it.second }.take(20).map { it.first }

        val artists = artistEntries.mapNotNull { a ->
            var total = 0.0
            for (t in tokens) {
                val s = fieldScore(a.norm, a.words, t, 3.0)
                if (s == 0.0) return@mapNotNull null
                total += s
            }
            a.value to total
        }.sortedByDescending { it.second }.take(10).map { Artist(it.first) }

        return SearchResults(artists, albums, songs)
    }

    private class Entry(val song: Song) {
        val title = norm(song.title)
        val artist = norm(song.artist)
        val album = norm(song.album)
        val albumArtist = norm(song.albumArtist)
        val genre = norm(song.genre)
        private val titleWords = words(title)
        private val artistWords = words(artist)
        private val albumWords = words(album)
        private val albumArtistWords = words(albumArtist)
        private val genreWords = words(genre)

        /** Meilleur score du mot [t] dans l'un des champs ; 0 s'il n'apparaît nulle part. */
        fun tokenScore(t: String): Double = maxOf(
            fieldScore(title, titleWords, t, 3.0),
            fieldScore(artist, artistWords, t, 2.5),
            fieldScore(album, albumWords, t, 2.0),
            fieldScore(albumArtist, albumArtistWords, t, 1.5),
            fieldScore(genre, genreWords, t, 1.0),
        )
    }

    private class AlbumEntry(val value: Album) {
        val album = norm(value.name)
        val artist = norm(value.albumArtist.ifBlank { value.artist })
        val albumWords = words(album)
        val artistWords = words(artist)
    }

    private class ArtistEntry(val value: String) {
        val norm = norm(value)
        val words = words(norm)
    }

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

    // --- texte ------------------------------------------------------------------------

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    /** Minuscules, sans accents ni ponctuation : « Mylène Farmer » et « mylene  farmer » deviennent identiques. */
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "").replace(NON_ALNUM, " ").trim()

    private fun words(s: String): List<String> = s.split(' ').filter { it.isNotEmpty() }

    /**
     * Score du mot [t] dans un champ : présence directe (mieux placée en début de mot ou si c'est tout le champ),
     * sinon proche d'un mot du champ à une ou deux fautes près (à partir de 4 lettres).
     */
    private fun fieldScore(text: String, words: List<String>, t: String, weight: Double): Double {
        if (text.isEmpty()) return 0.0
        val idx = text.indexOf(t)
        if (idx >= 0) {
            val wordStart = idx == 0 || text[idx - 1] == ' '
            if (t.length == 1 && !wordStart) return 0.0 // une lettre seule ne compte qu'en début de mot
            var s = weight
            if (wordStart) s += 1.0
            if (text == t) s += 1.0
            return s
        }
        if (t.length >= 4) {
            val max = if (t.length >= 8) 2 else 1
            for (w in words) {
                if (abs(w.length - t.length) <= max && distance(w, t, max) <= max) return weight * 0.6
            }
        }
        return 0.0
    }

    /** Distance d'édition (insertion, suppression, remplacement, échange de deux lettres), abandonnée au-delà de [max]. */
    private fun distance(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        val n = a.length
        val m = b.length
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }
}
