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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.inobody92.sillon.data.Playlist
import kotlinx.coroutines.launch
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

private val PlaylistSaver = Saver<Playlist?, List<String>>(
    save = { p -> if (p == null) emptyList() else listOf(p.id, p.name) },
    restore = { l -> if (l.size == 2) Playlist(l[0], l[1]) else null },
)

@Composable
fun LoggedInHost(client: SynoClient, playerViewModel: PlayerViewModel, onLogout: () -> Unit) {
    val context = LocalContext.current
    val imageLoader = rememberImageLoader(client)
    val player = playerViewModel.controller
    val playerUi by player.ui.collectAsStateWithLifecycle()

    var album by rememberSaveable(stateSaver = AlbumSaver) { mutableStateOf(null) }
    var playlist by rememberSaveable(stateSaver = PlaylistSaver) { mutableStateOf(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    var addTarget by remember { mutableStateOf<List<String>?>(null) }

    val playlistsVm: PlaylistsViewModel = viewModel(
        key = "playlists/${client.creds.url}/${client.creds.account}",
        factory = viewModelFactory { initializer { PlaylistsViewModel(client) } },
    )
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

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
    BackHandler(enabled = !nowPlaying && (album != null || playlist != null)) {
        album = null
        playlist = null
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val currentAlbum = album
                val currentPlaylist = playlist
                when {
                    currentAlbum != null -> AlbumDetailScreen(
                        client = client,
                        album = currentAlbum,
                        imageLoader = imageLoader,
                        onBack = { album = null },
                        onPlay = { songs, index -> player.play(client, songs, index, currentAlbum) },
                        onAddToPlaylist = { songs -> addTarget = songs.map { it.id } },
                    )
                    currentPlaylist != null -> PlaylistDetailScreen(
                        client = client,
                        playlist = currentPlaylist,
                        imageLoader = imageLoader,
                        onBack = { playlist = null },
                        onPlay = { songs, index -> player.play(client, songs, index) },
                        onMessage = showMessage,
                    )
                    tab == 0 -> AlbumsScreen(client, imageLoader, onOpenAlbum = { album = it }, onLogout = onLogout)
                    else -> PlaylistsScreen(playlistsVm, onOpen = { playlist = it }, onMessage = showMessage)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            // Zone basse : mini-lecteur + navigation.
            Surface(tonalElevation = 3.dp) {
                Column {
                    if (playerUi.hasItem) {
                        MiniPlayer(playerUi, player, imageLoader, onOpen = { nowPlaying = true })
                    }
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0; album = null; playlist = null },
                            icon = { Icon(Icons.Filled.Album, contentDescription = null) },
                            label = { Text("Albums") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1; album = null; playlist = null },
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            label = { Text("Playlists") },
                        )
                    }
                }
            }
        }
        if (nowPlaying && playerUi.hasItem) {
            NowPlayingScreen(playerUi, player, imageLoader, onClose = { nowPlaying = false })
        }
        addTarget?.let { ids ->
            AddToPlaylistDialog(
                vm = playlistsVm,
                songIds = ids,
                onDismiss = { addTarget = null },
                onMessage = showMessage,
            )
        }
    }
}
