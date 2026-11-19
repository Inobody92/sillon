package io.github.inobody92.sillon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.inobody92.sillon.data.RemoteFinished
import io.github.inobody92.sillon.data.RemoteTask
import kotlinx.coroutines.delay

/** Suivi en direct des téléchargements du serveur : avancement, morceau en cours, débit, temps restant, historique. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadProgressScreen(vm: AddMusicViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Relecture toutes les 2 s tant que l'écran est visible (la file ne dépend que du serveur).
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshQueue()
                delay(2_000)
            }
        }
    }
    val queue = state.queue

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text("Suivi des téléchargements") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.queueError != null) {
                    item(key = "error") {
                        Text("${state.queueError}. Nouvelle tentative en cours…", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (queue == null) {
                    if (state.queueError == null) item(key = "loading") { Text("Chargement…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    if (queue.pausedUntil != null) {
                        item(key = "paused") {
                            Text("Le serveur a mis la file en pause ; elle reprendra toute seule.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (queue.tasks.isEmpty()) {
                        item(key = "idle") {
                            Text("Aucun téléchargement en cours.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    } else {
                        item(key = "summary") {
                            val waiting = queue.tasks.count { !it.running }
                            Text(
                                plural(queue.tasks.count { it.running }, "en cours") + if (waiting > 0) " · $waiting en attente" else "",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(queue.tasks, key = { "task-${it.number}" }) { TaskCard(it, onCancel = vm::cancel) }
                    }
                    if (queue.finished.isNotEmpty()) {
                        item(key = "done-title") {
                            Text("Terminés récemment", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                        }
                        items(queue.finished, key = { "done-${it.number}@${it.finishedAt}" }) { FinishedRow(it, queue.finished.firstOrNull()?.finishedAt) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: RemoteTask, onCancel: (RemoteTask) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(task.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (task.running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { (task.percent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
                    Text("${task.percent.toInt()} %", style = MaterialTheme.typography.labelLarge)
                }
                // L'étape (téléchargement, conversion, classement…) : sans elle, 0 % pendant plusieurs secondes ressemble à une panne.
                Text(task.state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                if (task.total > 1) Text("${task.done} sur ${plural(task.total, "titre")}", style = MaterialTheme.typography.bodyMedium)
                task.currentTrack?.let {
                    Text("En cours : $it", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val details = listOfNotNull(
                    task.speedBps?.let { formatSpeed(it) },
                    task.etaSec?.let { "encore ${formatEta(it)}" },
                    if (task.errors > 0) plural(task.errors, "erreur") else null,
                ).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("En attente · position ${(task.position ?: 0) + 1} dans la file", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { onCancel(task) }) { Text(if (task.running) "Arrêter" else "Retirer de la file") }
        }
    }
}

@Composable
private fun FinishedRow(f: RemoteFinished, @Suppress("UNUSED_PARAMETER") newest: Long?) {
    Column(Modifier.fillMaxWidth()) {
        Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when (f.status) {
                "echec" -> "Échec" + (f.error?.let { " : $it" } ?: "")
                "annule" -> "Annulé" + if (f.downloaded > 0) " · ${plural(f.downloaded, "titre")} conservé" + (if (f.downloaded > 1) "s" else "") else ""
                else -> if (f.downloaded > 0) plural(f.downloaded, "titre") + " ajouté" + (if (f.downloaded > 1) "s" else "") +
                    (if (f.errors > 0) " · ${plural(f.errors, "erreur")}" else "")
                else "Déjà dans la bibliothèque"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (f.status == "echec") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun formatEta(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min ${"%02d".format(seconds % 60)} s"
    else -> "${seconds / 3600} h ${"%02d".format(seconds % 3600 / 60)} min"
}

/** « 1,4 Mo/s » : une décimale, car le débit d'un téléchargement se lit en dizaines de Ko ou en Mo. */
internal fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 -> "%.1f Mo/s".format(bytesPerSecond / 1_000_000.0).replace('.', ',')
    bytesPerSecond >= 1_000 -> "${bytesPerSecond / 1_000} Ko/s"
    else -> "$bytesPerSecond o/s"
}
