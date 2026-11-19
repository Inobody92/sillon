package io.github.inobody92.sillon.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.DailyMix
import io.github.inobody92.sillon.data.Mood
import io.github.inobody92.sillon.data.MoodStore
import io.github.inobody92.sillon.data.PlayHistory
import io.github.inobody92.sillon.data.SessionHolder
import io.github.inobody92.sillon.data.Song
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Arborescence de la bibliothèque exposée à la voiture (Android Auto) et utilisée par la radio.
 *
 *   root
 *   ├─ mix:daily              (lisible) Mix du jour
 *   ├─ radio                  → radio:<humeur> (lisibles)
 *   ├─ albums                 → album:<artiste>|<nom> → titres
 *   ├─ artists                → artist:<nom> → albums
 *   └─ playlists              → playlist:<id> → titres
 */
class LibraryTree(private val context: Context) {

    /**
     * Dossier d'où vient chaque titre affiché. Android Auto renvoie seulement l'identifiant du titre choisi
     * (les métadonnées ne reviennent pas) : c'est ce qui permet de continuer l'album ou la playlist.
     */
    private val parents = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun parentOf(songId: String): String? = parents[songId]

    suspend fun client(): SynoClient? =
        SessionHolder.client ?: withContext(Dispatchers.IO) { SessionHolder.restore(context) }

