package io.github.inobody92.sillon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.inobody92.sillon.data.Suggestion
import io.github.inobody92.sillon.data.SuggestionKind

/** « Découvertes du jour » : quelques suggestions à télécharger d'un geste, ou à écarter. */
@Composable
fun DiscoverySection(
    suggestions: List<Suggestion>,
    imageLoader: ImageLoader,
    onDownload: (Suggestion) -> Unit,
    onDismiss: (Suggestion) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.padding(top = 24.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Découvertes du jour", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Rien n'est téléchargé sans ton accord.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.IconButton(onClick = onRefresh) {
                androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = "D'autres suggestions")
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 16.dp)) {
            items(suggestions, key = { it.key }) { SuggestionCard(it, imageLoader, onDownload, onDismiss) }
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, imageLoader: ImageLoader, onDownload: (Suggestion) -> Unit, onDismiss: (Suggestion) -> Unit) {
    Card(Modifier.width(172.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CoverImage(s.cover, imageLoader, null, Modifier.size(148.dp).clip(RoundedCornerShape(12.dp)))
            Text(
                when (s.kind) {
                    SuggestionKind.NEW_RELEASE -> "NOUVEAUTÉ"
                    SuggestionKind.RELATED -> "POUR TOI"
                    SuggestionKind.UNDERGROUND -> "PÉPITE"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.kind == SuggestionKind.NEW_RELEASE) {
                Text(s.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                s.reason + (s.fans?.takeIf { s.kind == SuggestionKind.UNDERGROUND }?.let { " · ${formatFans(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            // Les deux boutons l'un sous l'autre : la carte est trop étroite pour les mettre côte à côte.
            Button(onClick = { onDownload(s) }, modifier = Modifier.fillMaxWidth()) {
                Text("Télécharger")
            }
            TextButton(onClick = { onDismiss(s) }, modifier = Modifier.fillMaxWidth()) { Text("Non merci") }
        }
    }
}

internal fun formatFans(fans: Long): String = when {
    fans >= 1_000_000 -> "%.1f M de fans".format(fans / 1_000_000.0).replace('.', ',')
    fans >= 1_000 -> "${fans / 1_000} k fans"
    else -> "$fans fans"
}
