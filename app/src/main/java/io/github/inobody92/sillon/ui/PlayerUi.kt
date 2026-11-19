package io.github.inobody92.sillon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import io.github.inobody92.sillon.playback.MediaItems
import io.github.inobody92.sillon.playback.PlayerController
import io.github.inobody92.sillon.playback.PlayerUi
import io.github.inobody92.sillon.playback.SleepState
import io.github.inobody92.sillon.data.AppSettings
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Position de lecture rafraîchie deux fois par seconde tant qu'on affiche le lecteur. */
@Composable
private fun rememberPosition(player: PlayerController, active: Boolean): Long {
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        while (active) {
            position = player.positionMs()
            delay(500)
        }
    }
    return position
}

@Composable
fun MiniPlayer(
    ui: PlayerUi,
    player: PlayerController,
    imageLoader: ImageLoader,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = rememberPosition(player, active = ui.isPlaying)
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        LinearProgressIndicator(
            progress = { if (ui.durationMs > 0) (position.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(
                url = ui.artwork,
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(ui.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ui.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = player::togglePlay) {
                Icon(
                    if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (ui.isPlaying) "Pause" else "Lecture",
                )
            }
            IconButton(onClick = player::next, enabled = ui.hasNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Suivant")
            }
        }
    }
}

@Composable
fun NowPlayingScreen(
    ui: PlayerUi,
    player: PlayerController,
    imageLoader: ImageLoader,
    client: SynoClient?,
    sleep: SleepState,
    onSetSleep: (mode: String, minutes: Int) -> Unit,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onAddToPlaylist: ((mediaId: String) -> Unit)?,
) {
    var showSleepDialog by remember { mutableStateOf(false) }
    val position = rememberPosition(player, active = true)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Réduire")
                }
            }
            Spacer(Modifier.weight(1f))
            val useTurntable by AppSettings.turntable.collectAsStateWithLifecycle()
            if (useTurntable) {
                Turntable(
                    artwork = ui.artwork,
                    imageLoader = imageLoader,
                    playing = ui.isPlaying,
                    positionMs = position,
                    durationMs = ui.durationMs,
                    onSeek = player::seekTo,
                    onTogglePlay = player::togglePlay,
                )
            } else {
                CoverImage(
                    url = ui.artwork,
                    imageLoader = imageLoader,
                    contentDescription = ui.album,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ui.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (ui.mediaId.isNotBlank()) LikeButton(ui.mediaId, client)
            }
            if (client != null && ui.mediaId.isNotBlank()) {
                RatingRow(client, ui.mediaId)
            }
            Spacer(Modifier.height(8.dp))

            val duration = ui.durationMs.coerceAtLeast(1L)
            Slider(
                value = if (dragging) dragValue else position.coerceAtMost(duration).toFloat(),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    player.seekTo(dragValue.toLong())
                    dragging = false
                },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = if (dragging) dragValue.toLong() else position
                Text(formatTime(shown), style = MaterialTheme.typography.bodySmall)
                Text(formatTime(ui.durationMs), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = player::previous, enabled = ui.hasPrevious, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Précédent", modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = player::togglePlay, modifier = Modifier.size(80.dp)) {
                    Icon(
                        if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (ui.isPlaying) "Pause" else "Lecture",
                        modifier = Modifier.size(56.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = player::next, enabled = ui.hasNext, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Suivant", modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = player::toggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = if (ui.shuffle) "Aléatoire activé" else "Aléatoire désactivé",
                        tint = if (ui.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = player::cycleRepeat) {
                    val active = ui.repeatMode != Player.REPEAT_MODE_OFF
                    Icon(
                        if (ui.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Répéter",
                        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenQueue) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "File d'attente")
                }
                IconButton(onClick = { showSleepDialog = true }) {
                    Icon(
                        Icons.Filled.Bedtime,
                        contentDescription = "Minuteur de sommeil",
                        tint = if (sleep.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onAddToPlaylist != null) {
                    IconButton(onClick = { onAddToPlaylist(ui.mediaId) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Ajouter à une playlist")
                    }
                }
            }
            if (sleep.active) {
                SleepStatus(sleep)
            }
            Spacer(Modifier.weight(1f))
        }
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            current = sleep,
            onDismiss = { showSleepDialog = false },
            onSelect = { mode, minutes ->
                showSleepDialog = false
                onSetSleep(mode, minutes)
            },
        )
    }
}

/** Cinq étoiles pour noter le titre en cours ; toucher l'étoile déjà active retire la note. */
@Composable
private fun RatingRow(client: SynoClient, mediaId: String) {
    var rating by remember(mediaId) { mutableIntStateOf(0) }
    var known by remember(mediaId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(mediaId) {
        runCatching { client.rating(mediaId) }.onSuccess { rating = it; known = true }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        for (star in 1..5) {
            IconButton(
                enabled = known,
                onClick = {
                    val previous = rating
                    val next = if (previous == star) 0 else star
                    rating = next
                    scope.launch {
                        // En cas d'échec, on remet l'ancienne valeur plutôt que d'afficher une note fausse.
                        runCatching { client.setRating(mediaId, next) }.onFailure { rating = previous }
                    }
                },
            ) {
                Icon(
                    if (star <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "$star étoile${if (star > 1) "s" else ""}",
                    tint = if (star <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Ce que fera le minuteur : compte à rebours, ou arrêt à la fin du titre / de la file. */
@Composable
private fun SleepStatus(sleep: SleepState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleep) {
        while (sleep.mode == MediaItems.SLEEP_TIME) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val text = when (sleep.mode) {
        MediaItems.SLEEP_TIME -> "Arrêt dans " + formatTime((sleep.endAtMs - now).coerceAtLeast(0))
        MediaItems.SLEEP_TRACK -> "Arrêt à la fin du titre"
        else -> "Arrêt à la fin de la file"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SleepTimerDialog(current: SleepState, onDismiss: () -> Unit, onSelect: (String, Int) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        var minutes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Autre durée") },
            text = {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                    label = { Text("Minutes (1 à 600)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                val value = minutes.toIntOrNull()
                TextButton(
                    enabled = value != null && value in 1..600,
                    onClick = { onSelect(MediaItems.SLEEP_TIME, value ?: 0) },
                ) { Text("Démarrer") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Minuteur de sommeil") },
        text = {
            Column {
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    SleepOption("Dans $m minutes") { onSelect(MediaItems.SLEEP_TIME, m) }
                }
                SleepOption("Autre durée…") { custom = true }
                SleepOption("À la fin du titre") { onSelect(MediaItems.SLEEP_TRACK, 0) }
                SleepOption("À la fin de la file") { onSelect(MediaItems.SLEEP_QUEUE, 0) }
                if (current.active) {
                    SleepOption("Annuler le minuteur") { onSelect(MediaItems.SLEEP_OFF, 0) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun SleepOption(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}
