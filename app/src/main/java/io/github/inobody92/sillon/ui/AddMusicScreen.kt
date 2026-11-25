package io.github.inobody92.sillon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import io.github.inobody92.sillon.data.DownloadRequest
import io.github.inobody92.sillon.data.DownloadRequestLog
import io.github.inobody92.sillon.data.DownloadServerClient
import io.github.inobody92.sillon.data.RemoteFinished
import io.github.inobody92.sillon.data.DownloadServerException
import io.github.inobody92.sillon.data.LibraryIndex
import io.github.inobody92.sillon.data.QueuedTask
import io.github.inobody92.sillon.data.RemoteArtist
import io.github.inobody92.sillon.data.RemoteQueue
import io.github.inobody92.sillon.data.RemoteSong
import io.github.inobody92.sillon.data.RemoteTask
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AddMode(val label: String) { SONG("Chanson"), ARTIST("Artiste"), LINK("Lien") }

data class AddMusicState(
    val mode: AddMode = AddMode.SONG,
    val songs: List<RemoteSong> = emptyList(),
    val artists: List<RemoteArtist> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
    val queue: RemoteQueue? = null,
    val queueError: String? = null,
)

class AddMusicViewModel(
    private val server: DownloadServerClient,
    private val library: SynoClient,
    /** Appelé avec le numéro de chaque demande acceptée par le serveur (pour la suivre en arrière-plan). */
    private val onQueued: (Int) -> Unit = {},
    /** Mémoire des demandes, pour pouvoir relancer celles qui ont échoué ou ont été annulées. */
    private val requests: DownloadRequestLog? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(AddMusicState())
    val state: StateFlow<AddMusicState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Téléchargements terminés déjà annoncés. Null tant que la première lecture n'a pas eu lieu (on n'annonce pas l'ancien). */
    private var seen: MutableSet<String>? = null

    fun setMode(mode: AddMode) = _state.update { AddMusicState(mode = mode, queue = it.queue, queueError = it.queueError) }

    fun search(query: String) {
        val q = query.trim()
        if (q.length < 2) {
            _state.update { it.copy(error = "Tape au moins 2 caractères") }
            return
        }
        val mode = _state.value.mode
        _state.update { it.copy(searching = true, error = null) }
        viewModelScope.launch {
            try {
                when (mode) {
                    AddMode.SONG -> server.searchSongs(q).let { r -> _state.update { it.copy(songs = r, artists = emptyList()) } }
                    AddMode.ARTIST -> server.searchArtists(q).let { r -> _state.update { it.copy(artists = r, songs = emptyList()) } }
                    AddMode.LINK -> Unit
                }
                _state.update { it.copy(searching = false, searched = true) }
            } catch (e: DownloadServerException) {
                _state.update { it.copy(searching = false, error = e.message) }
            }
        }
    }

    fun downloadSong(song: RemoteSong) = enqueue(DownloadRequest.Song(song.id))
    fun downloadArtist(artist: RemoteArtist, count: Int) = enqueue(DownloadRequest.Artist(artist.id, count))
    fun downloadLink(url: String) = enqueue(DownloadRequest.Link(url.trim()))

    /** Vrai si on se souvient de la demande derrière [finished] et qu'elle n'a pas abouti : un « Réessayer » a un sens. */
    fun canRetry(finished: RemoteFinished): Boolean =
        finished.status != "termine" && requests?.find(finished.number, finished.name) != null

    fun retry(finished: RemoteFinished) {
        val request = requests?.find(finished.number, finished.name) ?: return
        enqueue(request, replacing = finished.number)
    }

    private fun enqueue(request: DownloadRequest, replacing: Int? = null) {
        viewModelScope.launch {
            try {
                val task = when (request) {
                    is DownloadRequest.Song -> server.downloadSong(request.id)
                    is DownloadRequest.Artist -> server.downloadArtist(request.id, request.count)
                    is DownloadRequest.Link -> server.downloadLink(request.url)
                }
                requests?.remember(task.number, task.name, request)
                // L'ancienne tentative est remplacée par la nouvelle : plus de bouton ni de bandeau pour elle.
                replacing?.let { requests?.forget(it) }
                onQueued(task.number)
                _messages.emit(
                    if (task.position == 0) "Téléchargement lancé : ${task.name}"
                    else "Ajouté à la file (position ${task.position}) : ${task.name}",
                )
                refreshQueue()
            } catch (e: DownloadServerException) {
                _messages.emit(e.message ?: "Échec de la demande")
            }
        }
    }

    fun cancel(task: RemoteTask) {
        viewModelScope.launch {
            try {
                _messages.emit(server.cancel(task.number).ifBlank { "Annulation demandée" })
                refreshQueue()
            } catch (e: DownloadServerException) {
                _messages.emit(e.message ?: "Annulation impossible")
            }
        }
    }

    suspend fun refreshQueue() {
        try {
            val queue = server.queue()
            announceFinished(queue)
            _state.update { it.copy(queue = queue, queueError = null) }
        } catch (e: DownloadServerException) {
            _state.update { it.copy(queueError = e.message) }
        }
    }

    private fun announceFinished(queue: RemoteQueue) {
        val keys = queue.finished.associateBy { "${it.number}@${it.finishedAt}" }
        val known = seen
        if (known == null) {
            seen = keys.keys.toMutableSet()
            return
        }
        var gotMusic = false
        keys.forEach { (key, f) ->
            if (!known.add(key)) return@forEach
            _messages.tryEmit(
                when (f.status) {
                    "echec" -> "Échec : ${f.name}" + (f.error?.let { " ($it)" } ?: "")
                    "annule" -> "Annulé : ${f.name}"
                    else -> if (f.downloaded > 0) "Terminé : ${f.name} · ${plural(f.downloaded, "titre")}" else "${f.name} : déjà dans la bibliothèque"
                },
            )
            if (f.downloaded > 0) gotMusic = true
        }
        if (gotMusic) scheduleLibraryRefresh()
    }

    /** Le NAS indexe les nouveaux fichiers avec un peu de retard : on revérifie la bibliothèque deux fois. */
    private fun scheduleLibraryRefresh() {
        viewModelScope.launch {
            delay(30_000)
            LibraryIndex.ensure(library)
            delay(120_000)
            LibraryIndex.ensure(library)
        }
    }
}

