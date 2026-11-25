package io.github.inobody92.sillon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.ImageLoader
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.data.Song
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AlbumDetailState(
    val songs: List<Song> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class AlbumDetailViewModel(private val client: SynoClient, private val album: Album) : ViewModel() {
    private val _state = MutableStateFlow(AlbumDetailState())
    val state: StateFlow<AlbumDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = AlbumDetailState(loading = true)
        viewModelScope.launch {
            _state.value = try {
                AlbumDetailState(songs = client.songs(album), loading = false)
            } catch (e: Exception) {
                AlbumDetailState(loading = false, error = e.message ?: "Erreur de chargement")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    client: SynoClient,
    album: Album,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    onAddToPlaylist: (List<Song>) -> Unit,
    onPlayNext: (List<Song>) -> Unit,
    onAddToQueue: (List<Song>) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onToggleDownload: () -> Unit,
) {
    val offline by OfflineStore.state.collectAsStateWithLifecycle()
    val collection = offline.collection(OfflineStore.albumKey(album))
    val vm: AlbumDetailViewModel = viewModel(
        key = "album/${album.albumArtist}/${album.name}",
        factory = viewModelFactory { initializer { AlbumDetailViewModel(client, album) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Header(
                        album, client, imageLoader,
                        canPlay = state.songs.isNotEmpty(),
                        onPlay = { onPlay(state.songs, 0) },
                        onAddAll = { onAddToPlaylist(state.songs) },
                        onShuffle = { onShuffle(state.songs) },
                        onPlayNext = { onPlayNext(state.songs) },
                        onAddToQueue = { onAddToQueue(state.songs) },
                        offlineStatus = collection?.let { "${offline.downloadedCount(it)}/${it.songIds.size}" },
                        onToggleDownload = onToggleDownload,
                    )
                }
                if (state.loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                state.error?.let { msg ->
                    item {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(msg, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = vm::load) { Text("Réessayer") }
                        }
                    }
                }
                itemsIndexed(state.songs, key = { _, s -> s.id }) { index, song ->
                    SongRow(
                        song,
                        showDisc = state.songs.any { it.disc > 1 },
                        onClick = { onPlay(state.songs, index) },
                        onAdd = { onAddToPlaylist(listOf(song)) },
                        onPlayNext = { onPlayNext(listOf(song)) },
                        onAddToQueue = { onAddToQueue(listOf(song)) },
                        downloaded = song.id in offline.songs,
                        client = client,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    album: Album,
    client: SynoClient,
    imageLoader: ImageLoader,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onAddAll: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    offlineStatus: String?,
    onToggleDownload: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            CoverImage(
                url = client.coverUrl(album, withSid = true),
                imageLoader = imageLoader,
                contentDescription = album.name,
                modifier = Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(album.name, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    album.displayArtist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (album.year > 0) {
                    Text(
                        album.year.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (offlineStatus != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(
                            Icons.Filled.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            " Hors-ligne · $offlineStatus",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlay, enabled = canPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Lecture")
            }
            OutlinedButton(onClick = onAddAll, enabled = canPlay) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Playlist")
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, enabled = canPlay) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'options")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Lecture aléatoire") }, onClick = { menu = false; onShuffle() })
                    DropdownMenuItem(text = { Text("Lire ensuite") }, onClick = { menu = false; onPlayNext() })
                    DropdownMenuItem(text = { Text("Ajouter à la file") }, onClick = { menu = false; onAddToQueue() })
                    DropdownMenuItem(
                        text = { Text(if (offlineStatus != null) "Supprimer le téléchargement" else "Télécharger") },
                        onClick = { menu = false; onToggleDownload() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    showDisc: Boolean,
    onClick: () -> Unit,
    onAdd: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    downloaded: Boolean,
    client: SynoClient?,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val nowPlaying = LocalNowPlaying.current
        val current = nowPlaying.isCurrent(song.id)
        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            if (current) {
                NowPlayingBars(nowPlaying.isPlaying, MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    if (song.track > 0) (if (showDisc) "${song.disc}.${song.track}" else "${song.track}") else "–",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.artist.isNotBlank()) {
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (downloaded) {
            Icon(
                Icons.Filled.DownloadDone,
                contentDescription = "Disponible hors-ligne",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            formatTime(song.durationSec * 1000L),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LikeButton(song.id, client)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Lire ensuite") }, onClick = { menu = false; onPlayNext() })
                DropdownMenuItem(text = { Text("Ajouter à la file") }, onClick = { menu = false; onAddToQueue() })
                DropdownMenuItem(text = { Text("Ajouter à une playlist") }, onClick = { menu = false; onAdd() })
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
