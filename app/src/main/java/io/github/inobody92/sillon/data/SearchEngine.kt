package io.github.inobody92.sillon.data

import java.text.Normalizer
import kotlin.math.abs

/**
 * Moteur de recherche en mémoire sur une liste de titres : accents et casse ignorés, fautes de frappe tolérées,
 * plusieurs mots dans n'importe quel ordre répartis entre titre, artiste, album et genre. Aucune dépendance à
 * Android : il se teste sur un simple ordinateur.
 */
class SearchEngine(songs: List<Song>) {

    private val entries: List<Entry> = songs.map { Entry(it) }

    private val albumEntries: List<AlbumEntry> = entries.filter { it.song.album.isNotBlank() }
        .groupBy { it.song.album to it.song.albumArtist.ifBlank { it.song.artist } }
        .map { (key, list) ->
            val first = list.first().song
            AlbumEntry(Album(name = key.first, albumArtist = first.albumArtist, artist = first.artist, year = list.maxOf { it.song.year }))
        }

    private val artistEntries: List<ArtistEntry> = entries.filter { it.song.artist.isNotBlank() }
        .groupBy { norm(it.song.artist) }
        .map { (_, list) ->
            // Plusieurs graphies d'un même nom : on garde la plus fréquente pour l'affichage.
            ArtistEntry(list.groupingBy { it.song.artist }.eachCount().maxByOrNull { it.value }!!.key)
        }

    val size: Int get() = entries.size

    /** Titres de l'index pour ces identifiants (dans l'ordre donné ; les inconnus sont ignorés). */
    fun songsByIds(ids: List<String>): List<Song> {
        val byId = entries.associateBy({ it.song.id }, { it.song })
        return ids.mapNotNull { byId[it] }
    }


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


    companion object {
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    /** Minuscules, sans accents ni ponctuation : « Mylène Farmer » et « mylene  farmer » deviennent identiques. */
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "").replace(NON_ALNUM, " ").trim()

    internal fun words(s: String): List<String> = s.split(' ').filter { it.isNotEmpty() }

    /**
     * Score du mot [t] dans un champ : présence directe (mieux placée en début de mot ou si c'est tout le champ),
     * sinon proche d'un mot du champ à une ou deux fautes près (à partir de 4 lettres).
     */
    internal fun fieldScore(text: String, words: List<String>, t: String, weight: Double): Double {
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
    internal fun distance(a: String, b: String, max: Int): Int {
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
}
