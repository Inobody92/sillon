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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.Hidden
import io.github.inobody92.sillon.data.RuleKind
import io.github.inobody92.sillon.data.albumKey
import io.github.inobody92.sillon.data.normalizeKey
import io.github.inobody92.sillon.data.HiddenRules
import io.github.inobody92.sillon.data.matchesRule
import io.github.inobody92.sillon.data.LibraryIndex
import io.github.inobody92.sillon.data.SynoClient

/** Vrai si le titre est masqué (se met à jour tout seul). */
@Composable
fun rememberIsHidden(songId: String): Boolean {
    val state by Hidden.state.collectAsStateWithLifecycle()
    return songId in state.ids
}

/** Vrai si ce titre est masqué par lui-même ou par une règle (artiste, album) : sert à griser sa ligne. */
@Composable
fun rememberIsSongHidden(song: io.github.inobody92.sillon.data.Song): Boolean {
    val state by Hidden.state.collectAsStateWithLifecycle()
    val rules by HiddenRules.state.collectAsStateWithLifecycle()
    return song.id in state.ids || matchesRule(rules.values.filter { it.on }, song.artist, song.albumArtist, song.album)
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

/**
 * Menu « masquer cet album / cet artiste » (une règle : elle vaut aussi pour les titres ajoutés plus tard), avec
 * confirmation avant de masquer. [extraItems] ajoute des entrées en tête (par exemple « Masquer ce titre »).
 */
@Composable
fun HideRulesMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    album: Album?,
    artistName: String?,
    extraItems: @Composable () -> Unit = {},
    onHidden: () -> Unit = {},
) {
    val rules by HiddenRules.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        extraItems()
        if (album != null && album.name.isNotBlank()) {
            val on = rules["${RuleKind.ALBUM.id}:${albumKey(album.name, album.displayArtist)}"]?.on == true
            DropdownMenuItem(
                text = { Text(if (on) "Ne plus masquer l'album" else "Masquer l'album « ${album.name} »") },
                onClick = {
                    onDismiss()
                    if (on) HiddenRules.unhideAlbum(album) else pending = "l'album « ${album.name} »" to { HiddenRules.hideAlbum(album) }
                },
            )
        }
        if (!artistName.isNullOrBlank()) {
            val on = rules["${RuleKind.ARTIST.id}:${normalizeKey(artistName)}"]?.on == true
            DropdownMenuItem(
                text = { Text(if (on) "Ne plus masquer l'artiste" else "Masquer l'artiste « $artistName »") },
                onClick = {
                    onDismiss()
                    if (on) HiddenRules.unhideArtist(artistName) else pending = "l'artiste « $artistName »" to { HiddenRules.hideArtist(artistName) }
                },
            )
        }
    }

    pending?.let { (what, apply) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Masquer $what ?") },
            text = {
                Text(
                    "Tous ses titres, y compris ceux ajoutés plus tard, ne seront plus proposés ni lus (mix, radios, aléatoire, voiture). " +
                        "Tu pourras annuler dans Réglages → Titres masqués.",
                )
            },
            confirmButton = { TextButton(onClick = { apply(); pending = null; onHidden() }) { Text("Masquer") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Annuler") } },
        )
    }
}

/** Les titres masqués de ce compte, avec de quoi les démasquer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenScreen(client: SynoClient, onClose: () -> Unit) {
    val state by Hidden.state.collectAsStateWithLifecycle()
    val rules by HiddenRules.state.collectAsStateWithLifecycle()
    val activeRules = remember(rules) { rules.values.filter { it.on }.sortedWith(compareBy({ it.kind }, { it.name.lowercase() })) }
    val songs = remember(state.ids) { LibraryIndex.songsByIds(state.ids.toList()) }
    val unknown = state.ids.size - songs.size

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text("Titres masqués") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
            )
            Text(
                if (state.ids.isEmpty() && activeRules.isEmpty()) "Aucun titre masqué."
                else "Ces titres ne sont jamais proposés (mix, radios, aléatoire, voiture) et sont sautés dans les files. " +
                    "La liste est propre à ton compte.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (activeRules.isNotEmpty()) {
                    item(key = "rules-title") {
                        Text("Artistes et albums masqués", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    items(activeRules, key = { it.id }) { rule ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (rule.kind == RuleKind.ARTIST) "Artiste · tous ses titres" else "Album · ${rule.artist}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { HiddenRules.remove(rule) }) { Text("Retirer") }
                        }
                    }
                    if (songs.isNotEmpty()) item(key = "songs-title") {
                        Text("Titres masqués", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
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
