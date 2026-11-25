package io.github.inobody92.sillon.ui

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.Affinity
import io.github.inobody92.sillon.data.SynoClient
import io.github.inobody92.sillon.data.TasteStore

/** Ce que l'application a compris de tes goûts : moyenne des notes par artiste et par genre. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasteScreen(client: SynoClient, onClose: () -> Unit) {
    val state by TasteStore.state.collectAsStateWithLifecycle()
    var showGenres by rememberSaveable { mutableStateOf(false) }
    val profile = state.profile
    val rows = if (showGenres) profile.genres() else profile.artists()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text("Mes goûts") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
                actions = {
                    if (state.loading) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { TasteStore.refresh(client, force = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Relire les notes du NAS")
                    }
                },
            )
            Text(
                if (profile.ratedCount == 0) "Aucune note, aucun like pour l'instant."
                else "${plural(profile.ratedCount, "titre noté ou liké")} · moyenne ${"%.1f".format(profile.globalMean).replace('.', ',')} ★",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "Les notes, les likes (= 5 ★), les titres masqués (= 1 ★) et ce que tu écoutes jusqu'au bout ou passes vite " +
                    "dessinent tes goûts. Le mix du jour et les radios favorisent les mieux notés et reviennent moins souvent " +
                    "sur les titres déjà proposés.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showGenres, onClick = { showGenres = false }, label = { Text("Artistes") })
                FilterChip(selected = showGenres, onClick = { showGenres = true }, label = { Text("Genres") })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(rows, key = { (if (showGenres) "g:" else "a:") + it.name }) { AffinityRow(it) }
            }
        }
    }
}

@Composable
private fun AffinityRow(a: Affinity) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.name.ifBlank { "(sans nom)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = listOfNotNull(
                if (a.ratedCount > 0) plural(a.ratedCount, "titre") + " noté" + (if (a.ratedCount > 1) "s" else "") + " ou liké" + (if (a.ratedCount > 1) "s" else "") else null,
                if (a.listenedCount > 0) plural(a.listenedCount, "titre") + " déduit" + (if (a.listenedCount > 1) "s" else "") + " de l'écoute" else null,
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "%.1f ★".format(a.average).replace('.', ','),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
