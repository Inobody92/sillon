package io.github.inobody92.sillon.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.Likes
import io.github.inobody92.sillon.data.SynoClient

/**
 * Cœur « Titres likés ». Hors connexion on ne peut pas modifier la playlist : le cœur s'affiche alors seulement
 * si le titre est liké, sans être cliquable.
 */
@Composable
fun LikeButton(songId: String, client: SynoClient?, modifier: Modifier = Modifier) {
    val likes by Likes.state.collectAsStateWithLifecycle()
    val liked = songId in likes.ids
    if (client == null) {
        if (liked) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Titre liké",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.size(20.dp),
            )
        }
        return
    }
    IconButton(onClick = { Likes.toggle(client, songId) }, modifier = modifier) {
        Icon(
            if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (liked) "Retirer des titres likés" else "Liker",
            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
