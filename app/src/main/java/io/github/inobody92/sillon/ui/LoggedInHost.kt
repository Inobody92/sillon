package io.github.inobody92.sillon.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
import io.github.inobody92.sillon.data.DownloadServerStore
import io.github.inobody92.sillon.data.DownloadServerClient
import io.github.inobody92.sillon.data.AppSettings
import io.github.inobody92.sillon.data.Downloads
import io.github.inobody92.sillon.data.LibraryIndex
import io.github.inobody92.sillon.data.Likes
import io.github.inobody92.sillon.data.OfflineStore
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
    val sleep by player.sleep.collectAsStateWithLifecycle()

    var album by rememberSaveable(stateSaver = AlbumSaver) { mutableStateOf(null) }
    var playlist by rememberSaveable(stateSaver = PlaylistSaver) { mutableStateOf(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 Accueil, 1 Albums, 2 Artistes, 3 Playlists, 4 Recherche
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var showTaste by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showEqualizer by rememberSaveable { mutableStateOf(false) }
    var showAddMusic by rememberSaveable { mutableStateOf(false) }
    var showProgress by rememberSaveable { mutableStateOf(false) }
    val downloadServer by DownloadServerStore.config.collectAsStateWithLifecycle()
    var albumsGenre by rememberSaveable { mutableStateOf<String?>(null) }
    var artist by rememberSaveable { mutableStateOf<String?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
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

    val browseVm: BrowseViewModel = viewModel(
        key = "browse/${client.creds.url}/${client.creds.account}",
        factory = viewModelFactory { initializer { BrowseViewModel(client) } },
    )
    val searchVm: SearchViewModel = viewModel(
        key = "search/${client.creds.url}/${client.creds.account}",
        factory = viewModelFactory { initializer { SearchViewModel(client) } },
    )

    // Retour : file d'attente, puis lecteur plein écran, puis écran de détail.
    // Notification du lecteur ou widget : on revient sur l'écran de lecture, quel que soit l'écran resté ouvert.
    val openRequest by OpenPlayerRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) {
        if (!openRequest) return@LaunchedEffect
        // Service qui vient de démarrer : on laisse un instant à la file enregistrée pour être restaurée.
        val ready = withTimeoutOrNull(5_000) { snapshotFlow { playerUi.hasItem }.first { it } }
        if (ready == true) {
            showSettings = false; showDownloads = false; showQueue = false
            showEqualizer = false; showAddMusic = false; showProgress = false
            nowPlaying = true
        }
        OpenPlayerRequest.clear()
    }

    BackHandler(enabled = showEqualizer) { showEqualizer = false }
    BackHandler(enabled = showAddMusic && !showProgress) { showAddMusic = false }
    BackHandler(enabled = showProgress) { showProgress = false }
    BackHandler(enabled = showTheme) { showTheme = false }
    BackHandler(enabled = showHidden) { showHidden = false }
    BackHandler(enabled = showTaste) { showTaste = false }
    BackHandler(enabled = showDownloads) { showDownloads = false }
    BackHandler(enabled = showSettings && !showDownloads && !showEqualizer) { showSettings = false }
    BackHandler(enabled = showQueue) { showQueue = false }
    BackHandler(enabled = nowPlaying && !showQueue && !showSettings && !showDownloads && !showEqualizer) { nowPlaying = false }
    BackHandler(enabled = !nowPlaying && album != null) { album = null }
    BackHandler(enabled = !nowPlaying && album == null && playlist != null) { playlist = null }
    BackHandler(enabled = !nowPlaying && album == null && playlist == null && artist != null) { artist = null }
    BackHandler(enabled = !nowPlaying && album == null && playlist == null && artist == null && genre != null) {
        genre = null
    }

    // Titres likés téléchargés automatiquement : chaque like ou retrait de like met la copie hors-ligne à jour.
    val likesState by Likes.state.collectAsStateWithLifecycle()
    val autoLikes by AppSettings.autoDownloadLikes.collectAsStateWithLifecycle()
    LaunchedEffect(likesState.revision, likesState.playlistId, autoLikes) {
        val id = likesState.playlistId
        if (id == null) {
            // La playlist n'existe plus (supprimée) : sa copie hors-ligne n'a plus de raison d'être.
            OfflineStore.state.value.collections
                .filter { it.type == "playlist" && it.name == Likes.NAME }
                .forEach { Downloads.unpin(context, it.id) }
            return@LaunchedEffect
        }
        if (autoLikes) {
            try {
                Downloads.pinPlaylist(context, client, Playlist(id, Likes.NAME))
            } catch (_: Exception) {
                // NAS injoignable : ce sera refait au prochain like ou à la prochaine connexion.
            }
        }
    }

    // « Titres likés » : synchronisation avec le NAS à chaque connexion, et affichage des erreurs éventuelles.
    LaunchedEffect(client) { Likes.sync(client); io.github.inobody92.sillon.data.Hidden.sync(client) }
    LaunchedEffect(client) { io.github.inobody92.sillon.data.NasProfile.sync(client) }
    // Découvertes du jour : calculées à l'ouverture si ce n'est pas déjà fait, puis chaque jour en arrière-plan.
    val discoveries by io.github.inobody92.sillon.data.DiscoveryStore.state.collectAsStateWithLifecycle()
    val discoveryOn by AppSettings.discovery.collectAsStateWithLifecycle()
    LaunchedEffect(downloadServer != null, discoveryOn) {
        io.github.inobody92.sillon.data.DiscoveryScheduler.update(context)
        io.github.inobody92.sillon.data.DiscoveryRunner.run(context)
    }
    // Index de recherche : relu seulement si la bibliothèque a changé de taille ou s'il est ancien.
    LaunchedEffect(client) {
        LibraryIndex.ensure(client)
        io.github.inobody92.sillon.data.TasteStore.refresh(client)
    }
    LaunchedEffect(Unit) { Likes.errors.collect { showMessage(it) } }
    LaunchedEffect(Unit) { io.github.inobody92.sillon.data.Hidden.errors.collect { showMessage(it) } }

    // Playlists téléchargées : si elles ont changé sur le NAS depuis la dernière fois, on les remet à jour.
    LaunchedEffect(client) {
        for (c in OfflineStore.state.value.collections.filter { it.type == "playlist" }) {
            try {
                val id = c.id.removePrefix("playlist:")
                val current = client.playlistSongs(id).map { it.id }
                if (current != c.songIds) {
                    Downloads.pinPlaylist(context, client, Playlist(id, c.name))
                    showMessage("« ${c.name} » mise à jour hors-ligne")
                }
            } catch (_: Exception) {
                // Playlist supprimée ou NAS indisponible : on garde la copie locale telle quelle.
            }
        }
    }

    fun downloadStartMessage() =
        if (AppSettings.downloadWifiOnly.value && !AppSettings.isUnmetered(context)) {
            "Téléchargement en attente du Wi-Fi"
        } else {
            "Téléchargement lancé"
        }

    fun toggleAlbumDownload(album: Album) {
        val key = OfflineStore.albumKey(album)
        if (OfflineStore.state.value.isPinned(key)) {
            Downloads.unpin(context, key)
            showMessage("Téléchargement supprimé")
        } else {
            scope.launch {
                try {
                    Downloads.pinAlbum(context, client, album)
                    showMessage(downloadStartMessage())
                } catch (e: Exception) {
                    showMessage(e.message ?: "Téléchargement impossible")
                }
            }
        }
    }

    fun togglePlaylistDownload(playlist: Playlist) {
        val key = OfflineStore.playlistKey(playlist.id)
        if (OfflineStore.state.value.isPinned(key)) {
            Downloads.unpin(context, key)
            showMessage("Téléchargement supprimé")
        } else {
            scope.launch {
                try {
                    Downloads.pinPlaylist(context, client, playlist)
                    showMessage(downloadStartMessage())
                } catch (e: Exception) {
                    showMessage(e.message ?: "Téléchargement impossible")
                }
            }
        }
    }

    /** Lance tous les titres d'un artiste (chargés à la demande). */
    fun playArtist(name: String, shuffle: Boolean) {
        scope.launch {
            try {
                val songs = client.songsByArtist(name)
                if (songs.isEmpty()) {
                    showMessage("Aucun titre pour $name")
                } else {
                    player.play(client, if (shuffle) songs.shuffled() else songs, 0, keepStart = false)
                }
            } catch (e: Exception) {
                showMessage(e.message ?: "Impossible de charger les titres")
            }
        }
    }

    fun resetNavigation() {
        album = null
        playlist = null
        artist = null
        genre = null
    }

    fun queueMessage(count: Int, where: String) =
        showMessage(if (count == 1) "1 titre $where" else "$count titres $where")

    Box(Modifier.fillMaxSize()) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val destinations = listOf(
            Triple("Accueil", Icons.Filled.Home, 0),
            Triple("Albums", Icons.Filled.Album, 1),
            Triple("Artistes", Icons.Filled.Person, 2),
            Triple("Playlists", Icons.AutoMirrored.Filled.QueueMusic, 3),
            Triple("Recherche", Icons.Filled.Search, 4),
        )
        Row(Modifier.fillMaxSize()) {
          // Paysage : la hauteur est comptée, la navigation passe sur le côté au lieu de la barre du bas.
          if (landscape) {
            NavigationRail {
                Spacer(Modifier.weight(1f))
                destinations.forEach { (label, icon, index) ->
                    NavigationRailItem(
                        selected = tab == index,
                        onClick = { tab = index; resetNavigation() },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { FitLabel(label) },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
          }
          Column(Modifier.weight(1f).fillMaxHeight().then(if (landscape) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier)) {
            Box(Modifier.weight(1f)) {
                val currentAlbum = album
                val currentPlaylist = playlist
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalNowPlaying provides NowPlayingInfo(playerUi.mediaId, playerUi.isPlaying),
                ) {
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
                        onShuffle = { songs -> player.play(client, songs.shuffled(), 0, currentAlbum, keepStart = false) },
                        onToggleDownload = { toggleAlbumDownload(currentAlbum) },
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
                        onToggleDownload = { togglePlaylistDownload(currentPlaylist) },
                    )
                    artist != null -> {
                        val name = artist!!
                        AlbumsScreen(
                            client, imageLoader,
                            onOpenAlbum = { album = it },
                            title = name,
                            artist = name,
                            onBack = { artist = null },
                            header = {
                                Row(
                                    Modifier.padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Button(onClick = { playArtist(name, shuffle = false) }) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Lecture")
                                    }
                                    OutlinedButton(onClick = { playArtist(name, shuffle = true) }) {
                                        Icon(Icons.Filled.Shuffle, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Aléatoire")
                                    }
                                }
                            },
                        )
                    }
                    genre != null -> {
                        val name = genre!!
                        AlbumsScreen(
                            client, imageLoader,
                            onOpenAlbum = { album = it },
                            title = name,
                            genre = name,
                            onBack = { genre = null },
                        )
                    }
                    tab == 0 -> {
                        val browse by browseVm.state.collectAsStateWithLifecycle()
                        val appContext = LocalContext.current.applicationContext as android.app.Application
                        val homeVm: HomeViewModel = viewModel(
                            key = "home/${client.creds.url}/${client.creds.account}",
                            factory = viewModelFactory { initializer { HomeViewModel(appContext, client) } },
                        )
                        HomeScreen(
                            vm = homeVm,
                            client = client,
                            imageLoader = imageLoader,
                            libraryGenres = browse.genres.map { it.name },
                            onPlayMix = { songs -> player.play(client, songs, 0) },
                            onPlayRadio = { mood -> player.playRadio(mood) },
                            onOpenAlbum = { album = it },
                            onOpenAddMusic = if (downloadServer != null) ({ showAddMusic = true }) else null,
                            suggestions = if (downloadServer != null) discoveries.suggestions else emptyList(),
                            discoveriesEnabled = downloadServer != null && discoveryOn,
                            onDownloadSuggestion = { s, n -> scope.launch { showMessage(io.github.inobody92.sillon.data.DiscoveryActions.download(context, s, n)) } },
                            onDismissSuggestion = { s -> io.github.inobody92.sillon.data.DiscoveryStore.dismiss(s) },
                            onRefreshSuggestions = {
                                showMessage("Recherche de nouvelles suggestions…")
                                scope.launch { io.github.inobody92.sillon.data.DiscoveryRunner.run(context, force = true) }
                            },
                            onSettings = { showSettings = true },
                        )
                    }
                    tab == 1 -> {
                        val browse by browseVm.state.collectAsStateWithLifecycle()
                        AlbumsScreen(
                            client, imageLoader,
                            onOpenAlbum = { album = it },
                            genre = albumsGenre,
                            onSettings = { showSettings = true },
                            header = if (browse.genres.isEmpty()) null else {
                                {
                                    GenreChips(
                                        genres = browse.genres.map { it.name },
                                        selected = albumsGenre,
                                        onSelect = { albumsGenre = it },
                                    )
                                }
                            },
                        )
                    }
                    tab == 2 -> BrowseScreen(browseVm, onOpenArtist = { artist = it }, onOpenGenre = { genre = it })
                    tab == 3 -> PlaylistsScreen(playlistsVm, onOpen = { playlist = it }, onMessage = showMessage)
                    else -> SearchScreen(
                        vm = searchVm,
                        client = client,
                        imageLoader = imageLoader,
                        onOpenAlbum = { album = it },
                        onOpenArtist = { artist = it },
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
            }
            // Zone basse : mini-lecteur + navigation.
            Surface(tonalElevation = 3.dp) {
                Column {
                    if (playerUi.hasItem) {
                        MiniPlayer(playerUi, player, imageLoader, onOpen = { nowPlaying = true })
                    }
                    if (!landscape) NavigationBar {
                        destinations.forEach { (label, icon, index) ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index; resetNavigation() },
                                icon = { Icon(icon, contentDescription = null) },
                                label = { FitLabel(label) },
                            )
                        }
                    }
                }
            }
          }
        }
        if (nowPlaying && playerUi.hasItem) {
            NowPlayingScreen(
                playerUi, player, imageLoader,
                client = client,
                sleep = sleep,
                onSetSleep = player::setSleepTimer,
                onClose = { nowPlaying = false },
                onOpenQueue = { showQueue = true },
                onAddToPlaylist = { mediaId -> addTarget = listOf(mediaId) },
                onOpenEqualizer = { showEqualizer = true },
                onOpenAlbum = { target -> album = target; nowPlaying = false },
                onOpenArtist = { name ->
                    // La discographie s'ouvre à la place de ce qui était affiché ; retour = l'onglet d'avant.
                    album = null; playlist = null; genre = null
                    artist = name
                    nowPlaying = false
                },
            )
        }
        if (showSettings) {
            SettingsScreen(
                client = client,
                onClose = { showSettings = false },
                onOpenDownloads = { showDownloads = true },
                onOpenTaste = { showTaste = true },
                onOpenHidden = { showHidden = true },
                onOpenTheme = { showTheme = true },
                onOpenEqualizer = { showEqualizer = true },
                onLogout = { showSettings = false; onLogout() },
            )
        }
        if (showEqualizer) EqualizerScreen(onClose = { showEqualizer = false })
        downloadServer?.takeIf { showAddMusic }?.let { config ->
            val addMusicVm: AddMusicViewModel = viewModel(
                key = "add-music-${config.hashCode()}",
                factory = viewModelFactory { initializer { AddMusicViewModel(DownloadServerClient.create(config), client, onQueued = { n -> io.github.inobody92.sillon.data.DownloadWatch.watch(context.applicationContext, n) }, requests = io.github.inobody92.sillon.data.DownloadRequestLog(context)) } },
            )
            AddMusicScreen(
                addMusicVm, imageLoader,
                polling = !showProgress,
                onOpenProgress = { showProgress = true },
                onClose = { showAddMusic = false },
                onMessage = showMessage,
            )
            if (showProgress) DownloadProgressScreen(addMusicVm, onClose = { showProgress = false })
        }
        if (showTheme) ThemeScreen(onClose = { showTheme = false })
        if (showHidden) HiddenScreen(client = client, onClose = { showHidden = false })
        if (showTaste) TasteScreen(client = client, onClose = { showTaste = false })
        if (showDownloads) {
            DownloadsScreen(
                client = client,
                onClose = { showDownloads = false },
                onRetry = null,
                onPlay = { songs, index -> player.play(client, songs, index) },
                onMessage = showMessage,
            )
        }
        if (showQueue && playerUi.hasItem) {
            QueueScreen(playerUi, player, imageLoader, client, onClose = { showQueue = false })
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

/** Rangée de puces pour filtrer la grille d'albums par genre ; « Tous » retire le filtre. */
@Composable
private fun GenreChips(genres: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            androidx.compose.material3.FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("Tous") },
            )
        }
        items(genres.size, key = { genres[it] }) { i ->
            val name = genres[i]
            androidx.compose.material3.FilterChip(
                selected = selected == name,
                onClick = { onSelect(if (selected == name) null else name) },
                label = { Text(name) },
            )
        }
    }
}
