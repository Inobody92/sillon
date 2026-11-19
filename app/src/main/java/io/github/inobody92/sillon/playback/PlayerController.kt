package io.github.inobody92.sillon.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.data.Song
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueueItem(val mediaId: String, val title: String, val artist: String, val artwork: String?)

data class PlayerUi(
    val hasItem: Boolean = false,
    val mediaId: String = "",
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artwork: String? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/** Pont entre l'interface et le service de lecture (via MediaController). */
class PlayerController(context: Context) {

    private val appContext = context.applicationContext
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                val c = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = c
                c.addListener(listener)
                publish(c)
                pending.forEach { it(c) }
                pending.clear()
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun itemsFor(client: SynoClient?, songs: List<Song>, album: Album?): List<MediaItem> {
        val albumArtwork = album?.let { client?.coverUrl(it, withSid = true) }?.let(Uri::parse)
        return songs.map { song ->
            // La pochette téléchargée passe en premier : elle s'affiche aussi sans réseau.
            val artwork = OfflineStore.coverUri(song) ?: albumArtwork ?: client?.songCoverUrl(song.id, withSid = true)?.let(Uri::parse)
            MediaItems.song(song, album, artwork)
        }
    }

    /**
     * Lance la lecture de [songs] à partir de l'index [startIndex].
     * Si [album] est fourni, sa pochette sert pour tous les morceaux ; sinon, celle de chaque morceau.
     */
    fun play(client: SynoClient?, songs: List<Song>, startIndex: Int, album: Album? = null) {
        if (songs.isEmpty()) return
        val items = itemsFor(client, songs, album)
        withController {
            it.setMediaItems(items, startIndex.coerceIn(items.indices), 0L)
            it.prepare()
            it.play()
        }
    }

    /** Insère [songs] juste après le morceau en cours (ou lance la lecture si la file est vide). */
    fun playNext(client: SynoClient?, songs: List<Song>, album: Album? = null) {
        if (songs.isEmpty()) return
        val items = itemsFor(client, songs, album)
        withController {
            if (it.mediaItemCount == 0) {
                it.setMediaItems(items)
                it.prepare()
                it.play()
            } else {
                it.addMediaItems(it.currentMediaItemIndex + 1, items)
            }
        }
    }

    /** Ajoute [songs] en fin de file (ou lance la lecture si la file est vide). */
    fun addToQueue(client: SynoClient?, songs: List<Song>, album: Album? = null) {
        if (songs.isEmpty()) return
        val items = itemsFor(client, songs, album)
        withController {
            if (it.mediaItemCount == 0) {
                it.setMediaItems(items)
                it.prepare()
                it.play()
            } else {
                it.addMediaItems(items)
            }
        }
    }

    /**
     * Démarre une radio : le service fabrique la première série de titres et la prolonge tout seul
     * (voir PlaybackService). On lui envoie seulement l'identifiant de l'humeur.
     */
    fun playRadio(mood: io.github.inobody92.sillon.data.Mood) = withController {
        it.setMediaItem(MediaItem.Builder().setMediaId("radio:${mood.id}").build())
        it.prepare()
        it.play()
    }

    fun skipTo(index: Int) = withController {
        if (index in 0 until it.mediaItemCount) {
            it.seekTo(index, 0L)
            it.play()
        }
    }

    fun removeFromQueue(index: Int) = withController {
        if (index in 0 until it.mediaItemCount) it.removeMediaItem(index)
    }

    fun moveInQueue(from: Int, to: Int) = withController {
        if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to)
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Répétition : aucune, toute la file, puis le morceau en cours. */
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPreviousMediaItem() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun positionMs(): Long = controller?.currentPosition ?: 0L

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending += block
    }

    private fun publish(p: Player) {
        val md = p.mediaMetadata
        _ui.value = PlayerUi(
            hasItem = p.currentMediaItem != null,
            mediaId = p.currentMediaItem?.mediaId.orEmpty(),
            queue = List(p.mediaItemCount) { i ->
                val item = p.getMediaItemAt(i)
                QueueItem(
                    mediaId = item.mediaId,
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                    artwork = item.mediaMetadata.artworkUri?.toString(),
                )
            },
            currentIndex = p.currentMediaItemIndex,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            artwork = md.artworkUri?.toString(),
            isPlaying = p.isPlaying,
            durationMs = p.duration.coerceAtLeast(0L),
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem(),
        )
    }
}
