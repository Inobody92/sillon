package io.github.inobody92.sillon.playback

import android.content.Intent
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.inobody92.sillon.data.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.IOException

/**
 * Lecture en arrière-plan. Les éléments de file ont une adresse interne `sillon://song/<id>.<ext>?id=<id>` ;
 * elle est transformée en vraie URL de streaming au moment de la lecture, avec le sid courant,
 * pour ne jamais garder une URL périmée dans la file.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Morceau pour lequel on a déjà tenté une reconnexion (évite les boucles). */
    private var retriedMediaId: String? = null

    private fun activeClient() = SessionHolder.client ?: SessionHolder.restore(applicationContext)

    override fun onCreate() {
        super.onCreate()

        // Réutilise le client HTTP de la session (certificat auto-signé compris).
        val callFactory = Call.Factory { request ->
            val client = activeClient() ?: throw IOException("Pas de session Sillon active")
            client.http.newCall(request)
        }
        val resolver = ResolvingDataSource.Resolver { spec: DataSpec ->
            if (spec.uri.scheme != SCHEME) {
                spec
            } else {
                val client = activeClient() ?: throw IOException("Pas de session Sillon active")
                val id = spec.uri.getQueryParameter("id") ?: throw IOException("Identifiant de morceau manquant")
                val ext = spec.uri.lastPathSegment.orEmpty().substringAfterLast('.', "")
                val url = client.streamUrl(id, ext) ?: throw IOException("API de streaming absente du NAS")
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
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                retriedMediaId = null
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
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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

    companion object {
        const val SCHEME = "sillon"
    }
}
