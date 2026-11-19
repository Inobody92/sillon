package io.github.inobody92.sillon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.ImageLoader
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.SynoClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    client: SynoClient,
    imageLoader: ImageLoader,
    onOpenAlbum: (Album) -> Unit,
    title: String = "Albums",
    artist: String? = null,
    genre: String? = null,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val vm: AlbumsViewModel = viewModel(
        key = "albums/${client.creds.url}/${client.creds.account}/$artist/$genre",
        factory = viewModelFactory { initializer { AlbumsViewModel(client, artist, genre) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
                actions = {
                    if (onSettings != null) {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Réglages")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null && state.albums.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::retry) { Text("Réessayer") }
                }
                state.albums.isEmpty() && state.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (header != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header() }
                    }
                    itemsIndexed(state.albums, key = { i, a -> "$i/${a.name}/${a.albumArtist}" }) { index, album ->
                        if (index >= state.albums.size - 12) {
                            LaunchedEffect(state.albums.size) { vm.loadMore() }
                        }
                        AlbumCard(album, client, imageLoader, onClick = { onOpenAlbum(album) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: Album, client: SynoClient, imageLoader: ImageLoader, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        CoverImage(
            url = client.coverUrl(album, withSid = true),
            imageLoader = imageLoader,
            contentDescription = album.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            album.displayArtist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
