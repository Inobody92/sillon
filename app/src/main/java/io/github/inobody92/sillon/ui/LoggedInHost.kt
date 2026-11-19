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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Search
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
    var showQueue by rememberSaveable { mutableStateOf(false) }
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

    val searchVm: SearchViewModel = viewModel(
        key = "search/${client.creds.url}/${client.creds.account}",
        factory = viewModelFactory { initializer { SearchViewModel(client) } },
    )

    // Retour : file d'attente, puis lecteur plein écran, puis écran de détail.
    BackHandler(enabled = showQueue) { showQueue = false }
    BackHandler(enabled = nowPlaying && !showQueue) { nowPlaying = false }
    BackHandler(enabled = !nowPlaying && (album != null || playlist != null)) {
        album = null
        playlist = null
    }

    fun queueMessage(count: Int, where: String) =
        showMessage(if (count == 1) "1 titre $where" else "$count titres $where")

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
                        onPlayNext = { songs ->
                            player.playNext(client, songs, currentAlbum)
                            queueMessage(songs.size, "à lire ensuite")
                        },
                        onAddToQueue = { songs ->
                            player.addToQueue(client, songs, currentAlbum)
                            queueMessage(songs.size, "ajouté à la file")
                        },
                        onShuffle = { songs -> player.play(client, songs.shuffled(), 0, currentAlbum) },
                    )
                    currentPlaylist != null -> PlaylistDetailScreen(
                        client = client,
                        playlist = currentPlaylist,
                        imageLoader = imageLoader,
                        onBack = { playlist = null },
                        onPlay = { songs, index -> player.play(client, songs, index) },
                        onMessage = showMessage,
                        onPlayNext = { song ->
                            player.playNext(client, listOf(song))
                            queueMessage(1, "à lire ensuite")
                        },
                        onAddToQueue = { song ->
                            player.addToQueue(client, listOf(song))
                            queueMessage(1, "ajouté à la file")
                        },
                    )
                    tab == 0 -> AlbumsScreen(client, imageLoader, onOpenAlbum = { album = it }, onLogout = onLogout)
                    tab == 1 -> PlaylistsScreen(playlistsVm, onOpen = { playlist = it }, onMessage = showMessage)
                    else -> SearchScreen(
                        vm = searchVm,
                        client = client,
                        imageLoader = imageLoader,
                        onOpenAlbum = { album = it },
                        onPlaySong = { song -> player.play(client, listOf(song), 0) },
                        onPlayNext = { song ->
                            player.playNext(client, listOf(song))
                            queueMessage(1, "à lire ensuite")
                        },
                        onAddToQueue = { song ->
                            player.addToQueue(client, listOf(song))
                            queueMessage(1, "ajouté à la file")
                        },
                        onAddToPlaylist = { song -> addTarget = listOf(song.id) },
                    )
                }
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
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2; album = null; playlist = null },
                            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            label = { Text("Recherche") },
                        )
                    }
                }
            }
        }
        if (nowPlaying && playerUi.hasItem) {
            NowPlayingScreen(
                playerUi, player, imageLoader,
                onClose = { nowPlaying = false },
                onOpenQueue = { showQueue = true },
                onAddToPlaylist = { mediaId -> addTarget = listOf(mediaId) },
            )
        }
        if (showQueue && playerUi.hasItem) {
            QueueScreen(playerUi, player, imageLoader, onClose = { showQueue = false })
        }
        addTarget?.let { ids ->
            AddToPlaylistDialog(
                vm = playlistsVm,
                songIds = ids,
                onDismiss = { addTarget = null },
                onMessage = showMessage,
            )
        }
        // Au-dessus de tout, pour que les messages restent visibles depuis le lecteur plein écran.
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp),
        )
    }
}
