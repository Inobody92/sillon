package io.github.inobody92.sillon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.inobody92.sillon.playback.SpectrumAnalyzer
import io.github.inobody92.sillon.playback.SpectrumBus
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/**
 * Égaliseur façon chaîne hi-fi : colonnes de petits segments, une seule teinte, un repère de crête discret.
 * Les niveaux viennent du son réellement joué (voir [SpectrumBus]), décalés du délai de sortie audio.
 */
@Composable
fun EqualizerView(playing: Boolean, onTogglePlay: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val panel = if (dark) scheme.surfaceContainerLowest else Color(0xFF1B1212)
    val accent = if (dark) scheme.primary else scheme.inversePrimary
    val peakColor = lerp(accent, Color.White, 0.55f)
    val labelColor = Color.White.copy(alpha = 0.45f)

    val bands = SpectrumAnalyzer.DEFAULT_BANDS
    val levels = remember { FloatArray(bands) }
    val peaks = remember { FloatArray(bands) }
    val peakSince = remember { LongArray(bands) }
    val target = remember { FloatArray(bands) }
    var frame by remember { mutableLongStateOf(0L) }
    val isPlaying by rememberUpdatedState(playing)

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                val live = isPlaying && SpectrumBus.frameAt(System.nanoTime(), OUTPUT_DELAY_NS, MAX_AGE_NS, target)
                for (i in 0 until bands) {
                    val goal = if (live) target[i] else 0f
                    // Montée vive, descente lente : c'est ce qui donne l'allure d'un vrai afficheur.
                    levels[i] = if (goal > levels[i]) levels[i] + (goal - levels[i]) * 0.65f
                    else max(goal, levels[i] - FALL_PER_SECOND * dt)
                    if (levels[i] >= peaks[i]) {
                        peaks[i] = levels[i]
                        peakSince[i] = now
                    } else if (now - peakSince[i] > PEAK_HOLD_NS) {
                        peaks[i] = max(levels[i], peaks[i] - PEAK_FALL_PER_SECOND * dt)
                    }
                }
                frame++
            }
        }
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontSize = 10.sp) }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onTogglePlay),
    ) {
        Canvas(Modifier.matchParentSize()) {
            frame // lecture de l'état : redessine à chaque image
            drawRect(panel)

            val pad = 18.dp.toPx()
            val labelHeight = 20.dp.toPx()
            val area = Size(size.width - 2 * pad, size.height - 2 * pad - labelHeight)
            val gap = 3.dp.toPx()
            val colWidth = (area.width - gap * (bands - 1)) / bands
            val segments = SEGMENTS
            val segGap = 2.dp.toPx()
            val segHeight = (area.height - segGap * (segments - 1)) / segments
            val radius = CornerRadius(1.5.dp.toPx())

            for (b in 0 until bands) {
                val x = pad + b * (colWidth + gap)
                val lit = floor(levels[b] * segments).toInt().coerceIn(0, segments)
                val peakSeg = (floor(peaks[b] * segments).toInt() - 1).coerceIn(0, segments - 1)
                for (s in 0 until segments) {
                    val y = pad + area.height - (s + 1) * segHeight - s * segGap
                    val color = when {
                        s < lit -> accent.copy(alpha = 0.62f + 0.38f * s / (segments - 1))
                        s == peakSeg && peaks[b] > 0.04f -> peakColor.copy(alpha = 0.9f)
                        else -> accent.copy(alpha = 0.07f)
                    }
                    drawRoundRect(color, Offset(x, y), Size(colWidth, segHeight), radius)
                }
            }

            // Repères de fréquence, placés sur la même échelle logarithmique que les colonnes.
            val span = ln(SpectrumAnalyzer.MAX_HZ / SpectrumAnalyzer.MIN_HZ)
            for ((hz, text) in TICKS) {
                val position = ln(hz / SpectrumAnalyzer.MIN_HZ) / span
                val cx = pad + position * area.width
                val layout = measurer.measure(text, labelStyle)
                val lx = (cx - layout.size.width / 2f).coerceIn(pad, size.width - pad - layout.size.width)
                drawText(layout, color = labelColor, topLeft = Offset(lx, pad + area.height + 8.dp.toPx()))
            }
        }
    }
}

private val TICKS = listOf(60f to "60", 250f to "250", 1_000f to "1k", 4_000f to "4k", 16_000f to "16k")

private const val SEGMENTS = 16
private const val OUTPUT_DELAY_NS = 120_000_000L
private const val MAX_AGE_NS = 250_000_000L
private const val PEAK_HOLD_NS = 450_000_000L
private const val FALL_PER_SECOND = 1.6f
private const val PEAK_FALL_PER_SECOND = 0.9f
