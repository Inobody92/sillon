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

data class LikesState(
    val playlistId: String? = null,
    val ids: Set<String> = emptySet(),
    /** Incrémenté quand une opération sur le NAS est terminée : les écrans qui affichent la playlist se relisent alors. */
    val revision: Int = 0,
)

/**
 * « Titres likés » : une vraie playlist du NAS (donc visible partout, y compris dans la voiture), créée au premier
 * like. Un titre n'y figure qu'une seule fois. L'état est aussi mémorisé sur l'appareil : les cœurs s'affichent
 * tout de suite au démarrage et restent corrects sans réseau ; la synchronisation avec le NAS se fait ensuite.
 */
object Likes {

    const val NAME = "Titres likés"

    // Les écritures sur le NAS ne doivent pas être annulées si l'écran qui les a lancées se ferme.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var file: File

    private val _state = MutableStateFlow(LikesState())
    val state: StateFlow<LikesState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, "likes.json")
        load()
    }

    fun isLiked(songId: String) = songId in _state.value.ids

    /** Like ou retire le like. Instantané à l'écran ; le NAS suit, et on revient en arrière s'il refuse. */
    fun toggle(client: SynoClient, songId: String) {
        scope.launch { mutex.withLock { doToggle(client, songId) } }
    }

    private suspend fun doToggle(client: SynoClient, songId: String) {
        val before = _state.value
        val liking = songId !in before.ids
        _state.value = before.copy(ids = if (liking) before.ids + songId else before.ids - songId)
        save()
        try {
            if (liking) like(client, songId) else unlike(client, songId)
            _state.value = _state.value.copy(revision = _state.value.revision + 1)
        } catch (e: Exception) {
            _state.value = before
            save()
            _errors.tryEmit(e.message ?: "Impossible de mettre à jour « $NAME »")
        }
    }

    private suspend fun like(client: SynoClient, songId: String) {
        val known = _state.value.playlistId ?: findPlaylist(client)?.id
        if (known == null) {
            val id = client.createPlaylist(NAME, listOf(songId))
            _state.value = _state.value.copy(playlistId = id)
            save()
            return
        }
        // Le cache local peut être en retard sur le NAS : on vérifie pour ne jamais créer de doublon.
        val onServer = client.playlistSongs(known).any { it.id == songId }
        if (!onServer) client.addToPlaylist(known, listOf(songId))
        _state.value = _state.value.copy(playlistId = known)
        save()
    }

    private suspend fun unlike(client: SynoClient, songId: String) {
        val id = _state.value.playlistId ?: findPlaylist(client)?.id ?: return
        val onServer = client.playlistSongs(id).map { it.id }
        // On retire toutes les occurrences, de la dernière à la première pour ne pas décaler les positions.
        onServer.withIndex().filter { it.value == songId }.map { it.index }.sortedDescending()
            .forEach { client.removeFromPlaylist(id, it) }
    }

    /**
     * Aligne l'état local sur le NAS (à chaque connexion) et élimine les doublons éventuels, par exemple si un titre
     * a été ajouté à la main deux fois depuis une autre application.
     */
    suspend fun sync(client: SynoClient) = mutex.withLock {
        try {
            val known = _state.value.playlistId
            val playlist = known?.let { id -> client.playlists().firstOrNull { it.id == id } } ?: findPlaylist(client)
            if (playlist == null) {
                _state.value = LikesState()
                save()
                return@withLock
            }
            val onServer = client.playlistSongs(playlist.id).map { it.id }
            val seen = HashSet<String>()
            val duplicates = onServer.withIndex().filter { !seen.add(it.value) }.map { it.index }
            if (duplicates.isNotEmpty()) android.util.Log.i("Likes", "${duplicates.size} doublon(s) retiré(s) de « $NAME »")
            duplicates.sortedDescending().forEach { client.removeFromPlaylist(playlist.id, it) }
            _state.value = LikesState(
                playlist.id,
                onServer.distinct().toCollection(LinkedHashSet()),
                _state.value.revision + 1,
            )
            save()
        } catch (_: Exception) {
            // NAS injoignable : on garde l'état mémorisé.
        }
    }

    /** À appeler quand la playlist a été modifiée par ailleurs (ajout manuel, retrait, suppression). */
    fun onPlaylistChanged(client: SynoClient, playlistId: String) {
        if (playlistId == _state.value.playlistId) scope.launch { sync(client) }
    }

    private suspend fun findPlaylist(client: SynoClient): Playlist? =
        client.playlists().firstOrNull { it.name == NAME && !it.isShared }

    private fun save() {
        try {
            val s = _state.value
            file.writeText(
                JSONObject()
                    .put("playlistId", s.playlistId ?: JSONObject.NULL)
                    .put("ids", JSONArray(s.ids.toList()))
                    .toString(),
            )
        } catch (_: Exception) {
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("ids")
            val ids = if (arr == null) emptySet() else List(arr.length()) { arr.getString(it) }.toCollection(LinkedHashSet())
            _state.value = LikesState(json.optString("playlistId").takeIf { !json.isNull("playlistId") && it.isNotBlank() }, ids)
        } catch (_: Exception) {
        }
    }
}
