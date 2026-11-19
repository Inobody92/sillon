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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.ImageLoader
import io.github.inobody92.sillon.data.Playlist
import io.github.inobody92.sillon.data.Song
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Une ligne de playlist. La clé est unique même si le même morceau apparaît deux fois. */
data class PlaylistEntry(val key: Long, val song: Song)

data class PlaylistDetailState(
    val entries: List<PlaylistEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class PlaylistDetailViewModel(private val client: SynoClient, val playlist: Playlist) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailState())
    val state: StateFlow<PlaylistDetailState> = _state.asStateFlow()

    private var nextKey = 0L

    /** Ordre connu du serveur : permet de n'envoyer une écriture que si l'ordre a vraiment changé. */
    private var syncedIds: List<String> = emptyList()

    /** Les écritures partent l'une après l'autre : l'index envoyé doit correspondre à l'état du serveur. */
    private val writeLock = Mutex()

    init {
        load()
    }

    fun load() {
        _state.value = PlaylistDetailState(loading = true)
        viewModelScope.launch {
            _state.value = try {
                val songs = writeLock.withLock { client.playlistSongs(playlist.id) }
                syncedIds = songs.map { it.id }
                PlaylistDetailState(entries = songs.map { PlaylistEntry(nextKey++, it) }, loading = false)
            } catch (e: Exception) {
                PlaylistDetailState(loading = false, error = e.message ?: "Erreur de chargement")
            }
        }
    }

    /** Déplacement local pendant le glissement ; l'enregistrement se fait au relâchement. */
    fun move(from: Int, to: Int) {
        _state.update { s ->
            if (from !in s.entries.indices || to !in s.entries.indices) return@update s
            s.copy(entries = s.entries.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    fun commitOrder(onError: (String) -> Unit) {
        val ids = _state.value.entries.map { it.song.id }
        if (ids == syncedIds) return
        viewModelScope.launch {
            try {
                writeLock.withLock {
                    client.reorderPlaylist(playlist.id, ids)
                    syncedIds = ids
                }
            } catch (e: Exception) {
                onError("Réordonnancement impossible : ${e.message}")
                load()
            }
        }
    }

    fun remove(index: Int, onResult: (String) -> Unit) {
        val removed = _state.value.entries.getOrNull(index) ?: return
        _state.update { it.copy(entries = it.entries.filterIndexed { i, _ -> i != index }) }
        viewModelScope.launch {
            try {
                writeLock.withLock {
                    client.removeFromPlaylist(playlist.id, index)
                    syncedIds = syncedIds.filterIndexed { i, _ -> i != index }
                }
                onResult("« ${removed.song.title} » retiré")
            } catch (e: Exception) {
                onResult("Suppression impossible : ${e.message}")
                load()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    client: SynoClient,
    playlist: Playlist,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    onMessage: (String) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
) {
    val vm: PlaylistDetailViewModel = viewModel(
        key = "playlist/${playlist.id}",
        factory = viewModelFactory { initializer { PlaylistDetailViewModel(client, playlist) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val songs = state.entries.map { it.song }

    val listState = rememberLazyListState()
    // L'en-tête occupe l'index 0 de la liste : on décale pour retrouver la position dans la playlist.
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        vm.move(from.index - HEADER_ITEMS, to.index - HEADER_ITEMS)
    }

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
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.headlineMedium)
                        if (!state.loading && state.error == null) {
                            val total = songs.sumOf { it.durationSec }
                            Text(
                                "${songs.size} titre${if (songs.size > 1) "s" else ""} · ${formatTime(total * 1000L)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { onPlay(songs, 0) }, enabled = songs.isNotEmpty()) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Lecture")
                        }
                    }
                }
                if (state.loading) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                state.error?.let { msg ->
                    item(key = "error") {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(msg, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = vm::load) { Text("Réessayer") }
                        }
                    }
                }
                if (!state.loading && state.error == null && songs.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Cette playlist est vide.\nAjoute des morceaux depuis un album.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                itemsIndexed(state.entries, key = { _, e -> e.key }) { index, entry ->
                    ReorderableItem(reorderState, key = entry.key) { isDragging ->
                        Surface(
                            tonalElevation = if (isDragging) 6.dp else 0.dp,
                            shadowElevation = if (isDragging) 6.dp else 0.dp,
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlay(songs, index) }
                                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CoverImage(
                                    url = client.songCoverUrl(entry.song.id, withSid = true),
                                    imageLoader = imageLoader,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.song.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        entry.song.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Lire ensuite") },
                                            onClick = { menu = false; onPlayNext(entry.song) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ajouter à la file") },
                                            onClick = { menu = false; onAddToQueue(entry.song) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Retirer de la playlist") },
                                            onClick = {
                                                menu = false
                                                vm.remove(index, onMessage)
                                            },
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(onDragStopped = { vm.commitOrder(onMessage) }),
                                ) {
                                    Icon(Icons.Filled.DragHandle, contentDescription = "Déplacer")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val HEADER_ITEMS = 1
