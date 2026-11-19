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
import io.github.inobody92.sillon.data.Song
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUi(
    val hasItem: Boolean = false,
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

    /**
     * Lance la lecture de [songs] à partir de l'index [startIndex].
     * Si [album] est fourni, sa pochette sert pour tous les morceaux ; sinon, celle de chaque morceau.
     */
    fun play(client: SynoClient, songs: List<Song>, startIndex: Int, album: Album? = null) {
        if (songs.isEmpty()) return
        val albumArtwork = album?.let { client.coverUrl(it, withSid = true) }?.let(Uri::parse)
        val items = songs.map { song ->
            val artwork = albumArtwork ?: client.songCoverUrl(song.id, withSid = true)?.let(Uri::parse)
            toMediaItem(song, album, artwork)
        }
        withController {
            it.setMediaItems(items, startIndex.coerceIn(items.indices), 0L)
            it.prepare()
            it.play()
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

    private fun toMediaItem(song: Song, album: Album?, artwork: Uri?): MediaItem {
        val uri = Uri.Builder()
            .scheme(PlaybackService.SCHEME)
            .authority("song")
            .appendPath("${song.id}.${song.ext}")
            .appendQueryParameter("id", song.id)
            .build()
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist.ifBlank { album?.displayArtist.orEmpty() })
            .setAlbumTitle(song.album.ifBlank { album?.name.orEmpty() })
            .setAlbumArtist(song.albumArtist.ifBlank { album?.displayArtist.orEmpty() })
            .setTrackNumber(song.track.takeIf { it > 0 })
            .setDurationMs(song.durationSec.takeIf { it > 0 }?.let { it * 1000L })
            .setArtworkUri(artwork)
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMimeType(mimeFor(song.ext))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun mimeFor(ext: String): String? = when (ext) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        else -> null
    }
}
