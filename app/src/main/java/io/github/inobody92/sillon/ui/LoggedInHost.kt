package io.github.inobody92.sillon.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.SynoClient
import io.github.inobody92.sillon.playback.PlayerController

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    val controller = PlayerController(app)

    override fun onCleared() {
        controller.release()
    }
}

private val AlbumSaver = Saver<Album?, List<Any>>(
    save = { a -> if (a == null) emptyList() else listOf(a.name, a.albumArtist, a.artist, a.year) },
    restore = { l -> if (l.size == 4) Album(l[0] as String, l[1] as String, l[2] as String, l[3] as Int) else null },
)

@Composable
fun LoggedInHost(client: SynoClient, playerViewModel: PlayerViewModel, onLogout: () -> Unit) {
    val context = LocalContext.current
    val imageLoader = rememberImageLoader(client)
    val player = playerViewModel.controller
    val playerUi by player.ui.collectAsStateWithLifecycle()

    var album by rememberSaveable(stateSaver = AlbumSaver) { mutableStateOf(null) }
    var nowPlaying by rememberSaveable { mutableStateOf(false) }

    // Android 13+ : sans cette permission, la notification de lecture n'est pas affichée.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = nowPlaying) { nowPlaying = false }
    BackHandler(enabled = !nowPlaying && album != null) { album = null }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val current = album
                if (current == null) {
                    AlbumsScreen(client, imageLoader, onOpenAlbum = { album = it }, onLogout = onLogout)
                } else {
                    AlbumDetailScreen(
                        client = client,
                        album = current,
                        imageLoader = imageLoader,
                        onBack = { album = null },
                        onPlay = { songs, index -> player.play(client, current, songs, index) },
                    )
                }
            }
            // Zone basse : mini-lecteur + barre de navigation système.
            Surface(tonalElevation = 3.dp) {
                Column {
                    if (playerUi.hasItem) {
                        MiniPlayer(playerUi, player, imageLoader, onOpen = { nowPlaying = true })
                    }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
        if (nowPlaying && playerUi.hasItem) {
            NowPlayingScreen(playerUi, player, imageLoader, onClose = { nowPlaying = false })
        }
    }
}
