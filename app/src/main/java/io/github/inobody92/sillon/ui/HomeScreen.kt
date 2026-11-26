package io.github.inobody92.sillon.ui

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.DailyMix
import io.github.inobody92.sillon.data.LibraryIndex
import io.github.inobody92.sillon.data.PlayHistory
import io.github.inobody92.sillon.data.Mood
import io.github.inobody92.sillon.data.MoodStore
import io.github.inobody92.sillon.data.Song
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HomeState(
    val mix: List<Song> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val added: List<Album> = emptyList(),
    val played: List<Album> = emptyList(),
)

class HomeViewModel(app: Application, private val client: SynoClient) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        load(regenerate = false)
        loadAdded()
        // L'index de recherche sert à retrouver l'album de chaque titre écouté : on recalcule quand il est prêt.
        viewModelScope.launch {
            LibraryIndex.state.map { it.ready }.distinctUntilChanged().collect { refreshPlayed() }
        }
    }

    fun load(regenerate: Boolean) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val mix = DailyMix.get(getApplication(), client, regenerate)
                _state.update { it.copy(mix = mix, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Impossible de préparer le mix") }
            }
        }
    }

    /** Derniers albums ajoutés à la bibliothèque (tri par date d'ajout côté NAS). */
    private fun loadAdded() {
        viewModelScope.launch {
            runCatching { client.albums(0, 15, sortBy = "time", sortDirection = "desc") }
                .onSuccess { albums -> _state.update { it.copy(added = albums.filterNot { a -> io.github.inobody92.sillon.data.isTempDownloadAlbum(a.name) }) } }
        }
    }

    /** Albums des derniers titres écoutés, du plus récent au plus ancien, sans doublon. */
    fun refreshPlayed() {
        val ids = PlayHistory.recentOrdered(getApplication(), 60)
        val songs = LibraryIndex.songsByIds(ids)
        val albums = songs.filter { it.album.isNotBlank() }
            .distinctBy { it.album to it.albumArtist.ifBlank { it.artist } }
            .take(12)
            .map { Album(it.album, it.albumArtist, it.artist, it.year) }
        _state.update { it.copy(played = albums) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    client: SynoClient,
    imageLoader: ImageLoader,
    libraryGenres: List<String>,
    onPlayMix: (List<Song>) -> Unit,
    onPlayRadio: (Mood) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenAddMusic: (() -> Unit)?,
    suggestions: List<io.github.inobody92.sillon.data.Suggestion>,
    discoveriesEnabled: Boolean,
    onDownloadSuggestion: (io.github.inobody92.sillon.data.Suggestion, Int) -> Unit,
    onDismissSuggestion: (io.github.inobody92.sillon.data.Suggestion) -> Unit,
    onRefreshSuggestions: () -> Unit,
    onSettings: () -> Unit,
) {
    // L'historique change à chaque écoute : on le relit à chaque retour sur l'accueil.
    LaunchedEffect(Unit) { vm.refreshPlayed() }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Mood?>(null) }
    // Incrémenté après chaque enregistrement pour que les cartes recalculent leur nombre de genres.
    var moodVersion by remember { mutableIntStateOf(0) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text("Accueil") },
                actions = {
                    // Seulement si le serveur de téléchargement est configuré.
                    if (onOpenAddMusic != null) {
                        IconButton(onClick = onOpenAddMusic) { Icon(Icons.Filled.LibraryAdd, contentDescription = "Ajouter de la musique") }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Réglages") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item(key = "mix") {
                MixCard(state, client, imageLoader, onPlay = { onPlayMix(state.mix) }, onRegenerate = { vm.load(true) }, onRetry = { vm.load(false) })
            }
            if (discoveriesEnabled || suggestions.isNotEmpty()) {
                item(key = "discoveries") { DiscoverySection(suggestions, imageLoader, onDownloadSuggestion, onDismissSuggestion, onRefreshSuggestions) }
            }
            if (state.played.isNotEmpty()) {
                item(key = "played") { AlbumRow("Écoutés récemment", state.played, client, imageLoader, onOpenAlbum) }
            }
            if (state.added.isNotEmpty()) {
                item(key = "added") { AlbumRow("Ajoutés récemment", state.added, client, imageLoader, onOpenAlbum) }
            }
            item(key = "radio-title") {
                Text(
                    "Radio",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    "Une radio sans fin, tirée de ta bibliothèque selon l'humeur.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Mood.entries.chunked(2).forEach { pair ->
                item(key = "moods-${pair.first().id}") {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { mood ->
                            // moodVersion est lu ici pour forcer le recalcul après modification.
                            val count = remember(moodVersion, libraryGenres) {
                                MoodStore.genres(context, mood, libraryGenres).size
                            }
                            MoodCard(
                                mood, count,
                                modifier = Modifier.weight(1f),
                                onPlay = { onPlayRadio(mood) },
                                onEdit = { editing = mood },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    editing?.let { mood ->
        MoodEditorDialog(
            mood = mood,
            libraryGenres = libraryGenres,
            onDismiss = { editing = null },
            onSaved = { editing = null; moodVersion++ },
        )
    }
}

@Composable
private fun AlbumRow(
    title: String,
    albums: List<Album>,
    client: SynoClient,
    imageLoader: ImageLoader,
    onOpen: (Album) -> Unit,
) {
    Column(Modifier.padding(top = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(albums, key = { "${it.albumArtist}/${it.name}" }) { album ->
                Column(Modifier.width(120.dp).clickable { onOpen(album) }) {
                    CoverImage(
                        url = client.coverUrl(album, withSid = true),
                        imageLoader = imageLoader,
                        contentDescription = album.name,
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Text(
                        album.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
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
}

@Composable
private fun MixCard(
    state: HomeState,
    client: SynoClient,
    imageLoader: ImageLoader,
    onPlay: () -> Unit,
    onRegenerate: () -> Unit,
    onRetry: () -> Unit,
) {
    val today = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)).replaceFirstChar { it.uppercase() }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mix du jour", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        if (state.mix.isEmpty()) today else "$today · ${state.mix.size} titres",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                IconButton(onClick = onRegenerate, enabled = !state.loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Nouveau mix", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                state.loading && state.mix.isEmpty() -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null && state.mix.isEmpty() -> Column {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Réessayer") }
                }
                state.mix.isEmpty() -> Text("Ta bibliothèque est vide.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                else -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.mix.take(10), key = { it.id }) { song ->
                            CoverImage(
                                url = client.songCoverUrl(song.id, withSid = true),
                                imageLoader = imageLoader,
                                contentDescription = song.title,
                                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.mix.take(3).joinToString(" · ") { it.title },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onPlay) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Écouter")
                    }
                }
            }
        }
    }
}

/** Icône de chaque humeur (jamais d'émoji : ils rendent différemment selon l'appareil et font bon marché). */
private fun Mood.icon(): ImageVector = when (this) {
    Mood.ENERGY -> Icons.Filled.Bolt
    Mood.CHILL -> Icons.Filled.NightsStay
    Mood.PARTY -> Icons.Filled.Celebration
    Mood.NOSTALGIA -> Icons.Filled.History
}

@Composable
private fun MoodCard(mood: Mood, genreCount: Int, modifier: Modifier, onPlay: () -> Unit, onEdit: () -> Unit) {
    Card(modifier = modifier.clickable(onClick = onPlay)) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(mood.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "Choisir les genres de ${mood.label}", modifier = Modifier.size(18.dp))
                }
            }
            Text(mood.label, style = MaterialTheme.typography.titleMedium)
            Text(
                if (genreCount == 0) "Toute la bibliothèque" else if (genreCount == 1) "1 genre" else "$genreCount genres",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MoodEditorDialog(mood: Mood, libraryGenres: List<String>, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(MoodStore.genres(context, mood, libraryGenres).toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(mood.label) },
        text = {
            Column {
                Text(
                    "Genres utilisés par cette radio. Sans aucun genre, elle puise dans toute la bibliothèque.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(libraryGenres, key = { it }) { genre ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (genre in selected) selected - genre else selected + genre
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = genre in selected, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
                            Text(genre, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { MoodStore.save(context, mood, selected); onSaved() }) { Text("Enregistrer") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { MoodStore.reset(context, mood); onSaved() }) { Text("Par défaut") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}
