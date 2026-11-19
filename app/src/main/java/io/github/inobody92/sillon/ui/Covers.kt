package io.github.inobody92.sillon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import io.github.inobody92.sillon.data.SynoClient

@Composable
fun rememberImageLoader(client: SynoClient): ImageLoader {
    val context = LocalContext.current
    return remember(client) {
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client.http })) }
            .build()
    }
}

/** Pochette chargée via le client du NAS ; le cache ignore le sid pour survivre aux reconnexions. */
@Composable
fun CoverImage(
    url: String?,
    imageLoader: ImageLoader,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request = remember(url) {
        val key = url?.let(SynoClient::stableKey)
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .build()
    }
    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
