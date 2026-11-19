package io.github.inobody92.sillon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.inobody92.sillon.ui.SillonTheme
import io.github.inobody92.sillon.ui.Turntable
import io.github.inobody92.sillon.ui.formatTime
import kotlinx.coroutines.delay
import java.io.File

/**
 * Démonstration de la platine avec une lecture simulée (aucun NAS nécessaire) : sert à tester la rotation,
 * le bras et le glissé. Démarrer avec : am start -n .../.TurntableDemoActivity --ez playing true
 */
class TurntableDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startPlaying = intent.getBooleanExtra("playing", true)
        val startPos = intent.getLongExtra("position", 0L)
        setContent {
            SillonTheme(dark = isSystemInDarkTheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    val loader = remember { ImageLoader.Builder(context).build() }
                    // Pochette de test asymétrique : on voit tourner le disque.
                    val cover = remember { "file://" + makeCover(File(context.cacheDir, "demo-cover.png")) }
                    var playing by remember { mutableStateOf(startPlaying) }
                    var position by remember { mutableLongStateOf(startPos) }
                    val duration = 240_000L
                    LaunchedEffect(playing) {
                        while (playing) {
                            delay(100)
                            position = (position + 100).coerceAtMost(duration)
                        }
                    }
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Turntable(
                            artwork = cover, imageLoader = loader, playing = playing,
                            positionMs = position, durationMs = duration,
                            onSeek = { position = it }, onTogglePlay = { playing = !playing },
                        )
                        Text(
                            "playing=$playing  position=${formatTime(position)} / ${formatTime(duration)}  (${position}ms)",
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        }
    }

    private fun makeCover(file: File): String {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(Color.rgb(224, 60, 60), Color.rgb(250, 200, 70), Color.rgb(60, 140, 220), Color.rgb(70, 180, 110))
        for (i in 0..3) {
            p.color = colors[i]
            c.drawRect((i % 2) * 200f, (i / 2) * 200f, (i % 2) * 200f + 200f, (i / 2) * 200f + 200f, p)
        }
        p.color = Color.WHITE
        c.drawCircle(120f, 120f, 40f, p) // repère : tourne avec le disque
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }
}
