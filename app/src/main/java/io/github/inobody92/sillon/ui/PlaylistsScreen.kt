package io.github.inobody92.sillon.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.inobody92.sillon.data.Likes
import io.github.inobody92.sillon.data.Playlist
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaylistsState(
    val playlists: List<Playlist> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Partagé entre l'onglet Playlists et les boîtes « Ajouter à une playlist ». */
class PlaylistsViewModel(private val client: SynoClient) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistsState())
    val state: StateFlow<PlaylistsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _state.value = try {
                PlaylistsState(playlists = client.playlists(), loading = false)
            } catch (e: Exception) {
                _state.value.copy(loading = false, error = e.message ?: "Erreur de chargement")
            }
        }
    }

    /** Crée une playlist (éventuellement remplie) ; [onResult] reçoit null si tout va bien, sinon le message d'erreur. */
    fun create(name: String, songIds: List<String>, onResult: (String?) -> Unit) = run(onResult) {
        client.createPlaylist(name.trim(), songIds)
    }

    fun addSongs(playlistId: String, songIds: List<String>, onResult: (String?) -> Unit) = run(onResult) {
        client.addToPlaylist(playlistId, songIds)
        Likes.onPlaylistChanged(client, playlistId)
    }

    fun rename(playlistId: String, newName: String, onResult: (String?) -> Unit) = run(onResult) {
        client.renamePlaylist(playlistId, newName.trim())
    }

    fun delete(playlistId: String, onResult: (String?) -> Unit) = run(onResult) {
        client.deletePlaylist(playlistId)
        Likes.onPlaylistChanged(client, playlistId)
    }

    private fun run(onResult: (String?) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            val error = try {
                block()
                null
            } catch (e: Exception) {
                e.message ?: "Erreur inconnue"
            }
            onResult(error)
            if (error == null) refresh()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    vm: PlaylistsViewModel,
    onOpen: (Playlist) -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // La liste est partagée avec le reste de l'app (« Titres likés » est créée ailleurs) : on la relit à chaque affichage.
    LaunchedEffect(Unit) { vm.refresh() }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = { TopAppBar(title = { Text("Playlists") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Nouvelle playlist")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null && state.playlists.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::refresh) { Text("Réessayer") }
                }
                state.loading && state.playlists.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.playlists.isEmpty() -> Text(
                    "Aucune playlist pour l'instant.\nAppuie sur + pour en créer une.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.playlists, key = { it.id }) { playlist ->
                        PlaylistRow(
                            playlist,
                            onClick = { onOpen(playlist) },
                            onRename = { renaming = playlist },
                            onDelete = { deleting = playlist },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "Nouvelle playlist",
            confirmLabel = "Créer",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                vm.create(name, emptyList()) { err -> onMessage(err ?: "Playlist « ${name.trim()} » créée") }
            },
        )
    }
    renaming?.let { target ->
        NameDialog(
            title = "Renommer la playlist",
            confirmLabel = "Renommer",
            initial = target.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                vm.rename(target.id, name) { err -> onMessage(err ?: "Playlist renommée") }
            },
        )
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer la playlist ?") },
            text = { Text("« ${target.name} » sera supprimée du NAS. Les morceaux ne sont pas touchés.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    vm.delete(target.id) { err -> onMessage(err ?: "Playlist supprimée") }
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (playlist.isShared) {
                Text(
                    "Partagée",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Curseur placé en fin de texte, pour compléter un nom existant directement.
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val name = field.text
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                label = { Text("Nom") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