internal fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMusicScreen(
    vm: AddMusicViewModel,
    imageLoader: ImageLoader,
    polling: Boolean,
    onOpenProgress: () -> Unit,
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var choosing by remember { mutableStateOf<RemoteArtist?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(vm) { vm.messages.collect(onMessage) }
    // La file est relue toutes les 2,5 s tant que l'écran est visible.
    LaunchedEffect(vm, lifecycle, polling) {
        if (!polling) return@LaunchedEffect // l'écran de suivi, au-dessus, relit déjà la file
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshQueue()
                delay(2_500)
            }
        }
    }

    val submit = {
        if (state.mode == AddMode.LINK) {
            if (query.isNotBlank()) { vm.downloadLink(query); query = "" }
        } else vm.search(query)
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text("Ajouter de la musique") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
                actions = {
                    IconButton(onClick = onOpenProgress) { Icon(Icons.Filled.Downloading, contentDescription = "Suivi des téléchargements") }
                },
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                item(key = "modes") {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AddMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.mode == mode,
                                onClick = {
                                    // Le texte saisi est conservé. Entre chanson et artiste on relance la recherche avec lui ;
                                    // le mode lien garde le texte tel quel (c'est peut-être un lien collé).
                                    val betweenSearches = state.mode != AddMode.LINK && mode != AddMode.LINK
                                    vm.setMode(mode)
                                    if (betweenSearches && query.trim().length >= 2) vm.search(query)
                                },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                }
                item(key = "field") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = {
                            Text(
                                when (state.mode) {
                                    AddMode.SONG -> "Titre, avec ou sans l'artiste"
                                    AddMode.ARTIST -> "Nom de l'artiste"
                                    AddMode.LINK -> "Lien d'une vidéo ou d'une playlist"
                                },
                            )
                        },
                        singleLine = true,
                        isError = state.error != null,
                        supportingText = state.error?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (state.mode == AddMode.LINK) KeyboardType.Uri else KeyboardType.Text,
                            imeAction = if (state.mode == AddMode.LINK) ImeAction.Done else ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }),
                        trailingIcon = {
                            IconButton(onClick = { submit() }, enabled = !state.searching) {
                                Icon(
                                    if (state.mode == AddMode.LINK) Icons.Filled.Download else Icons.Filled.Search,
                                    contentDescription = if (state.mode == AddMode.LINK) "Télécharger" else "Chercher",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.searching) {
                    item(key = "searching") {
                        Text("Recherche…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                } else if (state.searched && state.songs.isEmpty() && state.artists.isEmpty() && state.mode != AddMode.LINK) {
                    item(key = "none") {
                        Text("Aucun résultat. Essaie avec le nom de l'artiste en plus.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                }
                items(state.songs, key = { "song-${it.id}" }) { SongResult(it, imageLoader) { vm.downloadSong(it) } }
                items(state.artists, key = { "artist-${it.id}" }) { ArtistResult(it, imageLoader) { choosing = it } }

                // Échecs de demandes lancées depuis l'application et encore relançables : visibles ici, sans ouvrir le suivi.
                val failed = state.queue?.finished.orEmpty().filter { it.status == "echec" && vm.canRetry(it) }
                items(failed, key = { "failed-${it.number}@${it.finishedAt}" }) { f ->
                    FailureCard(f, onRetry = { vm.retry(f) }, onOpenProgress = onOpenProgress)
                }
                item(key = "queue-banner") { QueueBanner(state, onOpenProgress) }
            }
        }
    }

    choosing?.let { artist ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(artist.name) },
            text = { Text("Les titres déjà présents dans la bibliothèque sont sautés.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { choosing = null; vm.downloadArtist(artist, 0) }) { Text("Toute la discographie") }
                    TextButton(onClick = { choosing = null; vm.downloadArtist(artist, 10) }) { Text("Les 10 titres les plus populaires") }
                    TextButton(onClick = { choosing = null }) { Text("Annuler") }
                }
            },
        )
    }
}

@Composable
private fun FailureCard(f: RemoteFinished, onRetry: () -> Unit, onOpenProgress: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Échec : ${f.name}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                f.error ?: "Le serveur n'a pas donné de raison.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("Réessayer") }
                TextButton(onClick = onOpenProgress) { Text("Voir le suivi") }
            }
        }
    }
}

