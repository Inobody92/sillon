package io.github.inobody92.sillon.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HiddenState(val playlistId: String? = null, val ids: Set<String> = emptySet(), val revision: Int = 0)

/**
 * « Titres masqués » : les titres qu'on ne veut plus jamais entendre (mix, radios, aléatoire, files, voiture).
 * Comme les likes, c'est une vraie playlist personnelle du NAS : propre à chaque compte (la bibliothèque est
 * commune, pas l'écoute), et retrouvée sur tous les appareils. L'état est aussi gardé sur le téléphone pour
 * fonctionner sans réseau et dans le service de lecture.
 */
object Hidden {

    const val NAME = "Titres masqués"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var file: File

    private val _state = MutableStateFlow(HiddenState())
    val state: StateFlow<HiddenState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, "hidden.json")
        HiddenRules.init(context)
        load()
    }

    /** Vrai si ce titre précis a été masqué (la liste « Titres masqués »). Les règles d'artiste et d'album : [isHiddenSong]. */
    fun isHidden(songId: String) = songId in _state.value.ids

    /** Vrai si le titre est masqué, par lui-même ou par une règle sur son artiste ou son album. */
    fun isHiddenSong(song: Song) = song.id in _state.value.ids || HiddenRules.matches(song)

    /** Titres de [songs] qui ne sont pas masqués. */
    fun visible(songs: List<Song>): List<Song> {
        val hidden = _state.value.ids
        return if (hidden.isEmpty() && HiddenRules.active.isEmpty()) songs else songs.filter { !isHiddenSong(it) }
    }

    /** Masque ou démasque. Instantané à l'écran ; le NAS suit, et on revient en arrière s'il refuse. */
    fun toggle(client: SynoClient, songId: String) {
        scope.launch { mutex.withLock { doToggle(client, songId) } }
    }

    fun hide(client: SynoClient, songId: String) {
        if (!isHidden(songId)) toggle(client, songId)
    }

    fun unhide(client: SynoClient, songId: String) {
        if (isHidden(songId)) toggle(client, songId)
    }

    private suspend fun doToggle(client: SynoClient, songId: String) {
        val before = _state.value
        val hiding = songId !in before.ids
        _state.value = before.copy(ids = if (hiding) before.ids + songId else before.ids - songId)
        save()
        try {
            if (hiding) add(client, songId) else remove(client, songId)
            _state.value = _state.value.copy(revision = _state.value.revision + 1)
        } catch (e: Exception) {
            _state.value = before
            save()
            _errors.tryEmit(e.message ?: "Impossible de mettre à jour « $NAME »")
        }
    }

    private suspend fun add(client: SynoClient, songId: String) {
        val known = _state.value.playlistId ?: findPlaylist(client)?.id
        if (known == null) {
            val id = client.createPlaylist(NAME, listOf(songId))
            _state.value = _state.value.copy(playlistId = id)
            save()
            return
        }
        // Le cache local peut être en retard sur le NAS : on vérifie pour ne jamais créer de doublon.
        if (client.playlistSongs(known).none { it.id == songId }) client.addToPlaylist(known, listOf(songId))
        _state.value = _state.value.copy(playlistId = known)
        save()
    }

    private suspend fun remove(client: SynoClient, songId: String) {
        val id = _state.value.playlistId ?: findPlaylist(client)?.id ?: return
        client.playlistSongs(id).map { it.id }.withIndex().filter { it.value == songId }.map { it.index }
            .sortedDescending().forEach { client.removeFromPlaylist(id, it) }
    }

    /** Aligne l'état local sur le NAS (à chaque connexion). */
    suspend fun sync(client: SynoClient) = mutex.withLock {
        try {
            val known = _state.value.playlistId
            val playlist = known?.let { id -> client.playlists().firstOrNull { it.id == id } } ?: findPlaylist(client)
            if (playlist == null) {
                _state.value = HiddenState(revision = _state.value.revision + 1)
            } else {
                val ids = client.playlistSongs(playlist.id).map { it.id }.distinct().toCollection(LinkedHashSet())
                _state.value = HiddenState(playlist.id, ids, _state.value.revision + 1)
            }
            save()
        } catch (_: Exception) {
            // NAS injoignable : on garde l'état mémorisé.
        }
    }

    private suspend fun findPlaylist(client: SynoClient): Playlist? =
        client.playlists().firstOrNull { it.name == NAME && !it.isShared }

    private fun save() {
        try {
            val s = _state.value
            file.writeText(JSONObject().put("playlistId", s.playlistId ?: JSONObject.NULL).put("ids", JSONArray(s.ids.toList())).toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("ids")
            val ids = if (arr == null) emptySet() else List(arr.length()) { arr.getString(it) }.toCollection(LinkedHashSet())
            _state.value = HiddenState(json.optString("playlistId").takeIf { !json.isNull("playlistId") && it.isNotBlank() }, ids)
        } catch (_: Exception) {
        }
    }
}
