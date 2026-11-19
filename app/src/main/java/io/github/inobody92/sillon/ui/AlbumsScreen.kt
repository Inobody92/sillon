package io.github.inobody92.sillon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.SynoClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(client: SynoClient, onLogout: () -> Unit) {
    val vm: AlbumsViewModel = viewModel(
        key = client.creds.url + client.creds.account,
        factory = viewModelFactory { initializer { AlbumsViewModel(client) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageLoader = remember(client) {
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client.http })) }
            .build()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Albums") },
                actions = { TextButton(onClick = onLogout) { Text("Déconnexion") } },
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
                    itemsIndexed(state.albums, key = { i, a -> "$i/${a.name}/${a.albumArtist}" }) { index, album ->
                        if (index >= state.albums.size - 12) {
                            LaunchedEffect(state.albums.size) { vm.loadMore() }
                        }
                        AlbumCard(album, client, imageLoader)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: Album, client: SynoClient, imageLoader: ImageLoader) {
    val context = LocalContext.current
    // Clé de cache sans sid : une reconnexion ne doit pas invalider les pochettes déjà chargées.
    val cacheKey = remember(album) { client.coverUrl(album, withSid = false) }
    val request = remember(album, client.sid) {
        ImageRequest.Builder(context)
            .data(client.coverUrl(album, withSid = true))
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }
    Column(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = album.name,
            contentScale = ContentScale.Crop,
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
