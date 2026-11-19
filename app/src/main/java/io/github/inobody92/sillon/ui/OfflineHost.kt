package io.github.inobody92.sillon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader

/**
 * Écran affiché quand le NAS est injoignable : seuls les titres téléchargés sont lisibles.
 * La lecture passe par le même service que d'habitude, qui lit les fichiers locaux sans réseau.
 */
@Composable
fun OfflineHost(playerViewModel: PlayerViewModel, onRetry: () -> Unit) {
    val context = LocalContext.current
    val player = playerViewModel.controller
    val playerUi by player.ui.collectAsStateWithLifecycle()
    val sleep by player.sleep.collectAsStateWithLifecycle()
    // Les pochettes sont des fichiers locaux : le chargeur d'images standard suffit (pas de client NAS).
    val imageLoader = remember { ImageLoader.Builder(context).build() }
    var nowPlaying by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showQueue) { showQueue = false }
    BackHandler(enabled = nowPlaying && !showQueue) { nowPlaying = false }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                DownloadsScreen(
                    client = null,
                    onClose = null,
                    onRetry = onRetry,
                    onPlay = { songs, index -> player.play(null, songs, index) },
                    onMessage = {},
                )
            }
            Surface(tonalElevation = 3.dp) {
                Column {
                    if (playerUi.hasItem) {
                        MiniPlayer(playerUi, player, imageLoader, onOpen = { nowPlaying = true })
                    }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
        if (nowPlaying && playerUi.hasItem) {
            NowPlayingScreen(
                playerUi, player, imageLoader,
                client = null, // notation et ajout à une playlist demandent le NAS
                sleep = sleep,
                onSetSleep = player::setSleepTimer,
                onClose = { nowPlaying = false },
                onOpenQueue = { showQueue = true },
                onAddToPlaylist = null,
            )
        }
        if (showQueue && playerUi.hasItem) {
            QueueScreen(playerUi, player, imageLoader, client = null, onClose = { showQueue = false })
        }
    }
}
