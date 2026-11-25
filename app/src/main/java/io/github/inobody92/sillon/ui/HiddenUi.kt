package io.github.inobody92.sillon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.Hidden
import io.github.inobody92.sillon.data.LibraryIndex
import io.github.inobody92.sillon.data.SynoClient

/** Vrai si le titre est masqué (se met à jour tout seul). */
@Composable
fun rememberIsHidden(songId: String): Boolean {
    val state by Hidden.state.collectAsStateWithLifecycle()
    return songId in state.ids
}

/** Entrée de menu « Masquer ce titre » / « Ne plus masquer » (absente hors connexion : le NAS ne peut pas être mis à jour). */
@Composable
fun HideMenuItem(songId: String, client: SynoClient?, onDone: () -> Unit) {
    if (client == null) return
    val hidden = rememberIsHidden(songId)
    DropdownMenuItem(
        text = { Text(if (hidden) "Ne plus masquer" else "Masquer ce titre") },
        onClick = { onDone(); Hidden.toggle(client, songId) },
    )
}

/** Les titres masqués de ce compte, avec de quoi les démasquer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenScreen(client: SynoClient, onClose: () -> Unit) {
    val state by Hidden.state.collectAsStateWithLifecycle()
    val songs = remember(state.ids) { LibraryIndex.songsByIds(state.ids.toList()) }
    val unknown = state.ids.size - songs.size

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text("Titres masqués") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
            )
            Text(
                if (state.ids.isEmpty()) "Aucun titre masqué."
                else "Ces titres ne sont jamais proposés (mix, radios, aléatoire, voiture) et sont sautés dans les files. " +
                    "La liste est propre à ton compte.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(songs, key = { it.id }) { song ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${song.artist} · ${song.album}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { Hidden.unhide(client, song.id) }) { Text("Démasquer") }
                    }
                }
                if (unknown > 0) {
                    item(key = "unknown") {
                        Text(
                            plural(unknown, "titre") + " masqué${if (unknown > 1) "s" else ""} absent${if (unknown > 1) "s" else ""} de la bibliothèque locale.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