@Composable
private fun QueueBanner(state: AddMusicState, onOpenProgress: () -> Unit) {
    val tasks = state.queue?.tasks.orEmpty()
    when {
        state.queueError != null -> Text(
            "Suivi indisponible : ${state.queueError}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        tasks.isNotEmpty() -> {
            val running = tasks.firstOrNull { it.running }
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).clickable(onClick = onOpenProgress)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Downloading, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(
                            if (tasks.size == 1) "1 téléchargement en cours" else "${tasks.size} téléchargements (en cours ou en attente)",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            running?.let { "${it.name} · ${it.percent.toInt()} %" } ?: "En attente de démarrage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("Voir le suivi", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SongResult(song: RemoteSong, imageLoader: ImageLoader, onDownload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onDownload).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverImage(song.cover, imageLoader, null, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(song.artist, song.album.ifBlank { null }, song.durationSec?.let { formatTime(it * 1000L) }, song.fans?.let { fansLabel(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Télécharger ${song.title}") }
    }
}

@Composable
private fun ArtistResult(artist: RemoteArtist, imageLoader: ImageLoader, onChoose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onChoose).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverImage(artist.photo, imageLoader, null, Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (artist.isGroup) "Groupe" else artist.realName,
                    artist.albums?.let { "$it album" + if (it > 1) "s" else "" },
                    artist.tracks?.let { "~$it titres" },
                    artist.fans?.let { fansLabel(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onChoose) { Icon(Icons.Filled.Download, contentDescription = "Télécharger ${artist.name}") }
    }
}

private fun fansLabel(n: Long): String = when {
    n >= 1_000_000 -> "%.1f M fans".format(n / 1_000_000.0).replace(".0", "").replace('.', ',')
    n >= 1_000 -> "${n / 1_000} k fans"
    else -> "$n fans"
}
