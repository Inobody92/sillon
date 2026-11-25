package io.github.inobody92.sillon.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Titre en cours de lecture et état de la lecture, lisibles par les listes de titres sans passer par chaque écran. */
data class NowPlayingInfo(val mediaId: String = "", val isPlaying: Boolean = false) {
    fun isCurrent(songId: String) = mediaId.isNotEmpty() && mediaId == songId
}

val LocalNowPlaying = compositionLocalOf { NowPlayingInfo() }

/**
 * Petites barres d'égaliseur animées, comme sur Spotify, pour marquer le titre en cours dans une liste.
 * En pause, elles se figent à mi-hauteur (le titre reste repérable sans bouger).
 */
@Composable
fun NowPlayingBars(playing: Boolean, color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val transition = rememberInfiniteTransition(label = "now-playing")
    // Trois périodes différentes : les barres ne montent jamais ensemble.
    val heights: List<State<Float>> = listOf(620 to 0, 880 to 140, 740 to 300).mapIndexed { i, (period, offset) ->
        if (!playing) return@mapIndexed mutableFloatStateOf(REST[i])
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = period
                    0.2f at 0 using FastOutSlowInEasing
                    1f at period / 2 using FastOutSlowInEasing
                    0.2f at period using FastOutSlowInEasing
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = androidx.compose.animation.core.StartOffset(offset),
            ),
            label = "bar-$i",
        )
    }
    Canvas(modifier.size(size)) {
        val gap = this.size.width * 0.12f
        val barWidth = (this.size.width - gap * 2) / 3
        heights.forEachIndexed { i, h ->
            val barHeight = this.size.height * h.value
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), this.size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 3),
            )
        }
    }
}

private val REST = floatArrayOf(0.55f, 0.9f, 0.4f)
