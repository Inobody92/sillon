package io.github.inobody92.sillon.playback

import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.inobody92.sillon.data.AppSettings
import io.github.inobody92.sillon.data.PlayHistory
import io.github.inobody92.sillon.data.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.IOException

/**
 * Lecture en arrière-plan, et point d'entrée d'Android Auto (bibliothèque navigable + recherche vocale).
 *
 * Les éléments de file ont une adresse interne `sillon://song/<id>.<ext>?id=<id>` ; elle est transformée en vraie
 * URL de streaming au moment de la lecture, avec le sid courant, pour ne jamais garder une URL périmée.
 *
 * Radio : les titres générés portent le marqueur [MediaItems.EXTRA_RADIO] ; tant que le titre en cours en est un,
 * le service prolonge la file avant qu'elle ne soit vide. Dès que l'utilisateur lance autre chose, la radio s'arrête.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var tree: LibraryTree

    /** Morceau pour lequel on a déjà tenté une reconnexion (évite les boucles). */
    private var retriedMediaId: String? = null
    private var refillingRadio = false

    private fun activeClient() = SessionHolder.client ?: SessionHolder.restore(applicationContext)

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(applicationContext)
        tree = LibraryTree(applicationContext)

        // Réutilise le client HTTP de la session (certificat auto-signé compris).
        val callFactory = Call.Factory { request ->
            val client = activeClient() ?: throw IOException("Pas de session Sillon active")
            client.http.newCall(request)
        }
        val resolver = ResolvingDataSource.Resolver { spec: DataSpec ->
            if (spec.uri.scheme != MediaItems.SCHEME) {
                spec
            } else {
                val client = activeClient() ?: throw IOException("Pas de session Sillon active")
                val id = spec.uri.getQueryParameter("id") ?: throw IOException("Identifiant de morceau manquant")
                val ext = spec.uri.lastPathSegment.orEmpty().substringAfterLast('.', "")
                val url = client.streamUrl(id, ext, AppSettings.currentQuality(applicationContext))
                    ?: throw IOException("API de streaming absente du NAS")
                spec.withUri(Uri.parse(url))
            }
        }
        val dataSourceFactory = ResolvingDataSource.Factory(OkHttpDataSource.Factory(callFactory), resolver)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                retriedMediaId = null
                // Historique d'écoute (local) : alimente le mix du jour et la radio.
                mediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { PlayHistory.record(applicationContext, it) }
                maybeRefillRadio(player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                maybeRefillRadio(player)
            }

            // Session NAS expirée en cours de route : on se reconnecte une fois puis on reprend.
            override fun onPlayerError(error: PlaybackException) {
                val mediaId = player.currentMediaItem?.mediaId ?: return
                if (retriedMediaId == mediaId) return
                retriedMediaId = mediaId
                val client = SessionHolder.client ?: return
                scope.launch {
                    val ok = runCatching { client.refreshSession() }.isSuccess
                    if (ok) player.prepare()
                }
            }
        })

        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    /** Radio sans fin : ajoute des titres quand il en reste moins de cinq après celui en cours. */
    private fun maybeRefillRadio(player: Player) {
        val mood = player.currentMediaItem?.mediaMetadata?.extras?.getString(MediaItems.EXTRA_RADIO) ?: return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining >= RADIO_LOW_WATER || refillingRadio) return
        refillingRadio = true
        scope.launch {
            try {
                val inQueue = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }.toSet()
                val more = tree.radioBatch(mood, LibraryTree.RADIO_REFILL, exclude = inQueue)
                // La radio n'a de sens que si l'utilisateur n'a pas lancé autre chose entre-temps.
                val stillRadio = player.currentMediaItem?.mediaMetadata?.extras?.getString(MediaItems.EXTRA_RADIO) == mood
                if (stillRadio && more.isNotEmpty()) player.addMediaItems(more)
            } catch (_: Exception) {
                // Réseau momentanément indisponible : on réessaiera au prochain changement de titre.
            } finally {
                refillingRadio = false
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(tree.rootItem(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            runCatching { tree.item(mediaId) }.getOrNull()
                ?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                LibraryResult.ofItemList(ImmutableList.copyOf(tree.children(parentId, page, pageSize)), params)
            } catch (_: Exception) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = runCatching { tree.search(query).size }.getOrDefault(0)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                val all = tree.search(query)
                LibraryResult.ofItemList(ImmutableList.copyOf(all.drop(page * pageSize).take(pageSize)), params)
            } catch (_: Exception) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { resolve(mediaItems).toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()
            val parent = single?.let { it.mediaMetadata.extras?.getString(MediaItems.EXTRA_PARENT) ?: tree.parentOf(it.mediaId) }
            if (single != null && parent != null) {
                // Un titre choisi dans un album ou une playlist (depuis la voiture) : on joue tout le contenu à partir de lui.
                val siblings = runCatching { tree.expand(parent) }.getOrNull().orEmpty()
                val index = siblings.indexOfFirst { it.mediaId == single.mediaId }
                if (index >= 0) {
                    return@future MediaSession.MediaItemsWithStartPosition(siblings, index, startPositionMs)
                }
            }
            val resolved = resolve(mediaItems)
            val start = if (resolved.size == mediaItems.size) startIndex else 0
            MediaSession.MediaItemsWithStartPosition(
                resolved,
                start.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
                startPositionMs,
            )
        }
    }

    /** Remplace les éléments « sans adresse » (identifiants de conteneurs, requêtes vocales) par de vrais titres. */
    private suspend fun resolve(items: List<MediaItem>): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        for (item in items) {
            if (item.localConfiguration != null) {
                out += item
                continue
            }
            val query = item.requestMetadata.searchQuery
            val expanded = try {
                if (query != null) {
                    tree.playbackForQuery(query)
                } else {
                    tree.expand(item.mediaId) ?: tree.item(item.mediaId)?.let { listOf(it) }
                }
            } catch (_: Exception) {
                null
            }
            if (expanded != null) out += expanded
        }
        return out
    }

    private companion object {
        const val RADIO_LOW_WATER = 5
    }
}
