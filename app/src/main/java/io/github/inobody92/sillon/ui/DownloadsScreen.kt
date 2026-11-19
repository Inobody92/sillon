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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.Downloads
import io.github.inobody92.sillon.data.OfflineCollection
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.data.Playlist
import io.github.inobody92.sillon.data.Song
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.launch

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f Go".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%d Mo".format(bytes shr 20)
    bytes >= 1L shl 10 -> "%d Ko".format(bytes shr 10)
    else -> "$bytes o"
}

/**
 * Téléchargements. [client] est null quand le NAS est injoignable : on ne propose alors que la lecture
 * et la suppression, et [onRetry] permet de retenter la connexion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    client: SynoClient?,
    onClose: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by OfflineStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<OfflineCollection?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text("Hors-ligne") },
                navigationIcon = {
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
                actions = {
                    if (state.collections.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Tout supprimer")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            if (onRetry != null) {
                item(key = "offline-banner") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CloudOff, contentDescription = null)
                                Text(
                                    "  NAS injoignable",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                "Seuls les titres téléchargés sont disponibles.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                            )
                            Button(onClick = onRetry) { Text("Réessayer la connexion") }
                        }
                    }
                }
            }
            item(key = "summary") {
                Text(
                    "${state.songs.size} titre${if (state.songs.size > 1) "s" else ""} · ${formatBytes(state.bytes)}" +
                        if (state.pendingCount > 0) " · ${state.pendingCount} en attente" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (state.collections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Rien n'est téléchargé.\nOuvre un album ou une playlist, puis choisis « Télécharger ».",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(state.collections, key = { it.id }) { c ->
                CollectionRow(
                    c,
                    downloaded = state.downloadedCount(c),
                    onPlay = {
                        val songs = state.downloadedSongs(c)
                        if (songs.isEmpty()) onMessage("Rien de téléchargé pour l'instant") else onPlay(songs, 0)
                    },
                    onSync = if (client != null && c.type == "playlist") {
                        {
                            scope.launch {
                                try {
                                    Downloads.pinPlaylist(context, client, Playlist(c.id.removePrefix("playlist:"), c.name))
                                    onMessage("« ${c.name} » mise à jour")
                                } catch (e: Exception) {
                                    onMessage(e.message ?: "Mise à jour impossible")
                                }
                            }
                        }
                    } else null,
                    onDelete = { confirmDelete = c },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Tout supprimer ?") },
            text = { Text("Les ${state.songs.size} titres téléchargés (${formatBytes(state.bytes)}) seront effacés de ce téléphone. Ils restent sur le NAS.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; OfflineStore.clearAll() }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Supprimer le téléchargement ?") },
            text = { Text("« ${c.name} » sera effacé de ce téléphone. Les titres utilisés par une autre collection sont conservés.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; Downloads.unpin(context, c.id) }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun CollectionRow(
    c: OfflineCollection,
    downloaded: Int,
    onPlay: () -> Unit,
    onSync: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val total = c.songIds.size
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable(onClick = onPlay)) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${c.subtitle} · $downloaded/$total titres",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "Lire") }
                if (onSync != null) {
                    IconButton(onClick = onSync) { Icon(Icons.Filled.Sync, contentDescription = "Mettre à jour") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer") }
            }
            if (downloaded < total) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else downloaded.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                )
            }
        }
    }
}
