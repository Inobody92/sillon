package io.github.inobody92.sillon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Choisir une playlist existante (ou en créer une) pour y ajouter [songIds]. */
@Composable
fun AddToPlaylistDialog(
    vm: PlaylistsViewModel,
    songIds: List<String>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val count = songIds.size
    val what = if (count == 1) "1 titre" else "$count titres"

    if (creating) {
        NameDialog(
            title = "Nouvelle playlist",
            confirmLabel = "Créer",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                vm.create(name, songIds) { err ->
                    onMessage(err ?: "Playlist « ${name.trim()} » créée avec $what")
                }
                onDismiss()
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter à une playlist") },
        text = {
            Box(Modifier.heightIn(max = 360.dp)) {
                when {
                    state.loading && state.playlists.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> LazyColumn {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    "Nouvelle playlist…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                        items(state.playlists, key = { it.id }) { playlist ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        vm.addSongs(playlist.id, songIds) { err ->
                                            onMessage(err ?: "$what ajouté${if (count > 1) "s" else ""} à « ${playlist.name} »")
                                        }
                                        onDismiss()
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
