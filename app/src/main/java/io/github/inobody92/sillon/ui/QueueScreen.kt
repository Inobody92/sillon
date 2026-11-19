package io.github.inobody92.sillon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.inobody92.sillon.playback.PlayerController
import io.github.inobody92.sillon.playback.PlayerUi
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * File d'attente en cours. Le déplacement est appliqué directement au lecteur (qui fait foi) :
 * pas de copie locale à resynchroniser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    ui: PlayerUi,
    player: PlayerController,
    imageLoader: ImageLoader,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        player.moveInQueue(from.index, to.index)
    }
    // À l'ouverture, on se place sur le morceau en cours.
    LaunchedEffect(Unit) { listState.scrollToItem(ui.currentIndex.coerceAtLeast(0)) }

    Surface(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text("File d'attente") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                // L'identifiant d'un morceau peut apparaître deux fois : la clé inclut la position d'origine.
                itemsIndexed(ui.queue, key = { index, item -> "${item.mediaId}#${index}" }) { index, item ->
                    ReorderableItem(reorderState, key = "${item.mediaId}#${index}") { isDragging ->
                        val current = index == ui.currentIndex
                        Surface(
                            tonalElevation = if (isDragging) 6.dp else 0.dp,
                            shadowElevation = if (isDragging) 6.dp else 0.dp,
                            color = if (current) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { player.skipTo(index) }
                                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CoverImage(
                                    url = item.artwork,
                                    imageLoader = imageLoader,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                )
                                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        item.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (current) {
                                    Icon(
                                        Icons.Filled.GraphicEq,
                                        contentDescription = "En cours de lecture",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(onClick = { player.removeFromQueue(index) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Retirer de la file")
                                }
                                IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
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
