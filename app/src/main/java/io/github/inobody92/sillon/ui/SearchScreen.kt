package io.github.inobody92.sillon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.Artist
import io.github.inobody92.sillon.data.LibraryIndex
import io.github.inobody92.sillon.data.Song
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasResults get() = artists.isNotEmpty() || albums.isNotEmpty() || songs.isNotEmpty()
}

class SearchViewModel(private val client: SynoClient) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        // Dès que l'index devient prêt, on relance la recherche en cours avec la version souple.
        viewModelScope.launch {
            LibraryIndex.state.map { it.ready }.distinctUntilChanged().collect { ready ->
                if (ready && _state.value.query.isNotBlank()) onQueryChange(_state.value.query)
            }
        }
    }

    /**
     * Recherche locale instantanée si l'index est prêt (souple : accents, fautes de frappe, plusieurs champs).
     * Sinon, repli sur la recherche du NAS, après une courte pause de frappe pour ne pas l'interroger à chaque lettre.
     */
    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        job?.cancel()
        if (query.isBlank()) {
            _state.value = SearchState(query = query)
            return
        }
        job = viewModelScope.launch {
            if (LibraryIndex.isReady) {
                val r = withContext(Dispatchers.Default) { LibraryIndex.search(query) }
                _state.value = _state.value.copy(artists = r.artists, albums = r.albums, songs = r.songs, loading = false, error = null)
                return@launch
            }
            if (query.trim().length < MIN_CHARS) {
                _state.value = SearchState(query = query)
                return@launch
            }
            delay(DEBOUNCE_MS)
            _state.value = _state.value.copy(loading = true, error = null)
            val q = query.trim()
            try {
                // Les trois requêtes sont lancées en parallèle.
                val results = coroutineScope {
                    val ar = async { client.artists(0, MAX_ARTISTS, filter = q) }
                    val a = async { client.albums(0, MAX_ALBUMS, filter = q) }
                    val t = async { client.searchSongs(q, MAX_SONGS) }
                    Triple(ar.await(), a.await(), t.await())
                }
                _state.value = _state.value.copy(
                    artists = results.first,
                    albums = results.second,
                    songs = results.third,
                    loading = false,
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Erreur de recherche")
            }
        }
    }

    private companion object {
        const val MIN_CHARS = 2
        const val DEBOUNCE_MS = 350L
        const val MAX_ARTISTS = 10
        const val MAX_ALBUMS = 30
        const val MAX_SONGS = 50
    }
}

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    client: SynoClient,
    imageLoader: ImageLoader,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val index by LibraryIndex.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                placeholder = { Text("Artiste, album, titre…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Effacer")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (index.building && !index.ready) {
                Text(
                    "Indexation de ta bibliothèque… la recherche sera ensuite instantanée et plus souple.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    state.error != null -> Text(
                        state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
                    )
                    state.loading && !state.hasResults -> CircularProgressIndicator(Modifier.align(Alignment.TopCenter))
                    state.query.isNotBlank() && !state.loading && !state.hasResults -> Text(
                        "Aucun résultat pour « ${state.query.trim()} »",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        if (state.artists.isNotEmpty()) {
                            item(key = "h-artists") { SectionTitle("Artistes") }
                            items(state.artists, key = { "ar/${it.name}" }) { artist ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onOpenArtist(artist.name) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (state.albums.isNotEmpty()) {
                            item(key = "h-albums") { SectionTitle("Albums") }
                            items(state.albums, key = { "a/${it.albumArtist}/${it.name}" }) { album ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onOpenAlbum(album) }
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CoverImage(
                                        url = client.coverUrl(album, withSid = true),
                                        imageLoader = imageLoader,
                                        contentDescription = null,
                                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(album.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            album.displayArtist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        if (state.songs.isNotEmpty()) {
                            item(key = "h-songs") { SectionTitle("Titres") }
                            items(state.songs, key = { "s/${it.id}" }) { song ->
                                SongSearchRow(
                                    song, client, imageLoader,
                                    onClick = { onPlaySong(song) },
                                    onPlayNext = { onPlayNext(song) },
                                    onAddToQueue = { onAddToQueue(song) },
                                    onAddToPlaylist = { onAddToPlaylist(song) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SongSearchRow(
    song: Song,
    client: SynoClient,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverImage(
            url = client.songCoverUrl(song.id, withSid = true),
            imageLoader = imageLoader,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LikeButton(song.id, client)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Lire ensuite") }, onClick = { menu = false; onPlayNext() })
                DropdownMenuItem(text = { Text("Ajouter à la file") }, onClick = { menu = false; onAddToQueue() })
                DropdownMenuItem(text = { Text("Ajouter à une playlist") }, onClick = { menu = false; onAddToPlaylist() })
            }
        }
    }
}