    fun rootItem(): MediaItem = MediaItems.folder(ROOT, "Sillon")

    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> rootItem()
        mediaId == MIX -> mixItem()
        mediaId == RADIO -> MediaItems.folder(RADIO, "Radio", "Mix du jour et humeurs")
        mediaId.startsWith("radio:") -> Mood.entries.firstOrNull { it.id == mediaId.removePrefix("radio:") }?.let(::moodItem)
        mediaId == ALBUMS -> MediaItems.folder(ALBUMS, "Albums")
        mediaId == ARTISTS -> MediaItems.folder(ARTISTS, "Artistes")
        mediaId == PLAYLISTS -> MediaItems.folder(PLAYLISTS, "Playlists")
        mediaId.startsWith("album:") -> decodeAlbum(mediaId)?.let { albumItem(it) }
        else -> client()?.songsByIds(listOf(mediaId))?.firstOrNull()?.let { songItem(it, null) }
    }

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val c = client() ?: return emptyList()
        val size = pageSize.coerceIn(1, 100)
        return when {
            parentId == ROOT -> listOf(
                MediaItems.folder(RADIO, "Radio", "Mix du jour et humeurs"),
                MediaItems.folder(ALBUMS, "Albums"),
                MediaItems.folder(ARTISTS, "Artistes"),
                MediaItems.folder(PLAYLISTS, "Playlists"),
            )
            parentId == RADIO -> listOf(mixItem()) + Mood.entries.map(::moodItem)
            parentId == ALBUMS -> c.albums(page * size, size).map { albumItem(it) }
            parentId == ARTISTS -> c.artists(page * size, size).map {
                MediaItems.folder("artist:${Uri.encode(it.name)}", it.name, type = MediaMetadata.MEDIA_TYPE_ARTIST)
            }
            parentId == PLAYLISTS -> c.playlists().drop(page * size).take(size).map {
                MediaItems.folder("playlist:${Uri.encode(it.id)}", it.name, type = MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
            parentId.startsWith("artist:") ->
                c.albums(page * size, size, artist = Uri.decode(parentId.removePrefix("artist:"))).map { albumItem(it) }
            parentId.startsWith("album:") -> {
                val album = decodeAlbum(parentId) ?: return emptyList()
                c.songs(album).also { songs -> songs.forEach { parents[it.id] = parentId } }
                    .map { songItem(it, album, parentId) }
            }
            parentId.startsWith("playlist:") ->
                c.playlistSongs(Uri.decode(parentId.removePrefix("playlist:")))
                    .also { songs -> songs.forEach { parents[it.id] = parentId } }
                    .map { songItem(it, null, parentId) }
            else -> emptyList()
        }
    }

    /** Transforme un identifiant « lisible » en titres prêts à jouer ; null si ce n'est pas un conteneur. */
    suspend fun expand(mediaId: String): List<MediaItem>? {
        val c = client() ?: return null
        return when {
            mediaId == MIX -> DailyMix.get(context, c).map { songItem(it, null) }
            mediaId.startsWith("radio:") -> radioBatch(mediaId.removePrefix("radio:"), RADIO_START)
            mediaId.startsWith("album:") -> {
                val album = decodeAlbum(mediaId) ?: return null
                c.songs(album).map { songItem(it, album, mediaId) }
            }
            mediaId.startsWith("playlist:") ->
                c.playlistSongs(Uri.decode(mediaId.removePrefix("playlist:"))).map { songItem(it, null, mediaId) }
            mediaId.startsWith("artist:") ->
                c.songsByArtist(Uri.decode(mediaId.removePrefix("artist:"))).map { songItem(it, null, mediaId) }
            else -> null
        }
    }

    /** Une série de titres pour la radio [moodId], en évitant [exclude] et ce qu'on a écouté récemment. */
    suspend fun radioBatch(moodId: String, count: Int, exclude: Set<String> = emptySet()): List<MediaItem> {
        val c = client() ?: return emptyList()
        val mood = Mood.entries.firstOrNull { it.id == moodId } ?: return emptyList()
        val library = c.genres().map { it.name }
        val genres = MoodStore.genres(context, mood, library)
        val skip = PlayHistory.recent(context, RADIO_RECENT_DAYS) + exclude
        // Aucun genre correspondant : la radio puise dans toute la bibliothèque plutôt que de rester muette.
        val pool = c.randomSongs(count * 2, genres.ifEmpty { null }, Random(System.nanoTime()))
        val chosen = (pool.filter { it.id !in skip } + pool.filter { it.id in skip && it.id !in exclude }).take(count)
        return chosen.map { songItem(it, null, radio = moodId) }
    }

    /** Recherche pour la voix et le champ de recherche de la voiture. */
    suspend fun search(query: String): List<MediaItem> {
        val c = client() ?: return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val songs = runCatching { c.searchSongs(q, 20) }.getOrDefault(emptyList())
        val albums = runCatching { c.albums(0, 10, filter = q) }.getOrDefault(emptyList())
        val artists = runCatching { c.artists(0, 10, filter = q) }.getOrDefault(emptyList())
        return artists.map { MediaItems.folder("artist:${Uri.encode(it.name)}", it.name, type = MediaMetadata.MEDIA_TYPE_ARTIST) } +
            albums.map { albumItem(it) } +
            songs.map { songItem(it, null) }
    }

    /**
     * Pour une commande vocale (« mets du Indochine ») : ce qu'il faut jouer pour [query].
     * On préfère un artiste, puis un titre, puis un album.
     */
    suspend fun playbackForQuery(query: String): List<MediaItem> {
        val c = client() ?: return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return DailyMix.get(context, c).map { songItem(it, null) }
        c.artists(0, 5, filter = q).firstOrNull()?.let { artist ->
            val songs = c.songsByArtist(artist.name)
            if (songs.isNotEmpty()) return songs.shuffled().map { songItem(it, null) }
        }
        c.searchSongs(q, 20).takeIf { it.isNotEmpty() }?.let { return it.map { s -> songItem(s, null) } }
        c.albums(0, 1, filter = q).firstOrNull()?.let { album ->
            return c.songs(album).map { songItem(it, album) }
        }
        return emptyList()
    }

    // --- éléments ---------------------------------------------------------------------

    private suspend fun songItem(song: Song, album: Album?, parentId: String? = null, radio: String? = null): MediaItem {
        val c = client()
        val artwork = (album?.let { c?.coverUrl(it, withSid = true) } ?: c?.songCoverUrl(song.id, withSid = true))?.let(Uri::parse)
        return MediaItems.song(song, album, artwork, parentId, radio)
    }

    private suspend fun albumItem(album: Album): MediaItem {
        val artwork = client()?.coverUrl(album, withSid = true)?.let(Uri::parse)
        return MediaItems.folder(
            id = encodeAlbum(album),
            title = album.name,
            subtitle = album.displayArtist,
            artwork = artwork,
            type = MediaMetadata.MEDIA_TYPE_ALBUM,
        )
    }

    private fun mixItem(): MediaItem =
        MediaItems.folder(MIX, "Mix du jour", "Une sélection pour aujourd'hui", playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST)

    private fun moodItem(mood: Mood): MediaItem =
        MediaItems.folder("radio:${mood.id}", mood.label, "Radio sans fin", playable = true, type = MediaMetadata.MEDIA_TYPE_RADIO_STATION)

    private fun encodeAlbum(a: Album) = "album:${Uri.encode(a.albumArtist)}|${Uri.encode(a.name)}|${Uri.encode(a.artist)}|${a.year}"

    private fun decodeAlbum(id: String): Album? {
        val parts = id.removePrefix("album:").split('|')
        if (parts.size != 4) return null
        return Album(
            name = Uri.decode(parts[1]),
            albumArtist = Uri.decode(parts[0]),
            artist = Uri.decode(parts[2]),
            year = parts[3].toIntOrNull() ?: 0,
        )
    }

    companion object {
        const val ROOT = "root"
        const val MIX = "mix:daily"
        const val RADIO = "radio"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"

        const val RADIO_START = 15
        const val RADIO_REFILL = 12
        private const val RADIO_RECENT_DAYS = 3
    }
}
